/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.tooling.internal.data;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptySet;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toList;
import static org.mule.runtime.api.i18n.I18nMessageFactory.createStaticMessage;
import static org.mule.runtime.api.value.ResolvingFailure.Builder.newFailure;
import static org.mule.runtime.module.tooling.internal.data.DefaultDataProviderResult.success;
import org.mule.runtime.api.component.location.ConfigurationComponentLocator;
import org.mule.runtime.api.component.location.Location;
import org.mule.runtime.api.connection.ConnectionProvider;
import org.mule.runtime.api.exception.MuleException;
import org.mule.runtime.api.exception.MuleRuntimeException;
import org.mule.runtime.api.meta.model.ComponentModel;
import org.mule.runtime.api.meta.model.EnrichableModel;
import org.mule.runtime.api.meta.model.ExtensionModel;
import org.mule.runtime.api.meta.model.config.ConfigurationModel;
import org.mule.runtime.api.meta.model.operation.HasOperationModels;
import org.mule.runtime.api.meta.model.operation.OperationModel;
import org.mule.runtime.api.meta.model.parameter.ParameterizedModel;
import org.mule.runtime.api.meta.model.source.HasSourceModels;
import org.mule.runtime.api.meta.model.source.SourceModel;
import org.mule.runtime.api.meta.model.util.ExtensionWalker;
import org.mule.runtime.api.util.Reference;
import org.mule.runtime.app.declaration.api.ArtifactDeclaration;
import org.mule.runtime.app.declaration.api.ComponentElementDeclaration;
import org.mule.runtime.app.declaration.api.ConfigurationElementDeclaration;
import org.mule.runtime.app.declaration.api.GlobalElementDeclarationVisitor;
import org.mule.runtime.app.declaration.api.ParameterValueVisitor;
import org.mule.runtime.app.declaration.api.fluent.ParameterSimpleValue;
import org.mule.runtime.core.api.MuleContext;
import org.mule.runtime.core.api.extension.ExtensionManager;
import org.mule.runtime.core.api.util.func.CheckedSupplier;
import org.mule.runtime.extension.api.runtime.config.ConfigurationProvider;
import org.mule.runtime.extension.api.values.ValueResolvingException;
import org.mule.runtime.module.extension.internal.loader.java.property.ValueProviderFactoryModelProperty;
import org.mule.runtime.module.extension.internal.runtime.resolver.ConnectionProviderResolver;
import org.mule.runtime.module.extension.internal.runtime.resolver.ParameterValueResolver;
import org.mule.runtime.module.extension.internal.runtime.resolver.StaticValueResolver;
import org.mule.runtime.module.extension.internal.runtime.resolver.ValueResolver;
import org.mule.runtime.module.extension.internal.util.ReflectionCache;
import org.mule.runtime.module.extension.internal.value.ValueProviderMediator;
import org.mule.runtime.module.tooling.api.data.DataProviderResult;
import org.mule.runtime.module.tooling.api.data.DataProviderService;
import org.mule.runtime.module.tooling.api.data.DataResult;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import javax.inject.Inject;

public class InternalDataProviderService implements DataProviderService {

  @Inject
  private ConfigurationComponentLocator componentLocator;

  @Inject
  private ExtensionManager extensionManager;

  @Inject
  private ReflectionCache reflectionCache;

  @Inject
  private MuleContext muleContext;

  private ArtifactDeclaration artifactDeclaration;

  InternalDataProviderService(ArtifactDeclaration artifactDeclaration) {
    this.artifactDeclaration = artifactDeclaration;
  }

  @Override
  public DataProviderResult<List<DataResult>> discover() {
    return success(findConfigurationModel()
        .map(this::discoverValuesFromConfigModel)
        .orElse(emptyList()));
  }

  @Override
  public DataProviderResult<DataResult> getValues(ComponentElementDeclaration component, String parameterName) {
    return success(
                   extensionManager.getExtension(component.getDeclaringExtension())
                       .flatMap(em -> findComponentModel(em, component.getName()))
                       .map(cm -> getValues(cm, parameterName, parameterValueResolver(component)))
                       .orElse(new DefaultDataResult(parameterName, emptySet())));
  }

  private <T extends ParameterizedModel & EnrichableModel> DataResult getValues(T componentModel,
                                                                                String parameterName,
                                                                                ParameterValueResolver parameterValueResolver) {
    ValueProviderMediator<T> valueProviderMediator = createValueProviderMediator(componentModel);
    try {
      return new DefaultDataResult(getResolverName(componentModel, parameterName),
                                   valueProviderMediator.getValues(parameterName,
                                                                   parameterValueResolver,
                                                                   connectionSupplier(),
                                                                   () -> null));
    } catch (ValueResolvingException e) {
      return new DefaultDataResult(getResolverName(componentModel, parameterName),
                                   newFailure(e).build());
    }
  }

  private String getResolverName(ParameterizedModel componentModel, String parameterName) {
    return componentModel
        .getAllParameterModels()
        .stream()
        .filter(pm -> Objects.equals(pm.getName(), parameterName)
            && pm.getModelProperty(ValueProviderFactoryModelProperty.class).isPresent())
        .findAny()
        .flatMap(pm -> pm.getModelProperty(ValueProviderFactoryModelProperty.class))
        .map(mp -> mp.getValueProvider().getSimpleName())
        .orElseThrow(() -> new MuleRuntimeException(createStaticMessage("Could not find parameter with name: %s",
                                                                        parameterName)));
  }

