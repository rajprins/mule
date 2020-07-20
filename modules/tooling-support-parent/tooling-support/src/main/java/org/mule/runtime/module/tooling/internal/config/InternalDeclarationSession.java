/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.tooling.internal.config;

import static java.util.Collections.emptyMap;
import static java.util.Collections.emptySet;
import static java.util.Comparator.comparingInt;
import static java.util.Optional.ofNullable;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static org.mule.runtime.api.connection.ConnectionValidationResult.failure;
import static org.mule.runtime.api.i18n.I18nMessageFactory.createStaticMessage;
import static org.mule.runtime.api.metadata.MetadataKeysContainerBuilder.getInstance;
import static org.mule.runtime.api.metadata.resolving.MetadataResult.success;
import static org.mule.runtime.api.value.ResolvingFailure.Builder.newFailure;
import static org.mule.runtime.api.value.ValueResult.resultFrom;
import static org.mule.runtime.core.api.util.ClassUtils.withContextClassLoader;
import static org.mule.runtime.core.internal.event.NullEventFactory.getNullEvent;
import static org.mule.runtime.module.extension.internal.util.MuleExtensionUtils.getClassLoader;
import static org.mule.runtime.module.tooling.internal.config.cache.NoOpMetadataCache.getNoOpCache;
import static org.mule.runtime.module.tooling.internal.config.params.ParameterExtractor.extractValue;
import org.mule.metadata.api.model.MetadataType;
import org.mule.metadata.java.api.JavaTypeLoader;
import org.mule.runtime.api.component.location.ConfigurationComponentLocator;
import org.mule.runtime.api.connection.ConnectionValidationResult;
import org.mule.runtime.api.exception.MuleRuntimeException;
import org.mule.runtime.api.meta.NamedObject;
import org.mule.runtime.api.meta.model.ComponentModel;
import org.mule.runtime.api.meta.model.ExtensionModel;
import org.mule.runtime.api.meta.model.HasOutputModel;
import org.mule.runtime.api.meta.model.parameter.ParameterGroupModel;
import org.mule.runtime.api.meta.model.parameter.ParameterModel;
import org.mule.runtime.api.metadata.MetadataKey;
import org.mule.runtime.api.metadata.MetadataKeyBuilder;
import org.mule.runtime.api.metadata.MetadataKeysContainer;
import org.mule.runtime.api.metadata.MetadataResolvingException;
import org.mule.runtime.api.metadata.descriptor.ComponentMetadataDescriptor;
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
import org.mule.runtime.core.api.event.CoreEvent;
import org.mule.runtime.core.api.extension.ExtensionManager;
import org.mule.runtime.extension.api.metadata.NullMetadataKey;
import org.mule.runtime.extension.api.metadata.NullMetadataResolver;
import org.mule.runtime.extension.api.property.MetadataKeyIdModelProperty;
import org.mule.runtime.extension.api.property.MetadataKeyPartModelProperty;
import org.mule.runtime.extension.api.runtime.config.ConfigurationInstance;
import org.mule.runtime.extension.api.values.ValueResolvingException;
import org.mule.runtime.module.extension.api.runtime.privileged.EventedExecutionContext;
import org.mule.runtime.module.extension.internal.metadata.DefaultMetadataContext;
import org.mule.runtime.module.extension.internal.metadata.MetadataKeyIdObjectResolver;
import org.mule.runtime.module.extension.internal.metadata.MetadataMediator;
import org.mule.runtime.module.extension.internal.runtime.config.ResolverSetBasedParameterResolver;
import org.mule.runtime.module.extension.internal.runtime.operation.OperationParameterValueResolver;
import org.mule.runtime.module.extension.internal.runtime.resolver.ParameterValueResolver;
import org.mule.runtime.module.extension.internal.runtime.resolver.ParametersResolver;
import org.mule.runtime.module.extension.internal.runtime.resolver.ResolverSet;
import org.mule.runtime.module.extension.internal.runtime.resolver.ValueResolver;
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
          MetadataKey metadataKey = buildMetadataKey(cm, component);
          try {
            ClassLoader extensionClassLoader = getClassLoader(artifactHelper().getExtensionModel(component));
            Object resolve = metadataKey != null ?
                             withContextClassLoader(extensionClassLoader, () -> new MetadataKeyIdObjectResolver(cm).resolveWithPartialKey(metadataKey), MetadataResolvingException.class, e -> new MuleRuntimeException(e))
                     : null;
            return resolveKeys(new MetadataMediator<>(cm), configurationInstance,
                               resolve,
                               extensionClassLoader);
          } catch (MetadataResolvingException e) {
            return MetadataResult.<MetadataKeysContainer>failure(MetadataFailure.Builder.newFailure(e).onKeys());
          }
        })
        .orElseGet(() -> success(getInstance()
            .add(NullMetadataResolver.NULL_RESOLVER_NAME, ImmutableSet.of(new NullMetadataKey())).build()));
  }

  @Override
  public MetadataResult<MetadataType> outputMetadata(ComponentElementDeclaration component) {
    return artifactHelper()
        .findComponentModel(component)
        .map(cm -> {
          Optional<ConfigurationInstance> configurationInstance =
              ofNullable(component.getConfigRef()).flatMap(name -> artifactHelper().getConfigurationInstance(name));
          ParameterValueResolver parameterValueResolver = parameterMetadataValueResolver(component, cm, configurationInstance);
          ClassLoader extensionClassLoader = getClassLoader(artifactHelper().getExtensionModel(component));
          MetadataMediator<? extends ComponentModel> metadataMediator = new MetadataMediator<>(cm);
          MetadataResult<? extends ComponentMetadataDescriptor<? extends ComponentModel>> metadata =
              metadataMediator.getMetadata(new DefaultMetadataContext(() -> configurationInstance,
                                                                      connectionManager,
                                                                      getNoOpCache(),
                                                                      new JavaTypeLoader(extensionClassLoader)),
                                           parameterValueResolver, reflectionCache);
          if (!metadata.isSuccess()) {
            return MetadataResult.<MetadataType>failure(metadata.getFailures());
          }
          ComponentModel model = metadata.get().getModel();
          // Check this before resolving!
          if (model instanceof HasOutputModel) {
            return MetadataResult.success(((HasOutputModel) model).getOutput().getType());
          }
          // TODO: improve error!
          return MetadataResult.<MetadataType>failure(MetadataFailure.Builder.newFailure().onOutputPayload());

        })
        .orElseGet(() -> MetadataResult.failure(MetadataFailure.Builder.newFailure().onOutputPayload()));
  }

  private ParameterValueResolver parameterMetadataValueResolver(ComponentElementDeclaration componentElementDeclaration,
                                                                ComponentModel componentModel,
                                                                Optional<ConfigurationInstance> configurationInstance) {
    // The same logic is applied for Source/Operation
    return new OperationParameterValueResolver(new DeclarationExecutionContext(componentElementDeclaration, componentModel,
                                                                               configurationInstance),
                                               new EmptyResolverSet(muleContext), reflectionCache, expressionManager);
  }

  private class DeclarationExecutionContext implements EventedExecutionContext {

    private ComponentElementDeclaration componentElementDeclaration;
    private ComponentModel componentModel;
    private Map<String, Object> parameters;
    private Optional<ConfigurationInstance> configurationInstanceOptional;

    public DeclarationExecutionContext(ComponentElementDeclaration componentElementDeclaration,
                                       ComponentModel componentModel,
                                       Optional<ConfigurationInstance> configurationInstanceOptional) {
      this.componentElementDeclaration = componentElementDeclaration;
      this.componentModel = componentModel;
      this.parameters = getComponentElementDeclarationParameters(componentElementDeclaration, componentModel);
      componentModel.getAllParameterModels().stream()
          .filter(parameterModel -> !parameters.containsKey(parameterModel.getName()))
          .forEach(parameterModel -> {
            if (parameterModel.getDefaultValue() != null) {
              parameters.put(parameterModel.getName(), parameterModel.getDefaultValue());
            }
          });

      this.configurationInstanceOptional = configurationInstanceOptional;
    }

    @Override
    public boolean hasParameter(String parameterName) {
      return parameters.containsKey(parameterName);
    }

    @Override
    public Map<String, Object> getParameters() {
      return parameters;
    }

    @Override
    public Optional<ConfigurationInstance> getConfiguration() {
      return configurationInstanceOptional;
    }

    @Override
    public ExtensionModel getExtensionModel() {
      return artifactHelper().getExtensionModel(componentElementDeclaration);
    }

    @Override
    public ComponentModel getComponentModel() {
      return componentModel;
    }

    @Override
    public Object getParameterOrDefault(String parameterName, Object defaultValue) {
      return parameters.getOrDefault(parameterName, defaultValue);
    }

    @Override
    public Object getParameter(String parameterName) {
      return parameters.get(parameterName);
    }

    @Override
    public CoreEvent getEvent() {
      return getNullEvent();
    }

    @Override
    public void changeEvent(CoreEvent updated) {}
  }

  private MetadataResult<MetadataKeysContainer> resolveKeys(MetadataMediator<ComponentModel> metadataMediator,
                                                            Optional<ConfigurationInstance> configurationInstance,
                                                            Object partialKey,
                                                            ClassLoader extensionClassLoader) {
    return metadataMediator.getMetadataKeys(new DefaultMetadataContext(() -> configurationInstance,
                                                                       connectionManager,
                                                                       getNoOpCache(),
                                                                       new JavaTypeLoader(extensionClassLoader)),
                                            partialKey,
                                            new ReflectionCache());
  }

  private MetadataKey buildMetadataKey(ComponentModel componentModel, ComponentElementDeclaration elementDeclaration) {
    if (!getMetadataKeyIdModelProperty(componentModel).isPresent()) {
      return null;//TODO
    }

    MetadataKeyBuilder metadataKeyBuilder = null;

    Map<String, Object> componentElementDeclarationParameters = getComponentElementDeclarationParameters(elementDeclaration, componentModel);
    for (ParameterModel parameterModel : getMetadataKeyParts(componentModel)) {
      String id = null;
      if (componentElementDeclarationParameters.containsKey(parameterModel.getName())) {
        id = (String) componentElementDeclarationParameters.get(parameterModel.getName());
      }
      if (id != null) {
        if (metadataKeyBuilder == null) {
          metadataKeyBuilder = MetadataKeyBuilder.newKey(id).withPartName(parameterModel.getName());
        }
        else {
          MetadataKeyBuilder metadataKeyChildBuilder = MetadataKeyBuilder.newKey(id).withPartName(parameterModel.getName());
          metadataKeyBuilder.withChild(metadataKeyChildBuilder);
          metadataKeyBuilder = metadataKeyChildBuilder;
        }
      }
    }

    return metadataKeyBuilder == null ? null : metadataKeyBuilder.build();
  }

  private Optional<MetadataKeyIdModelProperty> getMetadataKeyIdModelProperty(ComponentModel componentModel) {
    return componentModel.getModelProperty(MetadataKeyIdModelProperty.class);
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

  private class EmptyResolverSet extends ResolverSet {

    public EmptyResolverSet(MuleContext muleContext) {
      super(muleContext);
    }

    @Override
    public Map<String, ValueResolver<?>> getResolvers() {
      return emptyMap();
    }
  }
}
