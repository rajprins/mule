/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.tooling.internal.config;

import static java.lang.String.format;
import static java.util.Collections.emptySet;
import static java.util.Comparator.comparingInt;
import static java.util.Optional.ofNullable;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static org.mule.runtime.api.connection.ConnectionValidationResult.failure;
import static org.mule.runtime.api.i18n.I18nMessageFactory.createStaticMessage;
import static org.mule.runtime.api.metadata.MetadataKeysContainerBuilder.getInstance;
import static org.mule.runtime.api.metadata.resolving.FailureCode.COMPONENT_NOT_FOUND;
import static org.mule.runtime.api.metadata.resolving.MetadataResult.success;
import static org.mule.runtime.api.value.ResolvingFailure.Builder.newFailure;
import static org.mule.runtime.api.value.ValueResult.resultFrom;
import static org.mule.runtime.core.api.util.ClassUtils.withContextClassLoader;
import static org.mule.runtime.module.extension.internal.util.MuleExtensionUtils.getClassLoader;
import static org.mule.runtime.module.tooling.internal.config.params.ParameterExtractor.extractValue;
import org.mule.metadata.api.model.MetadataType;
import org.mule.metadata.java.api.JavaTypeLoader;
import org.mule.runtime.api.component.location.ConfigurationComponentLocator;
import org.mule.runtime.api.connection.ConnectionValidationResult;
import org.mule.runtime.api.exception.MuleRuntimeException;
import org.mule.runtime.api.meta.NamedObject;
import org.mule.runtime.api.meta.model.ComponentModel;
import org.mule.runtime.api.meta.model.parameter.ParameterGroupModel;
import org.mule.runtime.api.meta.model.parameter.ParameterModel;
import org.mule.runtime.api.metadata.MetadataKey;
import org.mule.runtime.api.metadata.MetadataKeyBuilder;
import org.mule.runtime.api.metadata.MetadataKeysContainer;
import org.mule.runtime.api.metadata.descriptor.ParameterMetadataDescriptor;
import org.mule.runtime.api.metadata.resolving.MetadataFailure;
import org.mule.runtime.api.metadata.resolving.MetadataResult;
import org.mule.runtime.api.util.LazyValue;
import org.mule.runtime.api.value.ValueResult;
import org.mule.runtime.app.declaration.api.ArtifactDeclaration;
import org.mule.runtime.app.declaration.api.ComponentElementDeclaration;
import org.mule.runtime.app.declaration.api.ParameterElementDeclaration;
import org.mule.runtime.app.declaration.api.ParameterGroupElementDeclaration;
import org.mule.runtime.core.api.MuleContext;
import org.mule.runtime.core.api.config.ConfigurationException;
import org.mule.runtime.core.api.connector.ConnectionManager;
import org.mule.runtime.core.api.el.ExpressionManager;
import org.mule.runtime.core.api.extension.ExtensionManager;
import org.mule.runtime.core.internal.metadata.cache.DefaultMetadataCache;
import org.mule.runtime.extension.api.metadata.NullMetadataKey;
import org.mule.runtime.extension.api.metadata.NullMetadataResolver;
import org.mule.runtime.extension.api.property.MetadataKeyPartModelProperty;
import org.mule.runtime.extension.api.runtime.config.ConfigurationInstance;
import org.mule.runtime.extension.api.values.ValueResolvingException;
import org.mule.runtime.module.extension.internal.metadata.DefaultMetadataContext;
import org.mule.runtime.module.extension.internal.metadata.MetadataMediator;
import org.mule.runtime.module.extension.internal.runtime.config.ResolverSetBasedParameterResolver;
import org.mule.runtime.module.extension.internal.runtime.resolver.ParameterValueResolver;
import org.mule.runtime.module.extension.internal.runtime.resolver.ParametersResolver;
import org.mule.runtime.module.extension.internal.runtime.resolver.ResolverSet;
import org.mule.runtime.module.extension.internal.util.ReflectionCache;
import org.mule.runtime.module.extension.internal.value.ValueProviderMediator;
import org.mule.runtime.module.tooling.api.artifact.DeclarationSession;
import org.mule.runtime.module.tooling.internal.utils.ArtifactHelper;

import com.google.common.collect.ImmutableSet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import javax.inject.Inject;

public class InternalDeclarationSession implements DeclarationSession {

  private static final Supplier<Object> NULL_SUPPLIER = () -> null;

