package org.mule.tooling.extensions.metadata.internal.operation;

import org.mule.runtime.extension.api.annotation.metadata.MetadataKeyId;
import org.mule.runtime.extension.api.annotation.metadata.OutputResolver;
import org.mule.runtime.extension.api.annotation.param.Config;
import org.mule.runtime.extension.api.annotation.param.Connection;
import org.mule.runtime.extension.api.annotation.values.OfValues;
import org.mule.runtime.extension.api.runtime.operation.Result;
import org.mule.tooling.extensions.metadata.internal.config.SimpleConfiguration;
import org.mule.tooling.extensions.metadata.internal.connection.TstExtensionClient;
import org.mule.tooling.extensions.metadata.internal.metadata.ConfigLessConnectionLessMetadataResolver;
import org.mule.tooling.extensions.metadata.internal.metadata.ConfigLessMetadataResolver;
import org.mule.tooling.extensions.metadata.internal.value.ActingParameterVP;
import org.mule.tooling.extensions.metadata.internal.value.ConfigLessConnectionLessNoActingParamVP;
import org.mule.tooling.extensions.metadata.internal.value.ConfigLessNoActingParamVP;

public class SimpleOperations {

  @OutputResolver(output = ConfigLessConnectionLessMetadataResolver.class)
  public Result<Void, Object> configLessConnectionLessOP(@Config SimpleConfiguration configuration,
                                                         @Connection TstExtensionClient client,
                                                         @OfValues(ConfigLessConnectionLessNoActingParamVP.class) String providedParameter,
                                                         @MetadataKeyId(ConfigLessConnectionLessMetadataResolver.class) String metadataKey) {
    return null;
  }

  @OutputResolver(output = ConfigLessMetadataResolver.class)
  public Result<Void, Object> configLessOP(@Config SimpleConfiguration configuration,
                                           @Connection TstExtensionClient client,
                                           @OfValues(ConfigLessNoActingParamVP.class) String providedParameter,
                                           @MetadataKeyId(ConfigLessMetadataResolver.class) String metadataKey) {
    return null;
  }

  public Result<Void, Object> actingParameterOP(@Config SimpleConfiguration configuration,
                                                @Connection TstExtensionClient client,
                                                String actingParameter,
                                                @OfValues(ActingParameterVP.class) String providedParameter) {
    return null;
  }
}
