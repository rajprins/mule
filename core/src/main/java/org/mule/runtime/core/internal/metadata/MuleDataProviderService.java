package org.mule.runtime.core.internal.metadata;

import org.mule.runtime.api.meta.model.ExtensionModel;
import org.mule.runtime.api.meta.model.config.ConfigurationModel;
import org.mule.runtime.api.meta.model.parameter.ParameterizedModel;
import org.mule.runtime.api.meta.model.parameter.ValueProviderModel;
import org.mule.runtime.api.metadata.DataProviderService;
import org.mule.runtime.app.declaration.api.ConnectionElementDeclaration;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MuleDataProviderService implements DataProviderService {

  private Map<String, ExtensionModel> extensionModels = new HashMap<>();


  @Override
  public Map<String, List<String>> getValues(ConnectionElementDeclaration connectionDeclaration) {
    return null;
  }
}
