/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.tooling.internal.data;

import static java.util.Collections.emptySet;
import static java.util.Collections.singletonMap;
import static java.util.Optional.ofNullable;
import static org.mule.runtime.api.i18n.I18nMessageFactory.createStaticMessage;
import static org.mule.runtime.api.value.ResolvingFailure.Builder.newFailure;
import static org.mule.runtime.api.value.ValueResult.resultFrom;
import org.mule.runtime.api.component.location.ConfigurationComponentLocator;
import org.mule.runtime.api.component.location.Location;
import org.mule.runtime.api.connection.ConnectionProvider;
import org.mule.runtime.api.exception.MuleException;
import org.mule.runtime.api.exception.MuleRuntimeException;
import org.mule.runtime.api.meta.model.ComponentModel;
import org.mule.runtime.api.meta.model.EnrichableModel;
import org.mule.runtime.api.meta.model.ExtensionModel;
import org.mule.runtime.api.meta.model.construct.ConstructModel;
import org.mule.runtime.api.meta.model.operation.HasOperationModels;
import org.mule.runtime.api.meta.model.operation.OperationModel;
import org.mule.runtime.api.meta.model.parameter.ParameterizedModel;
import org.mule.runtime.api.meta.model.source.HasSourceModels;
import org.mule.runtime.api.meta.model.source.SourceModel;
import org.mule.runtime.api.meta.model.util.ExtensionWalker;
import org.mule.runtime.api.metadata.DataProviderService;
import org.mule.runtime.api.util.Reference;
import org.mule.runtime.api.value.ValueResult;
import org.mule.runtime.app.declaration.api.ComponentElementDeclaration;
import org.mule.runtime.app.declaration.api.ConfigurationElementDeclaration;
import org.mule.runtime.app.declaration.api.GlobalElementDeclarationVisitor;
import org.mule.runtime.app.declaration.api.ParameterValueVisitor;
import org.mule.runtime.app.declaration.api.fluent.ParameterSimpleValue;
import org.mule.runtime.core.api.MuleContext;
import org.mule.runtime.core.api.extension.ExtensionManager;
import org.mule.runtime.deployment.model.api.application.Application;
import org.mule.runtime.extension.api.values.ValueResolvingException;
import org.mule.runtime.module.extension.internal.runtime.resolver.ConnectionProviderResolver;
import org.mule.runtime.module.extension.internal.runtime.resolver.ParameterValueResolver;
import org.mule.runtime.module.extension.internal.runtime.resolver.StaticValueResolver;
import org.mule.runtime.module.extension.internal.runtime.resolver.ValueResolver;
import org.mule.runtime.module.extension.internal.util.ReflectionCache;
import org.mule.runtime.module.extension.internal.value.ValueProviderMediator;
import org.mule.runtime.module.tooling.internal.AbstractArtifactAgnosticService;
import org.mule.runtime.module.tooling.internal.ApplicationSupplier;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;


public class TemporaryArtifactDataProviderService extends AbstractArtifactAgnosticService implements DataProviderService {

  TemporaryArtifactDataProviderService(ApplicationSupplier applicationSupplier) {
    super(applicationSupplier);
  }

  @Override
  public Map<String, ValueResult> discover() {
    return withTemporaryApplication(
                                    app -> singletonMap("", resultFrom(emptySet())),
                                    err -> singletonMap("", resultFrom(newFailure(err).build())));
  }

  @Override
  public ValueResult getValues(ComponentElementDeclaration component, String parameterName) {
    return withTemporaryApplication(
                                    app -> getExtensionModel(app, component.getDeclaringExtension())
                                        .flatMap(
                                                 em -> getComponentModel(em, component.getName())
                                                     .map(cm -> createValueProviderMediator(cm, app)))
                                        .map(
                                             vpm -> {
                                               try {
                                                 return vpm.getValues(parameterName, parameterValueResolver(component),
                                                                      connectionSupplier(app), () -> null);
                                               } catch (ValueResolvingException e) {
                                                 throw new MuleRuntimeException(e);
                                               }
                                             })
                                        .map(ValueResult::resultFrom)
                                        .orElse(resultFrom(emptySet())),
                                    err -> resultFrom(newFailure(err).build()));
  }

  private <T extends ParameterizedModel & EnrichableModel> ValueProviderMediator<T> createValueProviderMediator(T constructModel,
                                                                                                                Application application) {
    return new ValueProviderMediator<>(constructModel,
                                       muleContextSupplier(application),
                                       reflectionCacheSupplier(application));
  }

  private Optional<? extends ComponentModel> getComponentModel(ExtensionModel extensionModel, String componentName) {
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

  private Supplier<Object> connectionSupplier(Application application) {
    final Reference<ConfigurationElementDeclaration> configDeclaration = new Reference<>();
    final GlobalElementDeclarationVisitor visitor = new GlobalElementDeclarationVisitor() {

      @Override
      public void visit(ConfigurationElementDeclaration declaration) {
        configDeclaration.set(declaration);
      }
    };
    application.getDescriptor().getArtifactDeclaration().getGlobalElements().forEach(gld -> gld.accept(visitor));
    final Location connectionLocation =
        Location.builder().globalName(configDeclaration.get().getRefName()).addConnectionPart().build();
    return application
        .getRegistry()
        .lookupByType(ConfigurationComponentLocator.class)
        .flatMap(cl -> cl.find(connectionLocation))
        .filter(o -> o instanceof ConnectionProviderResolver)
        .map(cp -> (ConnectionProviderResolver) cp)
        .map(cr -> (Supplier<Object>) () -> {
          try {
            return ((ConnectionProvider) cr.resolve(null).getFirst()).connect();
          } catch (Exception e) {
            throw new MuleRuntimeException(e);
          }
        })
        .orElse(() -> null);
  }

  private Optional<ExtensionModel> getExtensionModel(Application application, String extensionName) {
    return application
        .getRegistry()
        .lookupByType(ExtensionManager.class)
        .map(em -> em.getExtension(extensionName))
        .orElseThrow(() -> new MuleRuntimeException(createStaticMessage("Could not find extensionModel")));
  }

  private Supplier<MuleContext> muleContextSupplier(Application application) {
    return () -> application
        .getRegistry()
        .lookupByType(MuleContext.class)
        .orElseThrow(() -> new MuleRuntimeException(createStaticMessage("Could not get MuleContext from application")));
  }

  private Supplier<ReflectionCache> reflectionCacheSupplier(Application application) {
    return () -> application
        .getRegistry()
        .lookupByType(ReflectionCache.class)
        .orElseThrow(() -> new MuleRuntimeException(createStaticMessage("Could not get ReflectionCache from application")));
  }
}
