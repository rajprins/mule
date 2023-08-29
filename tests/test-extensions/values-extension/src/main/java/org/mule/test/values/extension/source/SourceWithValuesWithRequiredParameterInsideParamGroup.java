/*
 * Copyright 2023 Salesforce, Inc. All rights reserved.
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.test.values.extension.source;

import static org.mule.runtime.extension.api.annotation.param.MediaType.TEXT_PLAIN;
import org.mule.runtime.extension.api.annotation.param.MediaType;
import org.mule.runtime.extension.api.annotation.param.ParameterGroup;
import org.mule.test.values.extension.GroupWithValuesParameter;

@MediaType(TEXT_PLAIN)
public class SourceWithValuesWithRequiredParameterInsideParamGroup extends AbstractSource {

  @org.mule.sdk.api.annotation.param.ParameterGroup(name = "ValuesGroup")
  GroupWithValuesParameter optionsParameter;
}
