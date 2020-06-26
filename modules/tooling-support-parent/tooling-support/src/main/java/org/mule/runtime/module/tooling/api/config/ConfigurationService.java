/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.tooling.api.config;


import org.mule.api.annotation.NoImplement;
import org.mule.runtime.api.connection.ConnectionValidationResult;
import org.mule.runtime.api.value.ValueResult;
import org.mule.runtime.app.declaration.api.ComponentElementDeclaration;

/**
 * Service in charge or resolving connector's operations and retrieving metadata for all
 * components related to the same Configuration.
 * <p/>
 * This service provides the possibility to avoid having a full artifact configuration before being able to
 * gather metadata from the connector.
 * <p/>
 * Each instance of {@link ConfigurationService} should handle one and only one connector configuration.
 *
 * @since 4.4.0
 */
@NoImplement
public interface ConfigurationService {

  /**
   * Test connectivity for the connection associated to this configuration.
   *
   * @return a {@link ConnectionValidationResult} with the result of the connectivity testing
   */
  ConnectionValidationResult testConnection();

  /**
   * Retrieve all {@link org.mule.runtime.api.value.Value} that can be configured for the given parameter.
   * @param component a {@link ComponentElementDeclaration} for the Component (Operation, Source, etc) from which
   *                  the available values can be used on the parameter {@param parameterName}. In case the value
   *                  provider requires any acting parameters to be able to resolve this values, those parameters
   *                  should be populated in this declaration.
   * @param parameterName the name of the parameter for which to resolve the {@link org.mule.runtime.api.value.Value}s
   * @return a {@link ValueResult} with the accepted parameter values to use
   */
  ValueResult getValues(ComponentElementDeclaration component, String parameterName);

  /**
   * Stops and disposes all resources used by this {@link ConfigurationService}
   */
  void dispose();

}
