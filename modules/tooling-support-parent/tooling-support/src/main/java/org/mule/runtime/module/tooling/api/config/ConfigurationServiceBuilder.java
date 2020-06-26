/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.tooling.api.config;

import org.mule.runtime.app.declaration.api.ConfigurationElementDeclaration;
import org.mule.runtime.module.tooling.api.ArtifactAgnosticServiceBuilder;

/**
 * Provides all required steps to configure and build a new {@link ConfigurationService}
 *
 * @since 4.4.0
 */
public interface ConfigurationServiceBuilder
    extends ArtifactAgnosticServiceBuilder<ConfigurationServiceBuilder, ConfigurationService> {

  /**
   * Add the values for the configuration to be used to resolve all metadata with the constucted {@link ConfigurationService}
   *
   * @param configurationDeclaration the {@link ConfigurationElementDeclaration} to configure this service.
   * @return this builder
   */
  ConfigurationServiceBuilder withConfigurationDeclaration(ConfigurationElementDeclaration configurationDeclaration);

}
