/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.tooling.api.data;

import org.mule.runtime.api.value.Value;

import java.util.Set;

public interface DataValue {

  /**
   * @return identifier for the current option
   */
  String getId();

  /**
   * @return human readable name to use when displaying the option
   */
  String getDisplayName();

  /**
   * @return the child {@link Value values} that form a composed {@link Value}.
   */
  Set<DataValue> getChilds();

  /**
   * @return the name of the part which this {@link Value} is from
   */
  String getPartName();

}
