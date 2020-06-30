/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.tooling;

import static java.util.Arrays.asList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mule.test.infrastructure.maven.MavenTestUtils.getMavenLocalRepository;
import org.mule.runtime.api.connection.ConnectionValidationResult;
import org.mule.runtime.api.value.ValueResult;
import org.mule.runtime.app.declaration.api.ComponentElementDeclaration;
import org.mule.runtime.module.tooling.api.config.ConfigurationService;
import org.mule.tck.junit4.rule.SystemProperty;
import org.mule.test.infrastructure.deployment.AbstractFakeMuleServerTestCase;

import java.util.List;

import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;

public class ConfigurationServiceTestCase extends AbstractFakeMuleServerTestCase implements TestExtensionAware {

  private static final String EXTENSION_GROUP_ID = "org.mule.tooling";
  private static final String EXTENSION_ARTIFACT_ID = "tooling-support-test-extension";
  private static final String EXTENSION_VERSION = "1.0.0-SNAPSHOT";
  private static final String EXTENSION_CLASSIFIER = "mule-plugin";
  private static final String EXTENSION_TYPE = "jar";

  private static final String CONFIG_NAME = "dummyConfig";
  private static final String CLIENT_NAME = "client";
  private static final String PROVIDED_PARAMETER_NAME = "providedParameter";
  private static final String METADATA_KEY_PARAMETER = "metadataKey";

  private ConfigurationService configurationService;

  @ClassRule
  public static SystemProperty artifactsLocation =
      new SystemProperty("mule.test.maven.artifacts.dir", ConfigurationService.class.getResource("/").getPath());

  @Rule
  public SystemProperty repositoryLocation =
      new SystemProperty("muleRuntimeConfig.maven.repositoryLocation", getMavenLocalRepository().getAbsolutePath());

  @Override
  public void setUp() throws Exception {
    super.setUp();
    this.configurationService = this.muleServer
        .toolingService()
        .newConfigurationServiceBuilder()
        .addDependency(EXTENSION_GROUP_ID,
                       EXTENSION_ARTIFACT_ID,
                       EXTENSION_VERSION,
                       EXTENSION_CLASSIFIER,
                       EXTENSION_TYPE)
        .withConfigurationDeclaration(configurationDeclaration(CONFIG_NAME, connectionDeclaration(CLIENT_NAME)))
        .build();
    this.muleServer.start();
  }

  @Test
  public void testConnection() {
    ConnectionValidationResult connectionValidationResult = configurationService.testConnection();
    assertThat(connectionValidationResult.isValid(), equalTo(true));
  }

