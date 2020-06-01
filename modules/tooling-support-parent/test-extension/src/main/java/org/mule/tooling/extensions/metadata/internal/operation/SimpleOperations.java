package org.mule.tooling.extensions.metadata.internal.operation;

import org.mule.runtime.extension.api.annotation.param.Config;
import org.mule.runtime.extension.api.annotation.param.Connection;
import org.mule.runtime.extension.api.annotation.values.OfValues;
import org.mule.runtime.extension.api.runtime.operation.Result;
import org.mule.tooling.extensions.metadata.internal.config.SimpleConfiguration;
import org.mule.tooling.extensions.metadata.internal.connection.TstExtensionClient;
import org.mule.tooling.extensions.metadata.internal.value.ActingParameterVP;
import org.mule.tooling.extensions.metadata.internal.value.ConfigLessConnectionLessNoActingParamVP;
import org.mule.tooling.extensions.metadata.internal.value.ConfigLessNoActingParamVP;

public class SimpleOperations {

  public Result<Void, Object> configLessConnectionLessVPOP(@Config SimpleConfiguration configuration,
                                                           @Connection TstExtensionClient client,
                                                           @OfValues(ConfigLessConnectionLessNoActingParamVP.class) String providedParameter) {
    return null;
  }

  public Result<Void, Object> configLessVPOP(@Config SimpleConfiguration configuration,
                                             @Connection TstExtensionClient client,
                                             @OfValues(ConfigLessNoActingParamVP.class) String providedParameter) {
    return null;
  }

  public Result<Void, Object> actingParameterVPOP(@Config SimpleConfiguration configuration,
                                                @Connection TstExtensionClient client,
                                                String actingParameter,
                                                @OfValues(ActingParameterVP.class) String providedParameter) {
    return null;
  }
}
