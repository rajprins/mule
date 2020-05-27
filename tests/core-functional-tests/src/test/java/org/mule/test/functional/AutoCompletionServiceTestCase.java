/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.test.functional;

import org.mule.functional.junit4.MuleArtifactFunctionalTestCase;
import org.mule.runtime.module.tooling.api.ToolingService;

import javax.inject.Inject;

import org.junit.Test;

public class AutoCompletionServiceTestCase extends MuleArtifactFunctionalTestCase {

  @Inject
  private ToolingService toolingService;

  @Test
  public void testTest() {
    System.out.println("");
  }

}