  @Inject
  private ConfigurationComponentLocator componentLocator;

  @Inject
  private ExtensionManager extensionManager;

  @Inject
  private ReflectionCache reflectionCache;

  @Inject
  private MuleContext muleContext;

  @Inject
  private ConnectionManager connectionManager;

  @Inject
  private ExpressionManager expressionManager;

  private LazyValue<ArtifactHelper> artifactHelperLazyValue;

  InternalDeclarationSession(ArtifactDeclaration artifactDeclaration) {
    this.artifactHelperLazyValue =
        new LazyValue<>(() -> new ArtifactHelper(extensionManager, componentLocator, artifactDeclaration));
  }

  private ArtifactHelper artifactHelper() {
    return artifactHelperLazyValue.get();
  }

  @Override
  public ConnectionValidationResult testConnection(String configName) {
    return artifactHelper()
        .findConnectionProvider(configName)
        .map(cp -> {
          Object connection = null;
          try {
            connection = cp.connect();
            return cp.validate(connection);
          } catch (Exception e) {
            return failure("Could not perform connectivity testing", e);
          } finally {
            if (connection != null) {
              cp.disconnect(connection);
            }
          }
        })
        .orElseThrow(() -> new MuleRuntimeException(createStaticMessage("Could not find connection provider")));
  }

  @Override
  public ValueResult getValues(ComponentElementDeclaration component, String parameterName) {
    return artifactHelper()
        .findComponentModel(component)
        .map(cm -> discoverValues(cm, parameterName, parameterValueResolver(component, cm), ofNullable(component.getConfigRef())))
        .orElse(resultFrom(emptySet()));
  }

  @Override
  public MetadataResult<MetadataKeysContainer> getMetadataKeys(ComponentElementDeclaration component) {
    return artifactHelper()
        .findComponentModel(component)
        .map(cm -> {
          Optional<ConfigurationInstance> configurationInstance =
              ofNullable(component.getConfigRef()).flatMap(name -> artifactHelper().getConfigurationInstance(name));
          return resolveKeys(new MetadataMediator<>(cm), configurationInstance,
                             buildMetadataKey(cm, component),
                             getClassLoader(artifactHelper().getExtensionModel(component)));
        })
        .orElseGet(() -> success(getInstance()
            .add(NullMetadataResolver.NULL_RESOLVER_NAME, ImmutableSet.of(new NullMetadataKey())).build()));
  }

  @Override
  public MetadataResult<MetadataType> inputMetadata(ComponentElementDeclaration component, String parameterName) {
    return artifactHelper()
            .findComponentModel(component)
            .map(cm -> {
              Optional<ConfigurationInstance> configurationInstance =
                      ofNullable(component.getConfigRef()).flatMap(name -> artifactHelper().getConfigurationInstance(name));
              MetadataKey metadataKey = buildMetadataKey(cm, component);
              ClassLoader extensionClassLoader = getClassLoader(artifactHelper().getExtensionModel(component));
              MetadataResult<ParameterMetadataDescriptor> parameterMetadataDescriptorMetadataResult = withContextClassLoader(extensionClassLoader, () ->
                      new MetadataMediator<>(cm).getInputMetadata(createMetadataContext(configurationInstance, extensionClassLoader),
                                                                  metadataKey, parameterName));
              if (parameterMetadataDescriptorMetadataResult.isSuccess()) {
                return MetadataResult.success(parameterMetadataDescriptorMetadataResult.get().getType());
              }
              return MetadataResult.<MetadataType>failure(parameterMetadataDescriptorMetadataResult.getFailures());
            })
            .orElseGet(() -> MetadataResult.failure(MetadataFailure.Builder.newFailure()
                                                            .withMessage(format("Error resolving metadata input metadata for the [%s:%s] component on parameter: [%s]", component.getDeclaringExtension(), component.getName(), parameterName)).withFailureCode(COMPONENT_NOT_FOUND)
                                                            .onComponent()));
  }