  //@Test
  //public void discoverAllValues() {
  //  ConfigurationService configurationService =
  //      addDependency(toolingService.newConfigurationServiceBuilder())
  //          .withConfigurationDeclaration(buildConfigDeclaration())
  //          .build();
  //  DataProviderResult<List<DataResult>> providerResult = configurationService.discover();
  //  assertThat(providerResult.isSuccessful(), is(true));
  //
  //  AtomicInteger totalValidations = new AtomicInteger();
  //  Consumer<DataResult> failureValidator = r -> {
  //    assertThat(r.isSuccessful(), is(false));
  //    totalValidations.incrementAndGet();
  //  };
  //
  //  //TODO: FIX THIS, WE SHOULD BE ABLE TO DISTINGUISH VALUE PROVIDERS ACCORDING TO THEIR ACTING PARAMETERS
  //  validateResult(providerResult.getResult(), "ActingParameterVP", r -> {
  //    if (r.isSuccessful()) {
  //      Set<String> results = r.getData().stream().map(DataValue::getId).collect(toSet());
  //      if (r.getData().size() == 3) {
  //        assertThat(results, containsInAnyOrder(expectedParameter("ONE"), expectedParameter("TWO"), expectedParameter("THREE")));
  //        totalValidations.incrementAndGet();
  //      } else {
  //        fail();
  //      }
  //    } else {
  //      failureValidator.accept(r);
  //    }
  //  });
  //  validateResult(providerResult.getResult(), "ActingParameterGroupVP", failureValidator);
  //  validateResult(providerResult.getResult(), "LevelTwoVP", r -> {
  //    assertThat(r.isSuccessful(), is(true));
  //    Set<String> results = r.getData().stream().map(DataValue::getId).collect(toSet());
  //    assertThat(results, containsInAnyOrder("LEVEL-TWO-ONE-ONE",
  //                                           "LEVEL-TWO-ONE-TWO",
  //                                           "LEVEL-TWO-ONE-THREE",
  //                                           "LEVEL-TWO-TWO-ONE",
  //                                           "LEVEL-TWO-TWO-TWO",
  //                                           "LEVEL-TWO-TWO-THREE",
  //                                           "LEVEL-TWO-THREE-ONE",
  //                                           "LEVEL-TWO-THREE-TWO",
  //                                           "LEVEL-TWO-THREE-THREE"));
  //    totalValidations.incrementAndGet();
  //  });
  //  validateResult(providerResult.getResult(), "LevelThreeVP", r -> {
  //    assertThat(r.isSuccessful(), is(true));
  //    Set<String> results = r.getData().stream().map(DataValue::getId).collect(toSet());
  //    assertThat(results, containsInAnyOrder("LEVEL-THREE-ONE-LEVEL-TWO-ONE-ONE",
  //                                           "LEVEL-THREE-ONE-LEVEL-TWO-ONE-TWO",
  //                                           "LEVEL-THREE-ONE-LEVEL-TWO-ONE-THREE",
  //                                           "LEVEL-THREE-ONE-LEVEL-TWO-TWO-ONE",
  //                                           "LEVEL-THREE-ONE-LEVEL-TWO-TWO-TWO",
  //                                           "LEVEL-THREE-ONE-LEVEL-TWO-TWO-THREE",
  //                                           "LEVEL-THREE-ONE-LEVEL-TWO-THREE-ONE",
  //                                           "LEVEL-THREE-ONE-LEVEL-TWO-THREE-TWO",
  //                                           "LEVEL-THREE-ONE-LEVEL-TWO-THREE-THREE",
  //
  //                                           "LEVEL-THREE-TWO-LEVEL-TWO-ONE-ONE",
  //                                           "LEVEL-THREE-TWO-LEVEL-TWO-ONE-TWO",
  //                                           "LEVEL-THREE-TWO-LEVEL-TWO-ONE-THREE",
  //                                           "LEVEL-THREE-TWO-LEVEL-TWO-TWO-ONE",
  //                                           "LEVEL-THREE-TWO-LEVEL-TWO-TWO-TWO",
  //                                           "LEVEL-THREE-TWO-LEVEL-TWO-TWO-THREE",
  //                                           "LEVEL-THREE-TWO-LEVEL-TWO-THREE-ONE",
  //                                           "LEVEL-THREE-TWO-LEVEL-TWO-THREE-TWO",
  //                                           "LEVEL-THREE-TWO-LEVEL-TWO-THREE-THREE",
  //
  //                                           "LEVEL-THREE-THREE-LEVEL-TWO-ONE-ONE",
  //                                           "LEVEL-THREE-THREE-LEVEL-TWO-ONE-TWO",
  //                                           "LEVEL-THREE-THREE-LEVEL-TWO-ONE-THREE",
  //                                           "LEVEL-THREE-THREE-LEVEL-TWO-TWO-ONE",
  //                                           "LEVEL-THREE-THREE-LEVEL-TWO-TWO-TWO",
  //                                           "LEVEL-THREE-THREE-LEVEL-TWO-TWO-THREE",
  //                                           "LEVEL-THREE-THREE-LEVEL-TWO-THREE-ONE",
  //                                           "LEVEL-THREE-THREE-LEVEL-TWO-THREE-TWO",
  //                                           "LEVEL-THREE-THREE-LEVEL-TWO-THREE-THREE"));
  //    totalValidations.incrementAndGet();
  //  });
  //  validateResult(providerResult.getResult(), "MultipleValuesSimpleVP", r -> {
  //    assertThat(r.isSuccessful(), is(true));
  //    Set<String> results = r.getData().stream().map(DataValue::getId).collect(toSet());
  //    assertThat(results, containsInAnyOrder("ONE", "TWO", "THREE"));
  //    totalValidations.incrementAndGet();
  //  });
  //  validateResult(providerResult.getResult(), "ConfigLessConnectionLessNoActingParamVP", r -> {
  //    assertThat(r.isSuccessful(), is(true));
  //    assertThat(r.getData(), hasSize(1));
  //    assertThat(r.getData().iterator().next().getId(), is("ConfigLessConnectionLessNoActingParameter"));
  //    totalValidations.incrementAndGet();
  //  });
  //  validateResult(providerResult.getResult(), "ConfigLessNoActingParamVP", r -> {
  //    assertThat(r.isSuccessful(), is(true));
  //    assertThat(r.getData(), hasSize(1));
  //    assertThat(r.getData().iterator().next().getId(), is(CLIENT_NAME));
  //    totalValidations.incrementAndGet();
  //  });
  //  validateResult(providerResult.getResult(), "ConfigLessConnectionLessMetadataResolver", r -> {
  //    assertThat(r.isSuccessful(), is(true));
  //    assertThat(r.getData(), hasSize(1));
  //    assertThat(r.getData().iterator().next().getId(), is("ConfigLessConnectionLessMetadataResolver"));
  //    totalValidations.incrementAndGet();
  //  });
  //  validateResult(providerResult.getResult(), "ConfigLessMetadataResolver", r -> {
  //    assertThat(r.isSuccessful(), is(true));
  //    assertThat(r.getData(), hasSize(1));
  //    assertThat(r.getData().iterator().next().getId(), is(CLIENT_NAME));
  //    totalValidations.incrementAndGet();
  //  });
  //  assertThat(totalValidations.get(), is(providerResult.getResult().size()));
  //}

