/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.extension.internal.resources;

import static java.lang.Boolean.getBoolean;
import static java.lang.String.format;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mule.runtime.api.dsl.DslResolvingContext.getDefault;
import static org.mule.runtime.api.util.MuleSystemProperties.SYSTEM_PROPERTY_PREFIX;
import static org.mule.runtime.core.api.util.FileUtils.stringToFile;
import static org.mule.runtime.core.api.util.IOUtils.getResourceAsString;
import static org.mule.runtime.core.api.util.IOUtils.getResourceAsUrl;
import static org.mule.runtime.core.api.util.IOUtils.toByteArray;
import static org.mule.runtime.module.extension.api.loader.AbstractJavaExtensionModelLoader.TYPE_PROPERTY_NAME;
import static org.mule.runtime.module.extension.api.loader.AbstractJavaExtensionModelLoader.VERSION;
import static org.mule.runtime.module.extension.internal.resources.BaseExtensionResourcesGeneratorAnnotationProcessor.COMPILATION_MODE;
import static org.mule.runtime.module.extension.internal.resources.ExtensionModelJsonGeneratorTestCase.ExtensionJsonGeneratorTestUnit.newTestUnit;

import org.mule.extension.test.extension.reconnection.ReconnectionExtension;
import org.mule.runtime.api.dsl.DslResolvingContext;
import org.mule.runtime.api.meta.model.ExtensionModel;
import org.mule.runtime.core.api.registry.ServiceRegistry;
import org.mule.runtime.extension.api.loader.DeclarationEnricher;
import org.mule.runtime.extension.api.loader.ExtensionModelLoader;
import org.mule.runtime.extension.api.persistence.ExtensionModelJsonSerializer;
import org.mule.runtime.module.extension.api.loader.java.DefaultJavaExtensionModelLoader;
import org.mule.runtime.module.extension.internal.loader.enricher.JavaXmlDeclarationEnricher;
import org.mule.runtime.module.extension.soap.api.loader.SoapExtensionModelLoader;
import org.mule.tck.junit4.AbstractMuleTestCase;
import org.mule.tck.size.SmallTest;
import org.mule.test.data.sample.extension.SampleDataExtension;
import org.mule.test.function.extension.WeaveFunctionExtension;
import org.mule.test.heisenberg.extension.HeisenbergExtension;
import org.mule.test.implicit.config.extension.extension.api.ImplicitConfigExtension;
import org.mule.test.marvel.MarvelExtension;
import org.mule.test.metadata.extension.MetadataExtension;
import org.mule.test.nonimplicit.config.extension.extension.api.NonImplicitConfigExtension;
import org.mule.test.oauth.TestOAuthExtension;
import org.mule.test.petstore.extension.PetStoreConnector;
import org.mule.test.ram.RickAndMortyExtension;
import org.mule.test.semantic.extension.SemanticTermsExtension;
import org.mule.test.substitutiongroup.extension.SubstitutionGroupExtension;
import org.mule.test.subtypes.extension.SubTypesMappingConnector;
import org.mule.test.transactional.TransactionalExtension;
import org.mule.test.typed.value.extension.extension.TypedValueExtension;
import org.mule.test.values.extension.ValuesExtension;
import org.mule.test.vegan.extension.VeganExtension;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.skyscreamer.jsonassert.JSONAssert;

@SmallTest
@RunWith(Parameterized.class)
public class ExtensionModelJsonGeneratorTestCase extends AbstractMuleTestCase {

  private static final boolean UPDATE_EXPECTED_FILES_ON_ERROR =
      getBoolean(SYSTEM_PROPERTY_PREFIX + "extensionModelJson.updateExpectedFilesOnError");

  static Map<String, ExtensionModel> extensionModels = new HashMap<>();

  private static final ExtensionModelLoader javaLoader = new DefaultJavaExtensionModelLoader();
  private static final ExtensionModelLoader soapLoader = new SoapExtensionModelLoader();

  @Parameterized.Parameter
  public ExtensionModel extensionUnderTest;

  @Parameterized.Parameter(1)
  public String expectedSource;

  private ExtensionModelJsonSerializer generator;
  private String expectedJson;

  @Parameterized.Parameters(name = "{1}")
  public static Collection<Object[]> data() {
    List<ExtensionJsonGeneratorTestUnit> extensions;
    extensions = asList(newTestUnit(javaLoader, VeganExtension.class, "vegan.json"),
                        newTestUnit(javaLoader, PetStoreConnector.class, "petstore.json"),
                        newTestUnit(javaLoader, MetadataExtension.class, "metadata.json"),
                        newTestUnit(javaLoader, HeisenbergExtension.class, "heisenberg.json"),
                        newTestUnit(javaLoader, SubstitutionGroupExtension.class, "substitutiongroup.json"),
                        newTestUnit(javaLoader, TransactionalExtension.class, "tx-ext.json"),
                        newTestUnit(javaLoader, SubTypesMappingConnector.class, "subtypes.json"),
                        newTestUnit(javaLoader, MarvelExtension.class, "marvel.json"),
                        newTestUnit(soapLoader, RickAndMortyExtension.class, "ram.json"),
                        newTestUnit(javaLoader, TypedValueExtension.class, "typed-value.json"),
                        newTestUnit(javaLoader, TestOAuthExtension.class, "test-oauth.json"),
                        newTestUnit(javaLoader, WeaveFunctionExtension.class, "test-fn.json"),
                        newTestUnit(javaLoader, ValuesExtension.class, "values.json"),
                        newTestUnit(javaLoader, SampleDataExtension.class, "sample-data.json"),
                        newTestUnit(javaLoader, ImplicitConfigExtension.class, "implicit-config.json"),
                        newTestUnit(javaLoader, NonImplicitConfigExtension.class, "non-implicit-config.json"),
                        newTestUnit(javaLoader, SemanticTermsExtension.class, "semantic-terms-extension.json"),
                        newTestUnit(javaLoader, ReconnectionExtension.class, "reconnection-extension.json"));

    return createExtensionModels(extensions);
  }

