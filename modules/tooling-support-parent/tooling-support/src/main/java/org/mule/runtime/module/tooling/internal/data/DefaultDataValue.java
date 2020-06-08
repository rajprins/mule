/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.tooling.internal.data;

import static java.util.Collections.unmodifiableSet;
import static java.util.stream.Collectors.toSet;
import org.mule.runtime.api.metadata.MetadataKey;
import org.mule.runtime.api.value.Value;
import org.mule.runtime.module.tooling.api.data.DataValue;

import java.util.HashSet;
import java.util.Set;

public class DefaultDataValue implements DataValue {

  public static Set<DataValue> fromValues(Set<Value> values) {
    return values.stream().map(DefaultDataValue::fromValue).collect(toSet());
  }

  public static Set<DataValue> fromKeys(Set<MetadataKey> keys) {
    return keys.stream().map(DefaultDataValue::fromKey).collect(toSet());
  }

  public static DataValue fromValue(Value value) {
    return new DefaultDataValue(value.getId(),
                                value.getDisplayName(),
                                fromValues(value.getChilds()),
                                value.getPartName());
  }

  public static DataValue fromKey(MetadataKey key) {
    return new DefaultDataValue(key.getId(),
                                key.getDisplayName(),
                                fromKeys(key.getChilds()),
                                key.getPartName());
  }

  private final String id;
  private final String displayName;
  private final Set<DataValue> children;
  private final String partName;

  private DefaultDataValue(String id, String displayName, Set<DataValue> childs, String partName) {
    this.id = id;
    this.displayName = displayName;
    this.children = unmodifiableSet(new HashSet<>(childs));
    this.partName = partName;
  }

  @Override
  public String getId() {
    return this.id;
  }

  @Override
  public String getDisplayName() {
    return this.displayName;
  }

  @Override
  public Set<DataValue> getChilds() {
    return this.children;
  }

  @Override
  public String getPartName() {
    return this.partName;
  }
}
