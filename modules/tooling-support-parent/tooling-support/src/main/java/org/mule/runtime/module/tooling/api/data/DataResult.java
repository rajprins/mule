/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.tooling.api.data;

import org.mule.runtime.api.value.ResolvingFailure;

import java.util.Optional;
import java.util.Set;

//TODO: Discuss this interface
public interface DataResult {

  String getResolverName();

  Set<DataValue> getData();

  Optional<ResolvingFailure> getFailure();

  default boolean isSuccessful() {
    return !getFailure().isPresent();
  }

}
