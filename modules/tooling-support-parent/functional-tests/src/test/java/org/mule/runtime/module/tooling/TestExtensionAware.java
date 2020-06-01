/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.tooling;

import static org.mule.runtime.app.declaration.api.fluent.ElementDeclarer.newArtifact;
import static org.mule.runtime.app.declaration.api.fluent.ElementDeclarer.newParameterGroup;
import org.mule.runtime.app.declaration.api.ArtifactDeclaration;
import org.mule.runtime.app.declaration.api.ConfigurationElementDeclaration;
import org.mule.runtime.app.declaration.api.ConnectionElementDeclaration;
import org.mule.runtime.app.declaration.api.OperationElementDeclaration;
import org.mule.runtime.app.declaration.api.SourceElementDeclaration;
import org.mule.runtime.app.declaration.api.fluent.ElementDeclarer;

public interface TestExtensionAware {

  ElementDeclarer TEST_EXTENSION_DECLARER = ElementDeclarer.forExtension("ToolingSupportTest");
  String CONFIG_ELEMENT_NAME = "config";
  String CONNECTION_ELEMENT_NAME = "tstConnection";

  String PROVIDED_PARAMETER_NAME = "providedParameter";
  String ACTING_PARAMETER_NAME = "actingParameter";

  String SOURCE_ELEMENT_NAME = "simple";
  String INDEPENDENT_SOURCE_PARAMETER_NAME = "independentParam";
  String CONNECTION_DEPENDANT_SOURCE_PARAMETER_NAME = "connectionDependantParam";
  String ACTING_PARAMETER_DEPENDANT_SOURCE_PARAMETER_NAME = "actingParameterDependantParam";

  String CONFIG_LESS_CONNECTION_LESS_VPOP_ELEMENT_NAME = "configLessConnectionLessVPOP";
  String CONFIG_LESS_VPOP_ELEMENT_NAME = "configLessVPOP";
  String ACTING_PARAMETER_VPOP_ELEMENT_NAME = "actingParameterVPOP";

  String CONNECTION_CLIENT_NAME_PARAMETER = "clientName";

  default ArtifactDeclaration artifactDeclaration(ConfigurationElementDeclaration config) {
    return newArtifact().withGlobalElement(config).getDeclaration();
  }

  default ConfigurationElementDeclaration configurationDeclaration(String name, ConnectionElementDeclaration connection) {
    return TEST_EXTENSION_DECLARER.newConfiguration(CONFIG_ELEMENT_NAME)
        .withRefName(name)
        .withConnection(connection)
        .getDeclaration();
  }

  default ConnectionElementDeclaration connectionDeclaration(String clientName) {
    return TEST_EXTENSION_DECLARER.newConnection(CONNECTION_ELEMENT_NAME)
        .withParameterGroup(newParameterGroup().withParameter(CONNECTION_CLIENT_NAME_PARAMETER, clientName).getDeclaration())
        .getDeclaration();
  }

  default OperationElementDeclaration configLessConnectionLessVPOPDeclaration(String configName) {
    return TEST_EXTENSION_DECLARER
        .newOperation(CONFIG_LESS_CONNECTION_LESS_VPOP_ELEMENT_NAME)
        .withConfig(configName)
        .withParameterGroup(newParameterGroup()
            .withParameter(PROVIDED_PARAMETER_NAME, "")
            .getDeclaration())
        .getDeclaration();

  }

  default OperationElementDeclaration configLessVPOPDeclaration(String configName) {
    return TEST_EXTENSION_DECLARER
        .newOperation(CONFIG_LESS_VPOP_ELEMENT_NAME)
        .withConfig(configName)
        .withParameterGroup(newParameterGroup()
            .withParameter(PROVIDED_PARAMETER_NAME, "")
            .getDeclaration())
        .getDeclaration();

  }

  default OperationElementDeclaration actingParameterVPOPDeclaration(String configName, String actingParameter) {
    return TEST_EXTENSION_DECLARER
        .newOperation(ACTING_PARAMETER_VPOP_ELEMENT_NAME)
        .withConfig(configName)
        .withParameterGroup(newParameterGroup()
            .withParameter(ACTING_PARAMETER_NAME, actingParameter)
            .withParameter(PROVIDED_PARAMETER_NAME, "")
            .getDeclaration())
        .getDeclaration();

  }

  default SourceElementDeclaration sourceDeclaration(String configName, String actingParameter) {
    return TEST_EXTENSION_DECLARER
        .newSource(SOURCE_ELEMENT_NAME)
        .withConfig(configName)
        .withParameterGroup(newParameterGroup()
            .withParameter(INDEPENDENT_SOURCE_PARAMETER_NAME, "")
            .withParameter(CONNECTION_DEPENDANT_SOURCE_PARAMETER_NAME, "")
            .withParameter(ACTING_PARAMETER_DEPENDANT_SOURCE_PARAMETER_NAME, "")
            .withParameter(ACTING_PARAMETER_NAME, actingParameter)
            .getDeclaration())
        .getDeclaration();
  }

}