  @Override
  public MetadataResult<MetadataType> outputMetadata(ComponentElementDeclaration component) {
    return artifactHelper()
        .findComponentModel(component)
        .map(cm -> {
          Optional<ConfigurationInstance> configurationInstance =
              ofNullable(component.getConfigRef()).flatMap(name -> artifactHelper().getConfigurationInstance(name));

          MetadataKey metadataKey = buildMetadataKey(cm, component);
          ClassLoader extensionClassLoader = getClassLoader(artifactHelper().getExtensionModel(component));
          return withContextClassLoader(extensionClassLoader, () ->
              new MetadataMediator<>(cm).getOutputMetadata(createMetadataContext(configurationInstance, extensionClassLoader),
                                                                     metadataKey));
        })
        .orElseGet(() -> MetadataResult.failure(MetadataFailure.Builder.newFailure()
                                                        .withMessage(format("Error resolving metadata output metadata for the [%s:%s] component", component.getDeclaringExtension(), component.getName())).withFailureCode(COMPONENT_NOT_FOUND)
                                                        .onComponent()));
  }

  @Override
  public MetadataResult<MetadataType> outputAttributesMetadata(ComponentElementDeclaration component) {
    return artifactHelper()
            .findComponentModel(component)
            .map(cm -> {
              Optional<ConfigurationInstance> configurationInstance =
                      ofNullable(component.getConfigRef()).flatMap(name -> artifactHelper().getConfigurationInstance(name));

              MetadataKey metadataKey = buildMetadataKey(cm, component);
              ClassLoader extensionClassLoader = getClassLoader(artifactHelper().getExtensionModel(component));
              return withContextClassLoader(extensionClassLoader, () ->
                      new MetadataMediator<>(cm).getOutputAttributesMetadata(createMetadataContext(configurationInstance, extensionClassLoader),
                                                                   metadataKey));
            })
            .orElseGet(() -> MetadataResult.failure(MetadataFailure.Builder.newFailure()
                                                            .withMessage(format("Error resolving metadata output metadata for the [%s:%s] component", component.getDeclaringExtension(), component.getName())).withFailureCode(COMPONENT_NOT_FOUND)
                                                            .onComponent()));
  }

  private MetadataResult<MetadataKeysContainer> resolveKeys(MetadataMediator<ComponentModel> metadataMediator,
                                                            Optional<ConfigurationInstance> configurationInstance,
                                                            MetadataKey partialKey,
                                                            ClassLoader extensionClassLoader) {
    return withContextClassLoader(extensionClassLoader, () ->
      metadataMediator.getMetadataKeys(createMetadataContext(configurationInstance, extensionClassLoader),
                                       partialKey,
                                       reflectionCache)
    );
  }

  private DefaultMetadataContext createMetadataContext(Optional<ConfigurationInstance> configurationInstance, ClassLoader extensionClassLoader) {
    return new DefaultMetadataContext(() -> configurationInstance,
                                      connectionManager,
                                      new DefaultMetadataCache(),
                                      new JavaTypeLoader(extensionClassLoader));
  }

  private MetadataKey buildMetadataKey(ComponentModel componentModel, ComponentElementDeclaration elementDeclaration) {
    List<ParameterModel> keyParts = getMetadataKeyParts(componentModel);

    if (keyParts.isEmpty()) {
      return MetadataKeyBuilder.newKey(NullMetadataKey.ID).build();
    }

    MetadataKeyBuilder rootMetadataKeyBuilder = null;
    MetadataKeyBuilder metadataKeyBuilder = null;
    Map<String, Object> componentElementDeclarationParameters = getComponentElementDeclarationParameters(elementDeclaration, componentModel);
    for (ParameterModel parameterModel : keyParts) {
      String id;
      if (componentElementDeclarationParameters.containsKey(parameterModel.getName())) {
        id = (String) componentElementDeclarationParameters.get(parameterModel.getName());
      } else {
        // It is only supported to defined parts in order
        break;
      }

      if (id != null) {
        if (metadataKeyBuilder == null) {
          metadataKeyBuilder = MetadataKeyBuilder.newKey(id).withPartName(parameterModel.getName());
          rootMetadataKeyBuilder = metadataKeyBuilder;
        }
        else {
          MetadataKeyBuilder metadataKeyChildBuilder = MetadataKeyBuilder.newKey(id).withPartName(parameterModel.getName());
          metadataKeyBuilder.withChild(metadataKeyChildBuilder);
          metadataKeyBuilder = metadataKeyChildBuilder;
        }
      }
    }

    if (metadataKeyBuilder == null) {
      return MetadataKeyBuilder.newKey(NullMetadataKey.ID).build();
    }
    return rootMetadataKeyBuilder.build();
  }

