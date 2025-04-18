/*
 * Copyright 2023 Salesforce, Inc. All rights reserved.
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.test.module.extension.streaming;

import static org.mule.functional.junit4.matchers.ThrowableCauseMatcher.hasCause;
import static org.mule.functional.junit4.matchers.ThrowableMessageMatcher.hasMessage;
import static org.mule.runtime.api.util.MuleSystemProperties.FORK_JOIN_COMPLETE_CHILDREN_ON_TIMEOUT_PROPERTY;
import static org.mule.test.allure.AllureConstants.ForkJoinStrategiesFeature.FORK_JOIN_STRATEGIES;
import static org.mule.test.allure.AllureConstants.StreamingFeature.STREAMING;
import static org.mule.test.allure.AllureConstants.StreamingFeature.StreamingStory.BYTES_STREAMING;

import static java.util.Collections.singletonList;

import static org.apache.commons.lang3.RandomStringUtils.insecure;
import static org.hamcrest.CoreMatchers.allOf;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.collection.IsCollectionWithSize.hasSize;
import static org.hamcrest.core.Is.isA;
import static org.hamcrest.number.OrderingComparison.greaterThan;
import static org.junit.Assert.assertThrows;

import org.mule.runtime.api.exception.ComposedErrorException;
import org.mule.runtime.core.api.event.EventContextService;
import org.mule.runtime.core.privileged.exception.MessagingException;
import org.mule.tck.junit4.rule.SystemProperty;
import org.mule.test.module.extension.AbstractExtensionFunctionalTestCase;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import jakarta.inject.Inject;

import org.junit.ClassRule;
import org.junit.Test;

import io.qameta.allure.Description;
import io.qameta.allure.Feature;
import io.qameta.allure.Features;
import io.qameta.allure.Issue;
import io.qameta.allure.Story;

@Features({@Feature(STREAMING), @Feature(FORK_JOIN_STRATEGIES)})
@Story(BYTES_STREAMING)
public class ScatterGatherTimeoutDontCompleteWithBytesStreamingExtensionTestCase extends AbstractExtensionFunctionalTestCase {

  private static final String DATA = insecure().nextAlphabetic(2048);

  @ClassRule
  public static SystemProperty DISABLE_FEATURE = new SystemProperty(FORK_JOIN_COMPLETE_CHILDREN_ON_TIMEOUT_PROPERTY, "false");

  @ClassRule
  public static SystemProperty CONFIG_NAME = new SystemProperty("configName", "drStrange");

  @Inject
  private EventContextService eventContextService;

  @Override
  protected String getConfigFile() {
    return "streaming/scatter-gather-bytes-streaming-extension-config.xml";
  }

  @Test
  @Issue("W-16941297")
  @Description("A Scatter Gather router will time out while an operation is still executing. The operation then finishes and generates a stream which will not be closed because the feature flag is disabled.")
  public void whenScatterGatherTimesOutThenStreamsAreLeaked() throws InterruptedException {
    CountDownLatch sgTimedOutLatch = new CountDownLatch(1);
    CountDownLatch pagingProviderClosedLatch = new CountDownLatch(1);
    MessagingException e = assertThrows(MessagingException.class, () -> flowRunner("scatterGatherWithTimeout")
        .withPayload(singletonList(DATA))
        .withVariable("latch", sgTimedOutLatch)
        .withVariable("providerClosedLatch", pagingProviderClosedLatch)
        .run());

    // Control test that the execution really ended with timeout
    assertThat(e,
               hasCause(allOf(isA(ComposedErrorException.class),
                              hasMessage(containsString("Route 1: java.util.concurrent.TimeoutException: "
                                  + "Timeout while processing route/part: '1'")))));

    // If we are here it means the Scatter Gather has already timed out, so now we allow the operation to proceed
    sgTimedOutLatch.countDown();

    // Check that the paging provider is not closed even after some reasonable time
    assertThat("Paging provider should not have been closed", pagingProviderClosedLatch.await(5, TimeUnit.SECONDS), is(false));

    // Check that some event contexts are still there
    assertThat(eventContextService.getCurrentlyActiveFlowStacks(), hasSize(greaterThan(0)));
  }
}
