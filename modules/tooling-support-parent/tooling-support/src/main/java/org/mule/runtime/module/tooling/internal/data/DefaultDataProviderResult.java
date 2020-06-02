/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.tooling.internal.data;

import static java.util.Optional.ofNullable;
import org.mule.runtime.api.value.ResolvingFailure;
import org.mule.runtime.module.tooling.api.data.DataProviderResult;

import java.util.Optional;

public class DefaultDataProviderResult<T> implements DataProviderResult<T> {

  private final ResolvingFailure resolvingFailure;
  private final T result;

  public static <T> DataProviderResult<T> success(T data) {
    return new DefaultDataProviderResult<>(data);
  }

  public static <T> DataProviderResult<T> failure(ResolvingFailure failure) {
    return new DefaultDataProviderResult<>(failure);
  }

  private DefaultDataProviderResult(T result) {
    this.result = result;
    this.resolvingFailure = null;
  }

  private DefaultDataProviderResult(ResolvingFailure failure) {
    this.resolvingFailure = failure;
    this.result = null;
  }

  @Override
  public T getResult() {
    return this.result;
  }

  @Override
  public Optional<ResolvingFailure> getFailure() {
    return ofNullable(resolvingFailure);
  }
}
