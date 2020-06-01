/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.tooling.internal.data;

import org.mule.runtime.module.tooling.api.data.DataProviderService;
import org.mule.runtime.module.deployment.impl.internal.application.DefaultApplicationFactory;
import org.mule.runtime.module.tooling.api.data.DataProviderServiceBuilder;
import org.mule.runtime.module.tooling.internal.AbstractArtifactAgnosticServiceBuilder;
import org.mule.runtime.module.tooling.internal.ApplicationSupplier;

public class DefaultDataProviderServiceBuilder
    extends AbstractArtifactAgnosticServiceBuilder<DataProviderServiceBuilder, DataProviderService>
    implements DataProviderServiceBuilder {

  private static final String CONFIG_ELEMENT_NAME = "config";
  private static final String DUMMY_CONFIG_NAME = "dummyConfig";

  public DefaultDataProviderServiceBuilder(DefaultApplicationFactory defaultApplicationFactory) {
    super(defaultApplicationFactory);
  }

  //@Override
  //public DataProviderServiceBuilder setConnectionDeclaration(ConnectionElementDeclaration connectionDeclaration) {
  //  final String extensionName = connectionDeclaration.getDeclaringExtension();
  //  final ElementDeclarer elementDeclarer = ElementDeclarer.forExtension(extensionName);
  //  ArtifactDeclarer artifactDeclarer = newArtifact();
  //  ConfigurationElementDeclaration dummyConfig = elementDeclarer.newConfiguration(CONFIG_ELEMENT_NAME)
  //      .withRefName(DUMMY_CONFIG_NAME).withConnection(connectionDeclaration).getDeclaration();
  //  this.artifactDeclaration = artifactDeclarer.withGlobalElement(dummyConfig).getDeclaration();
  //  return this;
  //}

  @Override
  protected DataProviderService createService(ApplicationSupplier applicationSupplier) {
    return new TemporaryArtifactDataProviderService(applicationSupplier);
  }
}
