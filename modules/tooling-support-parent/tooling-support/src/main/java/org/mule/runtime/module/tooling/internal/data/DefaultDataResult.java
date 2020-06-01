/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.tooling.internal.data;

import org.mule.runtime.module.tooling.api.data.DataResult;

public class DefaultDataResult<T> implements DataResult<T> {

  private T data;
  private boolean isSuccessful = true;

  private DefaultDataResult(T data) {
    this.data = data;
  }

  public static <T> DefaultDataResult<T> success(T data) {
    return new DefaultDataResult<>(data);
  }

  public static <T> DefaultDataResult<T> failure() {
    DefaultDataResult<T> dr = new DefaultDataResult<>(null);
    dr.isSuccessful = false;
    return dr;
  }

  @Override
  public T getData() {
    return this.data;
  }

  @Override
  public boolean isSuccessful() {
    return this.isSuccessful;
  }
}
