/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.tooling;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.mule.runtime.app.declaration.api.fluent.ElementDeclarer.newArtifact;
import static org.mule.runtime.app.declaration.api.fluent.ElementDeclarer.newParameterGroup;
import static org.mule.test.infrastructure.maven.MavenTestUtils.getMavenLocalRepository;
import org.mule.runtime.api.component.location.Location;
import org.mule.runtime.api.connection.ConnectionValidationResult;
import org.mule.runtime.api.connectivity.ConnectivityTestingService;
import org.mule.runtime.api.metadata.DataProviderService;
import org.mule.runtime.api.value.ValueResult;
import org.mule.runtime.app.declaration.api.ArtifactDeclaration;
import org.mule.runtime.app.declaration.api.fluent.ElementDeclarer;
import org.mule.runtime.module.tooling.api.ArtifactAgnosticServiceBuilder;
import org.mule.runtime.module.tooling.api.ToolingService;
import org.mule.tck.junit4.rule.SystemProperty;
import org.mule.test.infrastructure.deployment.AbstractFakeMuleServerTestCase;

import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;

public class DataProviderServiceTestCase extends AbstractFakeMuleServerTestCase {

  private static final String EXTENSION_GROUP_ID = "org.mule.tooling";
  private static final String EXTENSION_ARTIFACT_ID = "extension-with-metadata";
  private static final String EXTENSION_VERSION = "1.0.0-SNAPSHOT";
  private static final String EXTENSION_CLASSIFIER = "mule-plugin";
  private static final String EXTENSION_TYPE = "jar";

  private static final String CONFIG_ELEMENT_NAME = "config";
  private static final String CONFIG_NAME = "dummyConfig";
  private static final String CONNECTION_ELEMENT_NAME = "ewm";

  private static final ElementDeclarer declarer = ElementDeclarer.forExtension("Extension with Metadata");

  private ToolingService toolingService;

  @ClassRule
  public static SystemProperty artifactsLocation =
      new SystemProperty("mule.test.maven.artifacts.dir", DataProviderService.class.getResource("/").getPath());

  @Rule
  public SystemProperty repositoryLocation =
      new SystemProperty("muleRuntimeConfig.maven.repositoryLocation", getMavenLocalRepository().getAbsolutePath());

  @Override
  public void setUp() throws Exception {
    super.setUp();
    this.toolingService = this.muleServer.toolingService();
    this.muleServer.start();
  }

  @Test
  public void testConnection() {
    ConnectivityTestingService connectivityTestingService =
        addDependency(toolingService.newConnectivityTestingServiceBuilder())
            .setArtifactDeclaration(buildArtifactDeclaration())
            .build();

    ConnectionValidationResult connectionValidationResult =
        connectivityTestingService.testConnection(Location.builderFromStringRepresentation(CONFIG_NAME).build());
    assertThat(connectionValidationResult.isValid(), equalTo(true));
  }

  @Test
  public void testValueProvider() {
    DataProviderService dataProviderService =
        addDependency(toolingService.newDataProviderServiceBuilder())
            .setArtifactDeclaration(buildArtifactDeclaration())
            .build();

    ValueResult valueResult =
        dataProviderService.getValues(
                                      declarer
                                          .newOperation("ewmProvidedValuesOperation")
                                          .withParameterGroup(
                                                              newParameterGroup()
                                                                  .withParameter("actingParameter", "test")
                                                                  .getDeclaration())
                                          .getDeclaration(),
                                      "providedParameter");
    assertThat(valueResult.isSuccess(), equalTo(true));
  }

  private <T extends ArtifactAgnosticServiceBuilder<T, ?>> T addDependency(T serviceBuilder) {
    return serviceBuilder.addDependency(EXTENSION_GROUP_ID, EXTENSION_ARTIFACT_ID, EXTENSION_VERSION, EXTENSION_CLASSIFIER,
                                        EXTENSION_TYPE);
  }

  private ArtifactDeclaration buildArtifactDeclaration() {
    return newArtifact().withGlobalElement(
                                           declarer.newConfiguration(CONFIG_ELEMENT_NAME)
                                               .withRefName(CONFIG_NAME)
                                               .withConnection(declarer.newConnection(CONNECTION_ELEMENT_NAME)
                                                   .withParameterGroup(
                                                                       newParameterGroup()
                                                                           .withParameter("parameterRequiredForMetadata", "test")
                                                                           .withParameter("parameterNotRequiredForMetadata",
                                                                                          "test")
                                                                           .withParameter("notActingParameter", "test")
                                                                           .getDeclaration())
                                                   .getDeclaration())
                                               .withParameterGroup(newParameterGroup()
                                                   .withParameter("requiredForMetadata", "test")
                                                   .withParameter("notRequiredForMetadata", "test")
                                                   .withParameter("notActingParameter", "test")
                                                   .getDeclaration())
                                               .getDeclaration())
        .getDeclaration();
  }

}