  private List<DataResult> discoverValuesFromConfigModel(ConfigurationModel configurationModel) {
    final List<DataResult> results = new LinkedList<>();
    configurationModel.getOperationModels().stream().map(this::getValuesForModel).forEach(results::addAll);
    configurationModel.getSourceModels().stream().map(this::getValuesForModel).forEach(results::addAll);
    return results;
  }

  private <T extends ParameterizedModel & EnrichableModel> List<DataResult> getValuesForModel(T model) {
    return model.getAllParameterModels()
        .stream()
        .filter(pm -> pm.getValueProviderModel().isPresent())
        .map(vpp -> getValues(model, vpp.getName(), null))
        .collect(toList());
  }

  private <T extends ParameterizedModel & EnrichableModel> ValueProviderMediator<T> createValueProviderMediator(T constructModel) {
    return new ValueProviderMediator<>(constructModel,
                                       () -> muleContext,
                                       () -> reflectionCache);
  }

  private Optional<? extends ComponentModel> findComponentModel(ExtensionModel extensionModel, String componentName) {
    final Reference<ComponentModel> foundModel = new Reference<>();
    new ExtensionWalker() {

      @Override
      protected void onOperation(HasOperationModels owner, OperationModel model) {
        setAndStop(model);
      }

      @Override
      protected void onSource(HasSourceModels owner, SourceModel model) {
        setAndStop(model);
      }

      private void setAndStop(ComponentModel model) {
        if (Objects.equals(model.getName(), componentName)) {
          foundModel.set(model);
          stop();
        }
      }
    }.walk(extensionModel);
    return ofNullable(foundModel.get());
  }

  private ParameterValueResolver parameterValueResolver(ComponentElementDeclaration componentElementDeclaration) {
    Map<String, ValueResolver<?>> parametersMap = new HashMap<>();

    final Reference<Object> valueRef = new Reference<>();

    final ParameterValueVisitor parameterVisitor = new ParameterValueVisitor() {

      @Override
      public void visitSimpleValue(ParameterSimpleValue text) {
        valueRef.set(text.getValue());
      }

    };

    componentElementDeclaration
        .getParameterGroups()
        .forEach(
                 parameterGroup -> parameterGroup
                     .getParameters()
                     .forEach(
                              p -> {
                                p.getValue().accept(parameterVisitor);
                                parametersMap.put(p.getName(), new StaticValueResolver<>(valueRef.get()));
                              }));

    return new ParameterValueResolver() {

      private final Map<String, ValueResolver<?>> parameters = parametersMap;

      @Override
      public Object getParameterValue(String parameterName) {
        ValueResolver<?> valueResolver = parameters.get(parameterName);
        try {
          return valueResolver != null ? valueResolver.resolve(null) : null;
        } catch (MuleException e) {
          throw new MuleRuntimeException(e);
        }
      }

      @Override
      public Map<String, ValueResolver<?>> getParameters() {
        return parametersMap;
      }
    };
  }

  private Optional<ConfigurationElementDeclaration> findConfigurationDeclaration() {
    final Reference<ConfigurationElementDeclaration> configDeclaration = new Reference<>();
    final GlobalElementDeclarationVisitor visitor = new GlobalElementDeclarationVisitor() {

      @Override
      public void visit(ConfigurationElementDeclaration declaration) {
        configDeclaration.set(declaration);
      }
    };
    artifactDeclaration.getGlobalElements().forEach(gld -> gld.accept(visitor));
    return ofNullable(configDeclaration.get());
  }

  private Optional<ConfigurationModel> findConfigurationModel() {
    return findConfigurationProvider().map(ConfigurationProvider::getConfigurationModel);
  }

  private Optional<ConfigurationProvider> findConfigurationProvider() {
    return findConfigurationDeclaration()
        .map(ced -> Location.builder().globalName(ced.getRefName()).build())
        .flatMap(cloc -> componentLocator.find(cloc))
        .filter(cp -> cp instanceof ConfigurationProvider)
        .map(cp -> (ConfigurationProvider) cp);
  }

  private Supplier<Object> connectionSupplier() {
    return findConfigurationDeclaration()
        .map(ced -> Location.builder().globalName(ced.getRefName()).addConnectionPart().build())
        .flatMap(conloc -> componentLocator.find(conloc))
        .filter(c -> c instanceof ConnectionProviderResolver)
        .map(cpr -> (ConnectionProviderResolver) cpr)
        .map(cpr -> (Supplier<Object>) () -> handlingException(
                                                               () -> ((ConnectionProvider) cpr
                                                                   .resolve(null)
                                                                   .getFirst())
                                                                       .connect()))
        .orElse(() -> null);

  }

  private <T> T handlingException(CheckedSupplier<T> supplier, String... errorMessage) {
    try {
      return supplier.get();
    } catch (RuntimeException e) {
      if (errorMessage.length > 0) {
        throw new MuleRuntimeException(createStaticMessage(errorMessage[0]), e);
      }
      throw e;
    }
  }

}