  private List<ParameterModel> getMetadataKeyParts(ComponentModel componentModel) {
    return componentModel.getAllParameterModels().stream()
            .filter(p -> p.getModelProperty(MetadataKeyPartModelProperty.class).isPresent())
            .sorted(comparingInt(p -> p.getModelProperty(MetadataKeyPartModelProperty.class).get().getOrder()))
            .collect(toList());
  }


  @Override
  public void dispose() {
    //do nothing
  }

  private <T extends ComponentModel> ValueResult discoverValues(T componentModel,
                                                                String parameterName,
                                                                ParameterValueResolver parameterValueResolver,
                                                                Optional<String> configName) {
    ValueProviderMediator<T> valueProviderMediator = createValueProviderMediator(componentModel);
    try {
      return resultFrom(valueProviderMediator.getValues(parameterName,
                                                        parameterValueResolver,
                                                        configName.map(this::connectionSupplier).orElse(NULL_SUPPLIER),
                                                        configName.map(this::configSupplier).orElse(NULL_SUPPLIER)));
    } catch (ValueResolvingException e) {
      return resultFrom(newFailure(e).build());
    }
  }

  private <T extends ComponentModel> ValueProviderMediator<T> createValueProviderMediator(T constructModel) {
    return new ValueProviderMediator<>(constructModel,
                                       () -> muleContext,
                                       () -> reflectionCache);
  }

  private Supplier<Object> connectionSupplier(String configName) {
    return artifactHelper().getConnectionInstance(configName)
        .map(ci -> (Supplier<Object>) () -> ci)
        .orElse(NULL_SUPPLIER);

  }

  private Supplier<Object> configSupplier(String configName) {
    return artifactHelper().getConfigurationInstance(configName)
        .map(ci -> (Supplier<Object>) () -> ci)
        .orElse(NULL_SUPPLIER);
  }

  private <T extends ComponentModel> ParameterValueResolver parameterValueResolver(ComponentElementDeclaration componentElementDeclaration,
                                                                                   T model) {
    Map<String, Object> parametersMap = getComponentElementDeclarationParameters(componentElementDeclaration, model);

    try {
      final ResolverSet resolverSet =
          ParametersResolver.fromValues(parametersMap,
                                        muleContext,
                                        false,
                                        reflectionCache,
                                        expressionManager,
                                        model.getName())
              .getParametersAsResolverSet(model, muleContext);
      return new ResolverSetBasedParameterResolver(resolverSet, model, reflectionCache, expressionManager);
    } catch (ConfigurationException e) {
      throw new MuleRuntimeException(e);
    }
  }

  private <T extends ComponentModel> Map<String, Object> getComponentElementDeclarationParameters(ComponentElementDeclaration componentElementDeclaration,
                                                                                                  T model) {
    Map<String, Object> parametersMap = new HashMap<>();

    Map<String, ParameterGroupModel> parameterGroups =
        model.getParameterGroupModels().stream().collect(toMap(NamedObject::getName, identity()));

    List<String> parameterGroupsResolved = new ArrayList<>();

    for (ParameterGroupElementDeclaration parameterGroupElement : componentElementDeclaration.getParameterGroups()) {
      final String parameterGroupName = parameterGroupElement.getName();
      final ParameterGroupModel parameterGroupModel = parameterGroups.get(parameterGroupName);
      if (parameterGroupModel == null) {
        throw new MuleRuntimeException(createStaticMessage("Could not find parameter group with name: %s in model",
                                                           parameterGroupName));
      }

        parameterGroupsResolved.add(parameterGroupName);

        for (ParameterElementDeclaration parameterElement : parameterGroupElement.getParameters()) {
          final String parameterName = parameterElement.getName();
          final ParameterModel parameterModel = parameterGroupModel.getParameter(parameterName)
              .orElseThrow(() -> new MuleRuntimeException(createStaticMessage("Could not find parameter with name: %s in parameter group: %s",
                                                                              parameterName, parameterGroupName)));
          parametersMap.put(parameterName,
                            extractValue(parameterElement.getValue(),
                                         artifactHelper().getParameterClass(parameterModel, componentElementDeclaration)));
      }
    }

    // Default values
    model.getParameterGroupModels().stream()
        .filter(parameterGroupModel -> !parameterGroupsResolved.contains(parameterGroupModel.getName()))
        .forEach(parameterGroupModel -> parameterGroupModel.getParameterModels()
            .stream()
            .forEach(parameterModel -> parametersMap.put(model.getName(), parameterModel.getDefaultValue())));
    return parametersMap;
  }

}
