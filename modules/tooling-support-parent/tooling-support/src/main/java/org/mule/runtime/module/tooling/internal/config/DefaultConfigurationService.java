/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.tooling.internal.config;

import static org.mule.runtime.api.i18n.I18nMessageFactory.createStaticMessage;
import static org.mule.runtime.api.value.ResolvingFailure.Builder.newFailure;
import static org.mule.runtime.api.value.ValueResult.resultFrom;
import org.mule.runtime.api.connection.ConnectionValidationResult;
import org.mule.runtime.api.exception.MuleException;
import org.mule.runtime.api.exception.MuleRuntimeException;
import org.mule.runtime.api.util.LazyValue;
import org.mule.runtime.api.value.ValueResult;
import org.mule.runtime.core.api.MuleContext;
import org.mule.runtime.module.tooling.api.config.ConfigurationService;
import org.mule.runtime.app.declaration.api.ComponentElementDeclaration;
import org.mule.runtime.deployment.model.api.application.Application;
import org.mule.runtime.module.tooling.internal.AbstractArtifactAgnosticService;
import org.mule.runtime.module.tooling.internal.ApplicationSupplier;

//TODO: Refactor this, don't think we need to have the temporary application logic. Also, handle concurrency
//and operations on disposed services.
public class DefaultConfigurationService extends AbstractArtifactAgnosticService implements ConfigurationService {

  private LazyValue<ConfigurationService> internalConfigurationService;

  DefaultConfigurationService(ApplicationSupplier applicationSupplier) {
    super(applicationSupplier);
    this.internalConfigurationService = new LazyValue<>(() -> {
      try {
        return createInternalService(getStartedApplication());
      } catch (ApplicationStartingException e) {
        throw new MuleRuntimeException(e);
      }
    });
  }

  private ConfigurationService createInternalService(Application application) {
    final InternalConfigurationService internalDataProviderService =
        new InternalConfigurationService(application.getDescriptor().getArtifactDeclaration());
    return application.getRegistry()
        .lookupByType(MuleContext.class)
        .map(muleContext -> {
          try {
            return muleContext.getInjector().inject(internalDataProviderService);
          } catch (MuleException e) {
            throw new MuleRuntimeException(createStaticMessage("Could not inject values into ConfigurationService"));
          }
        })
        .orElseThrow(() -> new MuleRuntimeException(createStaticMessage("Could not find injector to create InternalConfigurationService")));
  }

  //@Override
  //public DataProviderResult<List<DataResult>> discover() {
  //  return withTemporaryApplication(
  //                                  app -> createInternalService(app).discover(),
  //                                  err -> failure(newFailure(err).build()));
  //}

  private ConfigurationService withInternalService() {
    return this.internalConfigurationService.get();
  }

  @Override
  public ConnectionValidationResult testConnection() {
    return withInternalService().testConnection();
  }

  @Override
  public ValueResult getValues(ComponentElementDeclaration component, String parameterName) {
    return withInternalService().getValues(component, parameterName);
  }

  @Override
  public void dispose() {
    super.dispose();
  }


}
