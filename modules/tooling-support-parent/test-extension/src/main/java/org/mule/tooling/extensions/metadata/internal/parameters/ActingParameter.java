package org.mule.tooling.extensions.metadata.internal.parameters;

import org.mule.runtime.extension.api.annotation.param.Parameter;

public class ActingParameter {

  @Parameter
  private InnerPojo innerPojo;

  @Parameter
  private String actingParameter1;


  public InnerPojo getInnerPojo() {
    return innerPojo;
  }

  public String getActingParameter1() {
    return actingParameter1;
  }
}
