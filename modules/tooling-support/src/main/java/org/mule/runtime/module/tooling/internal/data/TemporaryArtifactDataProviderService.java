/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.tooling.internal.data;

import static org.mule.runtime.api.i18n.I18nMessageFactory.createStaticMessage;
import static org.mule.runtime.core.api.util.ClassUtils.instantiateClass;
import org.mule.runtime.api.exception.MuleRuntimeException;
import org.mule.runtime.api.meta.model.ExtensionModel;
import org.mule.runtime.api.meta.model.config.ConfigurationModel;
import org.mule.runtime.api.meta.model.parameter.ParameterizedModel;
import org.mule.runtime.api.meta.model.parameter.ValueProviderModel;
import org.mule.runtime.api.metadata.DataProviderService;
import org.mule.runtime.app.declaration.api.ConnectionElementDeclaration;
import org.mule.runtime.core.api.extension.ExtensionManager;
import org.mule.runtime.deployment.model.api.application.Application;
import org.mule.runtime.module.extension.internal.loader.java.property.ValueProviderFactoryModelProperty;
import org.mule.runtime.module.tooling.internal.AbstractArtifactAgnosticService;
import org.mule.runtime.module.tooling.internal.ApplicationSupplier;

import java.util.List;
import java.util.Map;

import javax.inject.Inject;

public class TemporaryArtifactDataProviderService extends AbstractArtifactAgnosticService implements DataProviderService {

  TemporaryArtifactDataProviderService(ApplicationSupplier applicationSupplier) {
    super(applicationSupplier);
  }

  @Override
  public Map<String, List<String>> getValues(ConnectionElementDeclaration connectionDeclaration) {
    ExtensionModel extensionModel = this.extensionModels.get(connectionDeclaration.getDeclaringExtension());
    connectionDeclaration.getMetadataProperty("connectionProviderName").flatMap(
            cpn -> extensionModel.getConfigurationModels().stream()
                    .map(cm -> cm.getConnectionProviderModel((String) cpn))
                    .findAny()
    ).map(
            cpm ->
    )


    return null;
  }

  private ExtensionManager getExtensionManager(Application application) {
    return application
            .getRegistry()
            .lookupByType(ExtensionManager.class)
            .orElseThrow(() -> new MuleRuntimeException(createStaticMessage("No ExtensionManager present in registry")))
  }

  private void getPossibleValuesFromConfig(ConfigurationModel configurationModel) {
    configurationModel.getOperationModels()
  }



  private void getPossibleValuesFromParameterizedModel(ParameterizedModel parameterizedModel) {
    ValueProviderModel vp = parameterizedModel
            .getAllParameterModels()
            .stream()
            .map(pm -> pm.getValueProviderModel().map(
                    vpm -> {
                      if (vpm.requiresConfiguration() || !vpm.getActingParameters().isEmpty()) {
                        throw new MuleRuntimeException(createStaticMessage("Can't resolve values from valueProvider requiring connection or acting parameter"))
                      }
                      return pm
                              .getModelProperty(ValueProviderFactoryModelProperty.class)
                              .map(vpfmp -> vpfmp.getValueProvider())
                              .map(vpc -> instantiateClass(vpc))
                      return vpm.getProviderName()
                    }
            ))
            .map(vpm -> vpm.)

  @Override
  public Map<String, List<String>> getValues(ConnectionElementDeclaration connectionDeclaration) {
    return withTemporaryApplication(
            a -> {
              a.getRegistry().
            },
            e
    )
  }

  private void getValues(Application application) {
    application.
  }
}
