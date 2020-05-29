/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.core.internal.metadata;

import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;
import static org.mule.runtime.api.i18n.I18nMessageFactory.createStaticMessage;
import org.mule.runtime.api.exception.MuleRuntimeException;
import org.mule.runtime.api.meta.model.ExtensionModel;
import org.mule.runtime.api.meta.model.construct.ConstructModel;
import org.mule.runtime.api.metadata.DataProviderService;
import org.mule.runtime.api.value.ValueResult;
import org.mule.runtime.app.declaration.api.ComponentElementDeclaration;
import org.mule.runtime.core.api.MuleContext;

import java.util.Map;
import java.util.Optional;

public class MuleDataProviderService implements DataProviderService {

  private final MuleContext muleContext;
  private final Map<String, ExtensionModel> extensionModels;

  public MuleDataProviderService(MuleContext muleContext) {
    this.muleContext = muleContext;
    this.extensionModels = muleContext
        .getExtensionManager()
        .getExtensions()
        .stream()
        .collect(toMap(ExtensionModel::getName, identity()));
  }

  @Override
  public Map<String, ValueResult> discover() {
    return null;
  }

  @Override
  public ValueResult getValues(ComponentElementDeclaration component, String parameterName) {
    return null;
  }



  private Optional<ConstructModel> getConstructModel(String extensionName, String componentName) {
    ExtensionModel extensionModel = this.extensionModels.get(extensionName);
    if (extensionModel == null) {
      throw new MuleRuntimeException(createStaticMessage("Could not find extension model: %s", extensionName));
    }
    return extensionModel.getConstructModel(componentName);
  }
}
