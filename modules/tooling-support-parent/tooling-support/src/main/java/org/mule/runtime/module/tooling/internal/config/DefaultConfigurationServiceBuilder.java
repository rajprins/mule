/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.tooling.internal.config;

import static org.mule.runtime.app.declaration.api.fluent.ElementDeclarer.newArtifact;
import org.mule.runtime.app.declaration.api.ArtifactDeclaration;
import org.mule.runtime.app.declaration.api.ConfigurationElementDeclaration;
import org.mule.runtime.module.tooling.api.config.ConfigurationService;
import org.mule.runtime.module.deployment.impl.internal.application.DefaultApplicationFactory;
import org.mule.runtime.module.tooling.api.config.ConfigurationServiceBuilder;
import org.mule.runtime.module.tooling.internal.AbstractArtifactAgnosticServiceBuilder;
import org.mule.runtime.module.tooling.internal.ApplicationSupplier;

public class DefaultConfigurationServiceBuilder
    extends AbstractArtifactAgnosticServiceBuilder<ConfigurationServiceBuilder, ConfigurationService>
    implements ConfigurationServiceBuilder {

  private ArtifactDeclaration artifactDeclaration;

  public DefaultConfigurationServiceBuilder(DefaultApplicationFactory defaultApplicationFactory) {
    super(defaultApplicationFactory);
  }

  @Override
  public ConfigurationServiceBuilder withConfigurationDeclaration(ConfigurationElementDeclaration configurationDeclaration) {
    this.artifactDeclaration = newArtifact().withGlobalElement(configurationDeclaration).getDeclaration();
    return this;
  }

  //@Override
  //public ConfigurationServiceBuilder setConnectionDeclaration(ConnectionElementDeclaration connectionDeclaration) {
  //  final String extensionName = connectionDeclaration.getDeclaringExtension();
  //  final ElementDeclarer elementDeclarer = ElementDeclarer.forExtension(extensionName);
  //  ArtifactDeclarer artifactDeclarer = newArtifact();
  //  ConfigurationElementDeclaration dummyConfig = elementDeclarer.newConfiguration(CONFIG_ELEMENT_NAME)
  //      .withRefName(DUMMY_CONFIG_NAME).withConnection(connectionDeclaration).getDeclaration();
  //  this.artifactDeclaration = artifactDeclarer.withGlobalElement(dummyConfig).getDeclaration();
  //  return this;
  //}

  @Override
  protected ConfigurationService createService(ApplicationSupplier applicationSupplier) {
    return new DefaultConfigurationService(applicationSupplier);
  }

  @Override
  protected ArtifactDeclaration getArtifactDeclaration() {
    return this.artifactDeclaration;
  }

}
