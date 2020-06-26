/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.tooling.api;

import org.mule.api.annotation.NoExtend;
import org.mule.api.annotation.NoImplement;

@NoImplement
@NoExtend
public interface ArtifactAgnosticServiceBuilder<T extends ArtifactAgnosticServiceBuilder, S> {

  /**
   * Adds a dependency needed by the artifact that must be included in order to do connectivity testing.
   * <p>
   * If the dependency is a regular jar file, it will be made available to all extensions since the only possible jar dependency
   * that may be added are specific clients jar for execuring the created service like jdbc drivers or JMS clients.
   *
   * @param groupId         group id of the artifact
   * @param artifactId      artifact id of the artifact
   * @param artifactVersion version of the artifact
   * @param classifier      classifier of the artifact
   * @param type            type of the artifact
   * @return the builder
   **/
  T addDependency(String groupId, String artifactId, String artifactVersion, String classifier, String type);

  /**
   * Creates a {@code S service} with the provided configuration
   *
   * @return the created service
   */
  S build();

}
