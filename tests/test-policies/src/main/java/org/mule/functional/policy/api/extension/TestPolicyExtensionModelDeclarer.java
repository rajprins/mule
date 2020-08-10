/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.functional.policy.api.extension;

import static org.mule.metadata.api.model.MetadataFormat.JAVA;
import static org.mule.runtime.api.meta.Category.SELECT;
import static org.mule.runtime.core.api.extension.MuleExtensionModelProvider.MULE_VERSION;
import static org.mule.runtime.extension.api.util.XmlModelUtils.buildSchemaLocation;

import org.mule.metadata.api.ClassTypeLoader;
import org.mule.metadata.api.builder.BaseTypeBuilder;
import org.mule.metadata.java.api.JavaTypeLoader;
import org.mule.runtime.api.meta.model.XmlDslModel;
import org.mule.runtime.api.meta.model.declaration.fluent.ConstructDeclarer;
import org.mule.runtime.api.meta.model.declaration.fluent.ExtensionDeclarer;
import org.mule.runtime.api.meta.model.declaration.fluent.NestedRouteDeclarer;
import org.mule.runtime.api.meta.model.declaration.fluent.OperationDeclarer;
import org.mule.runtime.core.internal.extension.CustomBuildingDefinitionProviderModelProperty;
import org.mule.runtime.extension.api.declaration.type.ExtensionsTypeLoaderFactory;
import org.mule.runtime.extension.internal.property.NoErrorMappingModelProperty;
import org.mule.runtime.module.extension.api.loader.java.property.CustomLocationPartModelProperty;

/**
 * An {@link ExtensionDeclarer} for test Policy components
 *
 * @since 4.4
 */
class TestPolicyExtensionModelDeclarer {

  public ExtensionDeclarer createExtensionModel() {
    final ClassTypeLoader typeLoader = ExtensionsTypeLoaderFactory.getDefault()
        .createTypeLoader(TestPolicyExtensionModelDeclarer.class
            .getClassLoader());
    final BaseTypeBuilder typeBuilder = BaseTypeBuilder.create(JavaTypeLoader.JAVA);

    ExtensionDeclarer extensionDeclarer = new ExtensionDeclarer()
        .named("test-policy")
        .describedAs("Mule Runtime and Integration Platform: test Policy components")
        .onVersion(MULE_VERSION)
        .fromVendor("MuleSoft, Inc.")
        .withCategory(SELECT)
        .withModelProperty(new CustomBuildingDefinitionProviderModelProperty())
        .withXmlDsl(XmlDslModel.builder()
            .setPrefix("test-policy")
            .setNamespace("http://www.mulesoft.org/schema/mule/test-policy")
            .setSchemaVersion(MULE_VERSION)
            .setXsdFileName("mule-test-policy.xsd")
            .setSchemaLocation(buildSchemaLocation("test-policy", "http://www.mulesoft.org/schema/mule/test-policy"))
            .build());

    declareProxy(typeBuilder, extensionDeclarer);
    declareExecuteNext(extensionDeclarer, typeBuilder, typeLoader);

    return extensionDeclarer;
  }

  private void declareProxy(final BaseTypeBuilder typeBuilder, ExtensionDeclarer extensionDeclarer) {
    final ConstructDeclarer proxyDeclarer = extensionDeclarer.withConstruct("proxy")
        .allowingTopLevelDefinition();

    proxyDeclarer
        .onDefaultParameterGroup()
        .withRequiredParameter("name")
        .describedAs("The name used to identify this policy.")
        .asComponentId()
        .ofType(typeBuilder.stringType().build());

    final NestedRouteDeclarer sourceDeclarer = proxyDeclarer
        .withRoute("source")
        .withModelProperty(new CustomLocationPartModelProperty("source", false));

    // sourceDeclarer
    // .onDefaultParameterGroup()
    // .withOptionalParameter("propagateMessageTransformations")
    // .ofType(typeBuilder.booleanType().build())
    // .describedAs("Whether changes made by the policy to the message before returning to the next policy or flow should be
    // propagated to it.")
    // .defaultingTo(false);
    sourceDeclarer.withChain();

    final NestedRouteDeclarer operationDeclarer = proxyDeclarer
        .withRoute("operation")
        .withModelProperty(new CustomLocationPartModelProperty("operation", false));

    // operationDeclarer
    // .onDefaultParameterGroup()
    // .withOptionalParameter("propagateMessageTransformations")
    // .ofType(typeBuilder.booleanType().build())
    // .describedAs("Whether changes made by the policy to the message before returning to the next policy or flow should be
    // propagated to it.")
    // .defaultingTo(false);
    operationDeclarer.withChain();
  }


  private void declareExecuteNext(ExtensionDeclarer extensionDeclarer, BaseTypeBuilder typeBuilder, ClassTypeLoader typeLoader) {
    OperationDeclarer executeNext = extensionDeclarer
        .withOperation("executeNext")
        .withModelProperty(new NoErrorMappingModelProperty());

    // By this operation alone we cannot determine what its output will be, it will depend on the context on which this operation
    // is located.
    executeNext.withOutput().ofType(BaseTypeBuilder.create(JAVA).anyType().build());
    executeNext.withOutputAttributes().ofType(BaseTypeBuilder.create(JAVA).anyType().build());;
  }

}
