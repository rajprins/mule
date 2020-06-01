/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.tooling.internal.data;

import static org.mule.runtime.api.i18n.I18nMessageFactory.createStaticMessage;
import static org.mule.runtime.module.tooling.internal.data.DefaultDataResult.failure;
import org.mule.runtime.api.exception.MuleException;
import org.mule.runtime.api.exception.MuleRuntimeException;
import org.mule.runtime.core.api.MuleContext;
import org.mule.runtime.module.tooling.api.data.DataProviderService;
import org.mule.runtime.api.value.Value;
import org.mule.runtime.app.declaration.api.ComponentElementDeclaration;
import org.mule.runtime.deployment.model.api.application.Application;
import org.mule.runtime.module.tooling.api.data.DataResult;
import org.mule.runtime.module.tooling.internal.AbstractArtifactAgnosticService;
import org.mule.runtime.module.tooling.internal.ApplicationSupplier;

import java.util.Map;
import java.util.Set;


public class TemporaryArtifactDataProviderService extends AbstractArtifactAgnosticService implements DataProviderService {

  TemporaryArtifactDataProviderService(ApplicationSupplier applicationSupplier) {
    super(applicationSupplier);
  }

  private DataProviderService withServiceFrom(Application application) {
    final InternalDataProviderService internalDataProviderService =
        new InternalDataProviderService(application.getDescriptor().getArtifactDeclaration());
    return application.getRegistry()
        .lookupByType(MuleContext.class)
        .map(muleContext -> {
          try {
            return muleContext.getInjector().inject(internalDataProviderService);
          } catch (MuleException e) {
            throw new MuleRuntimeException(createStaticMessage("Could not inject values into DataProviderService"));
          }
        })
        .orElseThrow(() -> new MuleRuntimeException(createStaticMessage("Could not find injector to create InternalDataProviderService")));
  }

  @Override
  public DataResult<Map<String, Set<Value>>> discover() {
    return withTemporaryApplication(
                                    app -> withServiceFrom(app).discover(),
                                    err -> failure());
  }

  @Override
  public DataResult<Set<Value>> getValues(ComponentElementDeclaration component, String parameterName) {
    return withTemporaryApplication(
                                    app -> withServiceFrom(app).getValues(component, parameterName),
                                    err -> failure());
  }


}
