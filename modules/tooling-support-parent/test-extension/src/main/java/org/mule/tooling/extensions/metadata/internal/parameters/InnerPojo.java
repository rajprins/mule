package org.mule.tooling.extensions.metadata.internal.parameters;

import org.mule.runtime.extension.api.annotation.param.Parameter;

public class InnerPojo {

  @Parameter
  private String innerParam;

  public String getInnerParam() {
    return innerParam;
  }

}
