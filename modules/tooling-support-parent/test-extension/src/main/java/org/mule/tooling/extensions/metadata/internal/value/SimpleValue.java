package org.mule.tooling.extensions.metadata.internal.value;

import static java.util.Collections.emptySet;
import org.mule.runtime.api.value.Value;

import java.util.Set;

public class SimpleValue implements Value {

  private final String id;

  public SimpleValue(String id) {
    this.id = id;
  }

  @Override
  public String getId() {
    return this.id;
  }

  @Override
  public String getDisplayName() {
    return this.id;
  }

  @Override
  public Set<Value> getChilds() {
    return emptySet();
  }

  @Override
  public String getPartName() {
    return "part";
  }
}