  protected static Collection<Object[]> createExtensionModels(List<ExtensionJsonGeneratorTestUnit> extensions) {
    final ClassLoader classLoader = ExtensionModelJsonGeneratorTestCase.class.getClassLoader();
    final ServiceRegistry serviceRegistry = mock(ServiceRegistry.class);
    when(serviceRegistry.lookupProviders(DeclarationEnricher.class, classLoader))
        .thenReturn(singletonList(new JavaXmlDeclarationEnricher()));

    BiFunction<Class<?>, ExtensionModelLoader, ExtensionModel> createExtensionModel = (extension, loader) -> {
      ExtensionModel model = loadExtension(extension, loader);

      if (extensionModels.put(model.getName(), model) != null) {
        throw new IllegalArgumentException(format("Extension names must be unique. Name [%s] for extension [%s] was already used",
                                                  model.getName(), extension.getName()));
      }

      return model;
    };

    return extensions.stream()
        .map(e -> e.toTestParams(createExtensionModel))
        .collect(toList());
  }

  /**
   * Utility to batch fix input files when severe model changes are introduced. Use carefully, not a mechanism to get away with
   * anything. First check why the generated json is different and make sure you're not introducing any bugs. This should NEVER be
   * committed as true
   *
   * @return whether or not the "expected" test files should be updated when comparison fails
   */
  private boolean shouldUpdateExpectedFilesOnError() {
    return UPDATE_EXPECTED_FILES_ON_ERROR;
  }

  @Before
  public void setup() throws IOException {
    generator = new ExtensionModelJsonSerializer(true);
    expectedJson = getResourceAsString("models/" + expectedSource, getClass()).trim();
  }

  @Test
  public void generate() throws Exception {
    final String json = generator.serialize(extensionUnderTest).trim();
    try {
      JSONAssert.assertEquals(expectedJson, json, true);
    } catch (AssertionError e) {

      if (shouldUpdateExpectedFilesOnError()) {
        updateExpectedJson(json);
      } else {
        System.out.println(json);

        throw e;
      }
    }
  }

  @Test
  public void load() {
    ExtensionModel result = generator.deserialize(expectedJson);
    assertThat(result, is(extensionUnderTest));
  }

  @AfterClass
  public static void cleanUp() {
    extensionModels = new HashMap<>();
  }

  private void updateExpectedJson(String json) throws URISyntaxException, IOException {
    File root = new File(getResourceAsUrl("models/" + expectedSource, getClass()).toURI()).getParentFile()
        .getParentFile().getParentFile().getParentFile();
    File testDir = new File(root, "src/test/resources/models");
    File target = new File(testDir, expectedSource);
    stringToFile(target.getAbsolutePath(), json);

    System.out.println(expectedSource + " fixed");
  }

  public static ExtensionModel loadExtension(Class<?> clazz, ExtensionModelLoader loader) {
    Map<String, Object> params = new HashMap<>();
    params.put(TYPE_PROPERTY_NAME, clazz.getName());
    params.put(VERSION, "4.0.0-SNAPSHOT");
    // TODO MULE-14517: This workaround should be replaced for a better and more complete mechanism
    params.put(COMPILATION_MODE, true);
    // TODO MULE-11797: as this utils is consumed from
    // org.mule.runtime.module.extension.internal.capability.xml.schema.AbstractXmlResourceFactory.generateResource(org.mule.runtime.api.meta.model.ExtensionModel),
    // this util should get dropped once the ticket gets implemented.
    final DslResolvingContext dslResolvingContext = getDefault(new HashSet<>(extensionModels.values()));

    final String basePackage = clazz.getPackage().toString();
    final ClassLoader pluginClassLoader = new ClassLoader(clazz.getClassLoader()) {

      @Override
      protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (name.startsWith(basePackage)) {
          byte[] classBytes;
          try {
            classBytes =
                toByteArray(this.getClass().getResourceAsStream("/" + name.replaceAll("\\.", "/") + ".class"));
            return this.defineClass(null, classBytes, 0, classBytes.length);
          } catch (Exception e) {
            return super.loadClass(name);
          }
        } else {
          return super.loadClass(name, resolve);
        }
      }
    };

    return loader.loadExtensionModel(pluginClassLoader, dslResolvingContext, params);
  }

  static class ExtensionJsonGeneratorTestUnit {

    final ExtensionModelLoader loader;
    final Class<?> extensionClass;
    final String fileName;

    private ExtensionJsonGeneratorTestUnit(ExtensionModelLoader loader, Class<?> extensionClass, String fileName) {
      this.loader = loader;
      this.extensionClass = extensionClass;
      this.fileName = fileName;
    }

    static ExtensionJsonGeneratorTestUnit newTestUnit(ExtensionModelLoader modelLoader, Class<?> extensionClass,
                                                      String fileName) {
      return new ExtensionJsonGeneratorTestUnit(modelLoader, extensionClass, fileName);
    }

    ExtensionModelLoader getLoader() {
      return loader;
    }

    Class<?> getExtensionClass() {
      return extensionClass;
    }

    String getFileName() {
      return fileName;
    }

    public Object[] toTestParams(BiFunction<Class<?>, ExtensionModelLoader, ExtensionModel> createExtensionModel) {
      final ExtensionModel extensionModel = createExtensionModel.apply(getExtensionClass(), getLoader());
      return new Object[] {extensionModel, getFileName()};
    }
  }
}