  //private void validateResult(List<DataResult> results, String resolverName, Consumer<DataResult> validator) {
  //  results.stream().filter(r -> r.getResolverName().equals(resolverName)).forEach(validator);
  //}

  private String expectedParameter(String actingParameter) {
    return "WITH-ACTING-PARAMETER-" + actingParameter;
  }

  @Test
  public void configLessConnectionLessOnOperation() {
    ComponentElementDeclaration elementDeclaration = configLessConnectionLessOPDeclaration(CONFIG_NAME);
    getResultAndValidate(elementDeclaration, PROVIDED_PARAMETER_NAME, "ConfigLessConnectionLessNoActingParameter");
    //getResultAndValidate(elementDeclaration, METADATA_KEY_PARAMETER, "ConfigLessConnectionLessMetadataResolver");
  }

  @Test
  public void configLessOnOperation() {
    ComponentElementDeclaration elementDeclaration = configLessOPDeclaration(CONFIG_NAME);
    getResultAndValidate(elementDeclaration, PROVIDED_PARAMETER_NAME, CLIENT_NAME);
    //getResultAndValidate(elementDeclaration, METADATA_KEY_PARAMETER, CLIENT_NAME);
  }

  @Test
  public void actingParameterOnOperation() {
    final String actingParameter = "actingParameter";
    ComponentElementDeclaration elementDeclaration = actingParameterOPDeclaration(CONFIG_NAME, actingParameter);
    getResultAndValidate(elementDeclaration, PROVIDED_PARAMETER_NAME, expectedParameter(actingParameter));
  }

  @Test
  public void actingParameterGroup() {
    final String stringValue = "stringValue";
    final int intValue = 0;
    final List<String> listValue = asList("one", "two", "three");
    ComponentElementDeclaration elementDeclaration =
        actingParameterGroupOPDeclaration(CONFIG_NAME, stringValue, intValue, listValue);
    getResultAndValidate(elementDeclaration, PROVIDED_PARAMETER_NAME, "stringValue-0-one-two-three");
  }

  @Test
  public void complexActingParameter() {
    final String stringValue = "stringValue";
    ComponentElementDeclaration elementDeclaration =
        complexActingParameterOPDeclaration(CONFIG_NAME, stringValue);
    getResultAndValidate(elementDeclaration, PROVIDED_PARAMETER_NAME, stringValue);
  }

  private void getResultAndValidate(ComponentElementDeclaration elementDeclaration, String parameterName, String expectedValue) {
    ValueResult providerResult = configurationService.getValues(elementDeclaration, parameterName);

    assertThat(providerResult.isSuccess(), equalTo(true));
    assertThat(providerResult.getValues(), hasSize(1));
    assertThat(providerResult.getValues().iterator().next().getId(), is(expectedValue));
  }

}
