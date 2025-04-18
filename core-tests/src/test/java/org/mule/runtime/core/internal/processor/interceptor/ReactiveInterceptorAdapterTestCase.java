/*
 * Copyright 2023 Salesforce, Inc. All rights reserved.
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.core.internal.processor.interceptor;

import static org.mule.runtime.api.component.AbstractComponent.LOCATION_KEY;
import static org.mule.runtime.api.component.ComponentIdentifier.buildFromStringRepresentation;
import static org.mule.runtime.api.component.TypedComponentIdentifier.builder;
import static org.mule.runtime.api.component.TypedComponentIdentifier.ComponentType.OPERATION;
import static org.mule.runtime.api.i18n.I18nMessageFactory.createStaticMessage;
import static org.mule.runtime.core.api.construct.Flow.builder;
import static org.mule.runtime.core.api.error.Errors.ComponentIdentifiers.Handleable.UNKNOWN;
import static org.mule.runtime.core.api.processor.ReactiveProcessor.ProcessingType.CPU_LITE_ASYNC;
import static org.mule.runtime.core.api.rx.Exceptions.checkedFunction;
import static org.mule.runtime.core.internal.component.ComponentAnnotations.ANNOTATION_PARAMETERS;
import static org.mule.runtime.core.internal.util.rx.Operators.nullSafeMap;
import static org.mule.runtime.core.privileged.processor.MessageProcessors.WITHIN_PROCESS_TO_APPLY;
import static org.mule.runtime.dsl.api.component.config.DefaultComponentLocation.from;
import static org.mule.tck.junit4.matcher.EventMatcher.hasErrorType;
import static org.mule.tck.junit4.matcher.EventMatcher.hasErrorTypeThat;
import static org.mule.tck.junit4.matcher.MessagingExceptionMatcher.withEventThat;
import static org.mule.tck.junit4.matcher.MessagingExceptionMatcher.withFailingComponent;
import static org.mule.tck.util.MuleContextUtils.eventBuilder;

import static java.lang.Thread.currentThread;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static java.util.Optional.empty;
import static java.util.Optional.of;
import static java.util.concurrent.CompletableFuture.supplyAsync;
import static java.util.stream.Collectors.toList;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.collection.IsCollectionWithSize.hasSize;
import static org.hamcrest.collection.IsMapContaining.hasEntry;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsSame.sameInstance;
import static org.junit.internal.matchers.ThrowableCauseMatcher.hasCause;
import static org.junit.rules.ExpectedException.none;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.hamcrest.MockitoHamcrest.argThat;
import static reactor.core.Exceptions.errorCallbackNotImplemented;
import static reactor.core.publisher.Mono.from;
import static reactor.core.scheduler.Schedulers.fromExecutorService;

import org.mule.runtime.api.component.AbstractComponent;
import org.mule.runtime.api.component.Component;
import org.mule.runtime.api.component.TypedComponentIdentifier;
import org.mule.runtime.api.component.location.ComponentLocation;
import org.mule.runtime.api.exception.MuleException;
import org.mule.runtime.api.exception.MuleRuntimeException;
import org.mule.runtime.api.interception.InterceptionAction;
import org.mule.runtime.api.interception.InterceptionEvent;
import org.mule.runtime.api.interception.ProcessorInterceptor;
import org.mule.runtime.api.interception.ProcessorInterceptorFactory;
import org.mule.runtime.api.interception.ProcessorParameterValue;
import org.mule.runtime.api.lifecycle.Disposable;
import org.mule.runtime.api.lifecycle.Initialisable;
import org.mule.runtime.api.lifecycle.InitialisationException;
import org.mule.runtime.api.message.ErrorType;
import org.mule.runtime.api.message.Message;
import org.mule.runtime.api.meta.model.ComponentModel;
import org.mule.runtime.api.scheduler.Scheduler;
import org.mule.runtime.api.scheduler.SchedulerConfig;
import org.mule.runtime.api.util.LazyValue;
import org.mule.runtime.core.api.construct.Flow;
import org.mule.runtime.core.api.event.CoreEvent;
import org.mule.runtime.core.api.expression.ExpressionRuntimeException;
import org.mule.runtime.core.api.processor.Processor;
import org.mule.runtime.core.api.util.func.CheckedConsumer;
import org.mule.runtime.core.internal.event.InternalEvent;
import org.mule.runtime.core.internal.interception.DefaultInterceptionEvent;
import org.mule.runtime.core.internal.interception.ParametersResolverProcessor;
import org.mule.runtime.core.privileged.event.BaseEventContext;
import org.mule.runtime.core.privileged.exception.MessagingException;
import org.mule.runtime.dsl.api.component.config.DefaultComponentLocation;
import org.mule.runtime.dsl.api.component.config.DefaultComponentLocation.DefaultLocationPart;
import org.mule.runtime.extension.api.runtime.operation.ExecutionContext;
import org.mule.tck.junit4.AbstractMuleContextTestCase;
import org.mule.tck.probe.PollingProber;
import org.mule.tck.size.SmallTest;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

import jakarta.inject.Inject;

import javax.xml.namespace.QName;

import com.google.common.collect.ImmutableMap;

import org.reactivestreams.Publisher;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeMatcher;

import org.mockito.InOrder;
import org.mockito.verification.VerificationMode;

import io.qameta.allure.Issue;

import reactor.core.publisher.Mono;

@SmallTest
@RunWith(Parameterized.class)
public class ReactiveInterceptorAdapterTestCase extends AbstractMuleContextTestCase {

  private final boolean useMockInterceptor;
  private final Processor processor;

  @Inject
  private DefaultProcessorInterceptorManager processorInterceptiorManager;
  private Flow flow;

  @Rule
  public ExpectedException expected = none();

  public ReactiveInterceptorAdapterTestCase(boolean useMockInterceptor, Processor processor) {
    this.useMockInterceptor = useMockInterceptor;
    this.processor = spy(processor);
  }

  @Override
  protected boolean doTestClassInjection() {
    return true;
  }

  /*
   * The cases with no mocks exercise the optimized interception path when 'around' is not implemented. This is needed because
   * Mockito mocks override the methods.
   */
  @Parameters(name = "{1}, {0}")
  public static Collection<Object[]> data() {
    return asList(new Object[][] {
        {true, new ProcessorInApp(true)},
        {true, new NonBlockingProcessorInApp()},
        {true, new OperationProcessorInApp()},
        {false, new ProcessorInApp(false)},
        {false, new NonBlockingProcessorInApp()},
        {false, new OperationProcessorInApp()}
    });
  }

  @Before
  public void before() throws MuleException {
    flow = builder("flow", muleContext).processors(processor).build();
    flow.setAnnotations(singletonMap(LOCATION_KEY, from("flow")));
  }

  @After
  public void after() throws MuleException {
    flow.stop();
    flow.dispose();
  }

  private ProcessorInterceptor prepareInterceptor(ProcessorInterceptor interceptor) {
    if (useMockInterceptor) {
      return spy(interceptor);
    } else {
      return interceptor;
    }
  }

  @Test
  public void interceptorApplied() throws Exception {
    ProcessorInterceptor interceptor = prepareInterceptor(new TestProcessorInterceptor("test"));
    startFlowWithInterceptors(interceptor);

    CoreEvent result = process(flow, eventBuilder(muleContext).message(Message.of("")).build());
    assertThat(result.getMessage().getPayload().getValue(), is(""));
    assertThat(result.getError().isPresent(), is(false));

    if (useMockInterceptor) {
      InOrder inOrder = inOrder(processor, interceptor);

      inOrder.verify(interceptor).before(eq(((Component) processor).getLocation()), mapArgWithEntry("param", ""), any());
      inOrder.verify(interceptor).around(eq(((Component) processor).getLocation()), mapArgWithEntry("param", ""), any(),
                                         any());
      inOrder.verify(processor).process(argThat(hasPayloadValue("")));
      inOrder.verify(interceptor).after(eq(((Component) processor).getLocation()), any(), eq(empty()));

      assertThat(((InternalEvent) result).getInternalParameters().entrySet(), hasSize(0));
      verifyParametersResolvedAndDisposed(times(1));
    }
  }

  @Test
  public void interceptorMutatesEventBefore() throws Exception {
    ProcessorInterceptor interceptor = prepareInterceptor(new ProcessorInterceptor() {

      @Override
      public void before(ComponentLocation location, Map<String, ProcessorParameterValue> parameters, InterceptionEvent event) {
        event.message(Message.of(TEST_PAYLOAD));
      }


      // This is done for using mockito spied object in the case of default methods.
      @Override
      public CompletableFuture<InterceptionEvent> around(ComponentLocation location,
                                                         Map<String, ProcessorParameterValue> parameters, InterceptionEvent event,
                                                         InterceptionAction action) {
        return ProcessorInterceptor.super.around(location, parameters, event, action);
      }

      // This is done for using mockito spied object in the case of default methods.
      @Override
      public void after(ComponentLocation location, InterceptionEvent event, Optional<Throwable> thrown) {
        ProcessorInterceptor.super.after(location, event, thrown);
      }
    });
    startFlowWithInterceptors(interceptor);

    CoreEvent result = process(flow, eventBuilder(muleContext).message(Message.of("")).build());
    assertThat(result.getMessage().getPayload().getValue(), is(TEST_PAYLOAD));
    assertThat(result.getError().isPresent(), is(false));

    if (useMockInterceptor) {
      InOrder inOrder = inOrder(processor, interceptor);

      inOrder.verify(interceptor).before(any(), mapArgWithEntry("param", ""), any());
      inOrder.verify(interceptor).around(any(), mapArgWithEntry("param", TEST_PAYLOAD),
                                         argThat(interceptionHasPayloadValue(TEST_PAYLOAD)),
                                         any());
      inOrder.verify(processor).process(argThat(hasPayloadValue(TEST_PAYLOAD)));
      inOrder.verify(interceptor).after(any(), argThat(interceptionHasPayloadValue(TEST_PAYLOAD)), eq(empty()));

      assertThat(((InternalEvent) result).getInternalParameters().entrySet(), hasSize(0));
      verifyParametersResolvedAndDisposed(times(2));
    }
  }

  @Test
  public void interceptorMutatesEventAfter() throws Exception {
    ProcessorInterceptor interceptor = prepareInterceptor(new ProcessorInterceptor() {

      @Override
      public void after(ComponentLocation location, InterceptionEvent event, Optional<Throwable> thrown) {
        event.message(Message.of(TEST_PAYLOAD));
      }

      // This is done for using mockito spied object in the case of default methods.
      @Override
      public CompletableFuture<InterceptionEvent> around(ComponentLocation location,
                                                         Map<String, ProcessorParameterValue> parameters, InterceptionEvent event,
                                                         InterceptionAction action) {
        return ProcessorInterceptor.super.around(location, parameters, event, action);
      }

      // This is done for using mockito spied object in the case of default methods.
      @Override
      public void before(ComponentLocation location, Map<String, ProcessorParameterValue> parameters, InterceptionEvent event) {
        ProcessorInterceptor.super.before(location, parameters, event);
      }
    });
    startFlowWithInterceptors(interceptor);

    CoreEvent result = process(flow, eventBuilder(muleContext).message(Message.of("")).build());
    assertThat(result.getMessage().getPayload().getValue(), is(TEST_PAYLOAD));
    assertThat(result.getError().isPresent(), is(false));

    if (useMockInterceptor) {
      InOrder inOrder = inOrder(processor, interceptor);

      inOrder.verify(interceptor).before(any(), mapArgWithEntry("param", ""), any());
      inOrder.verify(interceptor).around(any(), mapArgWithEntry("param", ""), any(), any());
      inOrder.verify(processor).process(argThat(hasPayloadValue("")));
      inOrder.verify(interceptor).after(any(), any(), eq(empty()));

      assertThat(((InternalEvent) result).getInternalParameters().entrySet(), hasSize(0));
      verifyParametersResolvedAndDisposed(times(1));
    }
  }

  @Test
  public void interceptorMutatesEventAroundBeforeProceed() throws Exception {
    ProcessorInterceptor interceptor = prepareInterceptor(new ProcessorInterceptor() {

      @Override
      public CompletableFuture<InterceptionEvent> around(ComponentLocation location,
                                                         Map<String, ProcessorParameterValue> parameters,
                                                         InterceptionEvent event, InterceptionAction action) {
        event.message(Message.of(TEST_PAYLOAD));
        return action.proceed();
      }

      // This is done for using mockito spied object in the case of default methods.
      @Override
      public void before(ComponentLocation location, Map<String, ProcessorParameterValue> parameters, InterceptionEvent event) {
        ProcessorInterceptor.super.before(location, parameters, event);
      }

      // This is done for using mockito spied object in the case of default methods.
      @Override
      public void after(ComponentLocation location, InterceptionEvent event, Optional<Throwable> thrown) {
        ProcessorInterceptor.super.after(location, event, thrown);
      }
    });
    startFlowWithInterceptors(interceptor);

    CoreEvent result = process(flow, eventBuilder(muleContext).message(Message.of("")).build());
    assertThat(result.getMessage().getPayload().getValue(), is(TEST_PAYLOAD));
    assertThat(result.getError().isPresent(), is(false));

    if (useMockInterceptor) {
      InOrder inOrder = inOrder(processor, interceptor);

      inOrder.verify(interceptor).before(any(), mapArgWithEntry("param", ""), any());
      inOrder.verify(interceptor).around(any(), mapArgWithEntry("param", ""), any(), any());
      inOrder.verify(processor).process(argThat(hasPayloadValue(TEST_PAYLOAD)));
      inOrder.verify(interceptor).after(any(), argThat(interceptionHasPayloadValue(TEST_PAYLOAD)), eq(empty()));

      assertThat(((InternalEvent) result).getInternalParameters().entrySet(), hasSize(0));
      verifyParametersResolvedAndDisposed(times(1));
    }
  }

  @Test
  public void interceptorMutatesEventAroundAfterProceed() throws Exception {
    ProcessorInterceptor interceptor = prepareInterceptor(new ProcessorInterceptor() {

      @Override
      public CompletableFuture<InterceptionEvent> around(ComponentLocation location,
                                                         Map<String, ProcessorParameterValue> parameters,
                                                         InterceptionEvent event, InterceptionAction action) {
        return action.proceed().thenCompose(result -> supplyAsync(() -> {
          result.message(Message.of(TEST_PAYLOAD));
          return result;
        }));
      }

      // This is done for using mockito spied object in the case of default methods.
      @Override
      public void before(ComponentLocation location, Map<String, ProcessorParameterValue> parameters, InterceptionEvent event) {
        ProcessorInterceptor.super.before(location, parameters, event);
      }

      // This is done for using mockito spied object in the case of default methods.
      @Override
      public void after(ComponentLocation location, InterceptionEvent event, Optional<Throwable> thrown) {
        ProcessorInterceptor.super.after(location, event, thrown);
      }
    });
    startFlowWithInterceptors(interceptor);

    CoreEvent result = process(flow, eventBuilder(muleContext).message(Message.of("")).build());
    assertThat(result.getMessage().getPayload().getValue(), is(TEST_PAYLOAD));
    assertThat(result.getError().isPresent(), is(false));

    if (useMockInterceptor) {
      InOrder inOrder = inOrder(processor, interceptor);

      inOrder.verify(interceptor).before(any(), mapArgWithEntry("param", ""), any());
      inOrder.verify(interceptor).around(any(), mapArgWithEntry("param", ""), any(), any());
      inOrder.verify(processor).process(argThat(hasPayloadValue("")));
      inOrder.verify(interceptor).after(any(), argThat(interceptionHasPayloadValue(TEST_PAYLOAD)), eq(empty()));

      assertThat(((InternalEvent) result).getInternalParameters().entrySet(), hasSize(0));
      verifyParametersResolvedAndDisposed(times(1));
    }
  }

  @Test
  public void interceptorMutatesEventAroundBeforeSkip() throws Exception {
    ProcessorInterceptor interceptor = prepareInterceptor(new ProcessorInterceptor() {

      @Override
      public CompletableFuture<InterceptionEvent> around(ComponentLocation location,
                                                         Map<String, ProcessorParameterValue> parameters,
                                                         InterceptionEvent event, InterceptionAction action) {
        event.message(Message.of(TEST_PAYLOAD));
        return action.skip();
      }

      // This is done for using mockito spied object in the case of default methods.
      @Override
      public void before(ComponentLocation location, Map<String, ProcessorParameterValue> parameters, InterceptionEvent event) {
        ProcessorInterceptor.super.before(location, parameters, event);
      }

      // This is done for using mockito spied object in the case of default methods.
      @Override
      public void after(ComponentLocation location, InterceptionEvent event, Optional<Throwable> thrown) {
        ProcessorInterceptor.super.after(location, event, thrown);
      }
    });
    startFlowWithInterceptors(interceptor);

    CoreEvent result = process(flow, eventBuilder(muleContext).message(Message.of("")).build());
    assertThat(result.getMessage().getPayload().getValue(), is(TEST_PAYLOAD));
    assertThat(result.getError().isPresent(), is(false));

    if (useMockInterceptor) {
      InOrder inOrder = inOrder(processor, interceptor);

      inOrder.verify(interceptor).before(any(), mapArgWithEntry("param", ""), any());
      inOrder.verify(interceptor).around(any(), mapArgWithEntry("param", ""), any(), any());
      inOrder.verify(processor, never()).process(any());
      inOrder.verify(interceptor).after(any(), argThat(interceptionHasPayloadValue(TEST_PAYLOAD)), eq(empty()));

      assertThat(((InternalEvent) result).getInternalParameters().entrySet(), hasSize(0));
      verifyParametersResolvedAndDisposed(times(1));
    }
  }

  @Test
  public void interceptorMutatesEventAroundAfterSkip() throws Exception {
    ProcessorInterceptor interceptor = prepareInterceptor(new ProcessorInterceptor() {

      @Override
      public CompletableFuture<InterceptionEvent> around(ComponentLocation location,
                                                         Map<String, ProcessorParameterValue> parameters,
                                                         InterceptionEvent event, InterceptionAction action) {
        return action.skip().thenCompose(result -> supplyAsync(() -> {
          result.message(Message.of(TEST_PAYLOAD));
          return result;
        }));
      }

      // This is done for using mockito spied object in the case of default methods.
      @Override
      public void before(ComponentLocation location, Map<String, ProcessorParameterValue> parameters, InterceptionEvent event) {
        ProcessorInterceptor.super.before(location, parameters, event);
      }

      // This is done for using mockito spied object in the case of default methods.
      @Override
      public void after(ComponentLocation location, InterceptionEvent event, Optional<Throwable> thrown) {
        ProcessorInterceptor.super.after(location, event, thrown);
      }
    });
    startFlowWithInterceptors(interceptor);

    CoreEvent result = process(flow, eventBuilder(muleContext).message(Message.of("")).build());
    assertThat(result.getMessage().getPayload().getValue(), is(TEST_PAYLOAD));
    assertThat(result.getError().isPresent(), is(false));

    if (useMockInterceptor) {
      InOrder inOrder = inOrder(processor, interceptor);

      inOrder.verify(interceptor).before(any(), mapArgWithEntry("param", ""), any());
      inOrder.verify(interceptor).around(any(), mapArgWithEntry("param", ""), any(), any());
      inOrder.verify(processor, never()).process(any());
      inOrder.verify(interceptor).after(any(), argThat(interceptionHasPayloadValue(TEST_PAYLOAD)), eq(empty()));

      assertThat(((InternalEvent) result).getInternalParameters().entrySet(), hasSize(0));
      verifyParametersResolvedAndDisposed(times(1));
    }
  }

  @Test
  @Issue("MULE-18549")
  public void interceptorHandleMessagingExceptionWithFallingComponent() throws Exception {
    Component mockedComponent = mock(Component.class);

    startFlowWithInterceptors(interceptorThatFailsWith(mockedComponent));

    expected.expect(MessagingException.class);
    expected.expect(withFailingComponent(is(notNullValue(Component.class))));
    expected.expect(withFailingComponent(is(sameInstance(mockedComponent))));
    process(flow, eventBuilder(muleContext).message(Message.of("")).build());
  }

  @Test
  @Issue("MULE-18549")
  public void interceptorHandleMessagingExceptionWithoNullFallingComponent() throws Exception {
    startFlowWithInterceptors(interceptorThatFailsWith(null));

    expected.expect(MessagingException.class);
    expected.expect(withFailingComponent(is(notNullValue(Component.class))));
    expected.expect(withFailingComponent(is(sameInstance((Component) processor))));
    process(flow, eventBuilder(muleContext).message(Message.of("")).build());
  }

  @Test
  public void interceptorMutatesEventAroundAfterFailWithErrorType() throws Exception {
    ErrorType errorTypeMock = mock(ErrorType.class);
    when(errorTypeMock.getIdentifier()).thenReturn("ID");
    when(errorTypeMock.getNamespace()).thenReturn("NS");
    ProcessorInterceptor interceptor = prepareInterceptor(new ProcessorInterceptor() {

      @Override
      public CompletableFuture<InterceptionEvent> around(ComponentLocation location,
                                                         Map<String, ProcessorParameterValue> parameters,
                                                         InterceptionEvent event, InterceptionAction action) {
        event.message(Message.of(TEST_PAYLOAD));
        return action.fail(errorTypeMock);
      }

      // This is done for using mockito spied object in the case of default methods.
      @Override
      public void before(ComponentLocation location, Map<String, ProcessorParameterValue> parameters, InterceptionEvent event) {
        ProcessorInterceptor.super.before(location, parameters, event);
      }

      // This is done for using mockito spied object in the case of default methods.
      @Override
      public void after(ComponentLocation location, InterceptionEvent event, Optional<Throwable> thrown) {
        ProcessorInterceptor.super.after(location, event, thrown);
      }
    });
    startFlowWithInterceptors(interceptor);

    expected.expect(MessagingException.class);
    expected.expect(withEventThat(hasErrorTypeThat(sameInstance(errorTypeMock))));
    expected.expectCause(instanceOf(InterceptionException.class));
    try {
      process(flow, eventBuilder(muleContext).message(Message.of("")).build());
    } finally {
      if (useMockInterceptor) {
        InOrder inOrder = inOrder(processor, interceptor);

        inOrder.verify(interceptor).before(any(), mapArgWithEntry("param", ""), any());
        inOrder.verify(interceptor).around(any(), mapArgWithEntry("param", ""), any(), any());
        inOrder.verify(processor, never()).process(any());
        inOrder.verify(interceptor).after(any(), argThat(interceptionHasPayloadValue(TEST_PAYLOAD)),
                                          argThat(not(empty())));

        verifyParametersResolvedAndDisposed(times(1));
      }
    }
  }

  @Test
  public void interceptorMutatesEventAroundAfterFailWithErrorTypeAndMessage() throws Exception {
    final String FAIL = "Some message";
    ErrorType errorTypeMock = mock(ErrorType.class);
    when(errorTypeMock.getIdentifier()).thenReturn("ID");
    when(errorTypeMock.getNamespace()).thenReturn("NS");
    ProcessorInterceptor interceptor = prepareInterceptor(new ProcessorInterceptor() {

      @Override
      public CompletableFuture<InterceptionEvent> around(ComponentLocation location,
                                                         Map<String, ProcessorParameterValue> parameters,
                                                         InterceptionEvent event, InterceptionAction action) {
        event.message(Message.of(TEST_PAYLOAD));
        return action.fail(errorTypeMock, FAIL);
      }

      // This is done for using mockito spied object in the case of default methods.
      @Override
      public void before(ComponentLocation location, Map<String, ProcessorParameterValue> parameters, InterceptionEvent event) {
        ProcessorInterceptor.super.before(location, parameters, event);
      }

      // This is done for using mockito spied object in the case of default methods.
      @Override
      public void after(ComponentLocation location, InterceptionEvent event, Optional<Throwable> thrown) {
        ProcessorInterceptor.super.after(location, event, thrown);
      }
    });
    startFlowWithInterceptors(interceptor);

    expected.expect(MessagingException.class);
    expected.expect(withEventThat(hasErrorTypeThat(sameInstance(errorTypeMock))));
    expected.expectCause(instanceOf(InterceptionException.class));
    expected.expectMessage(FAIL);
    try {
      process(flow, eventBuilder(muleContext).message(Message.of("")).build());
    } finally {
      if (useMockInterceptor) {
        InOrder inOrder = inOrder(processor, interceptor);

        inOrder.verify(interceptor).before(any(), mapArgWithEntry("param", ""), any());
        inOrder.verify(interceptor).around(any(), mapArgWithEntry("param", ""), any(), any());
        inOrder.verify(processor, never()).process(any());
        inOrder.verify(interceptor).after(any(), argThat(interceptionHasPayloadValue(TEST_PAYLOAD)),
                                          argThat(not(empty())));

        verifyParametersResolvedAndDisposed(times(1));
      }
    }
  }

  @Test
  public void interceptorMutatesEventAroundAfterFailWithCause() throws Exception {
    Throwable cause = new RuntimeException("");
    ProcessorInterceptor interceptor = prepareInterceptor(new ProcessorInterceptor() {

      @Override
      public CompletableFuture<InterceptionEvent> around(ComponentLocation location,
                                                         Map<String, ProcessorParameterValue> parameters,
                                                         InterceptionEvent event, InterceptionAction action) {
        event.message(Message.of(TEST_PAYLOAD));
        return action.fail(cause);
      }

      // This is done for using mockito spied object in the case of default methods.
      @Override
      public void before(ComponentLocation location, Map<String, ProcessorParameterValue> parameters, InterceptionEvent event) {
        ProcessorInterceptor.super.before(location, parameters, event);
      }

      // This is done for using mockito spied object in the case of default methods.
      @Override
      public void after(ComponentLocation location, InterceptionEvent event, Optional<Throwable> thrown) {
        ProcessorInterceptor.super.after(location, event, thrown);
      }
    });
    startFlowWithInterceptors(interceptor);

    expected.expectCause(sameInstance(cause));
    try {
      process(flow, eventBuilder(muleContext).message(Message.of("")).build());
    } finally {
      if (useMockInterceptor) {
        InOrder inOrder = inOrder(processor, interceptor);
        inOrder.verify(interceptor).before(any(), mapArgWithEntry("param", ""), any());
        inOrder.verify(interceptor).around(any(), mapArgWithEntry("param", ""), any(), any());
        inOrder.verify(processor, never()).process(any());
        inOrder.verify(interceptor).after(any(), argThat(interceptionHasPayloadValue(TEST_PAYLOAD)), eq(of(cause)));

        verifyParametersResolvedAndDisposed(times(1));
      }
    }
  }

  @Test
  public void interceptorThrowsExceptionBefore() throws Exception {
    RuntimeException expectedException = new RuntimeException("Some Error");
    ProcessorInterceptor interceptor = prepareInterceptor(new ProcessorInterceptor() {

      @Override
      public void before(ComponentLocation location, Map<String, ProcessorParameterValue> parameters, InterceptionEvent event) {
        throw expectedException;
      }
    });
    startFlowWithInterceptors(interceptor);

    expected.expectCause(sameInstance(expectedException));
    try {
      process(flow, eventBuilder(muleContext).message(Message.of("")).build());
    } finally {
      if (useMockInterceptor) {
        InOrder inOrder = inOrder(processor, interceptor);

        inOrder.verify(interceptor).before(any(), any(), any());
        inOrder.verify(interceptor, never()).around(any(), any(), any(), any());
        inOrder.verify(processor, never()).process(any());
        inOrder.verify(interceptor).after(any(), any(), eq(of(expectedException)));

        verifyParametersResolvedAndDisposed(times(1));
      }
    }
  }

  @Test
  public void interceptorThrowsExceptionAfter() throws Exception {
    RuntimeException expectedException = new RuntimeException("Some Error");
    ProcessorInterceptor interceptor = prepareInterceptor(new ProcessorInterceptor() {

      // This is done for using mockito spied object in the case of default methods.
      @Override
      public void after(ComponentLocation location, InterceptionEvent event, Optional<Throwable> thrown) {
        throw expectedException;
      }

      // This is done for using mockito spied object in the case of default methods.
      @Override
      public CompletableFuture<InterceptionEvent> around(ComponentLocation location,
                                                         Map<String, ProcessorParameterValue> parameters, InterceptionEvent event,
                                                         InterceptionAction action) {
        return ProcessorInterceptor.super.around(location, parameters, event, action);
      }

      // This is done for using mockito spied object in the case of default methods.
      @Override
      public void before(ComponentLocation location, Map<String, ProcessorParameterValue> parameters, InterceptionEvent event) {
        ProcessorInterceptor.super.before(location, parameters, event);
      }
    });
    startFlowWithInterceptors(interceptor);

    expected.expectCause(sameInstance(expectedException));
    try {
      process(flow, eventBuilder(muleContext).message(Message.of("")).build());
    } finally {
      if (useMockInterceptor) {
        InOrder inOrder = inOrder(processor, interceptor);

        inOrder.verify(interceptor).before(any(), any(), any());
        inOrder.verify(interceptor).around(any(), any(), any(), any());
        inOrder.verify(processor).process(argThat(hasPayloadValue("")));
        inOrder.verify(interceptor).after(any(), any(), eq(empty()));

        verifyParametersResolvedAndDisposed(times(1));
      }
    }
  }

  @Test
  public void interceptorThrowsExceptionAround() throws Exception {
    RuntimeException expectedException = new RuntimeException("Some Error");
    ProcessorInterceptor interceptor = prepareInterceptor(new ProcessorInterceptor() {

      @Override
      public CompletableFuture<InterceptionEvent> around(ComponentLocation location,
                                                         Map<String, ProcessorParameterValue> parameters,
                                                         InterceptionEvent event, InterceptionAction action) {
        throw expectedException;
      }

      // This is done for using mockito spied object in the case of default methods.
      @Override
      public void before(ComponentLocation location, Map<String, ProcessorParameterValue> parameters, InterceptionEvent event) {
        ProcessorInterceptor.super.before(location, parameters, event);
      }

      // This is done for using mockito spied object in the case of default methods.
      @Override
      public void after(ComponentLocation location, InterceptionEvent event, Optional<Throwable> thrown) {
        ProcessorInterceptor.super.after(location, event, thrown);
      }
    });
    startFlowWithInterceptors(interceptor);

    expected.expectCause(sameInstance(expectedException));
    try {
      process(flow, eventBuilder(muleContext).message(Message.of("")).build());
    } finally {
      if (useMockInterceptor) {
        InOrder inOrder = inOrder(processor, interceptor);

        inOrder.verify(interceptor).before(any(), any(), any());
        inOrder.verify(interceptor).around(any(), any(), any(), any());
        inOrder.verify(processor, never()).process(any());
        inOrder.verify(interceptor).after(any(), any(), eq(of(expectedException)));

        verifyParametersResolvedAndDisposed(times(1));
      }
    }
  }

  @Test
  public void interceptorThrowsExceptionAroundAfterProceed() throws Exception {
    RuntimeException expectedException = new RuntimeException("Some Error");
    ProcessorInterceptor interceptor = prepareInterceptor(new ProcessorInterceptor() {

      @Override
      public CompletableFuture<InterceptionEvent> around(ComponentLocation location,
                                                         Map<String, ProcessorParameterValue> parameters,
                                                         InterceptionEvent event, InterceptionAction action) {
        return action.proceed().thenCompose(result -> {
          final CompletableFuture<InterceptionEvent> completableFuture = new CompletableFuture<>();
          completableFuture.completeExceptionally(expectedException);
          return completableFuture;
        });
      }

      // This is done for using mockito spied object in the case of default methods.
      @Override
      public void before(ComponentLocation location, Map<String, ProcessorParameterValue> parameters, InterceptionEvent event) {
        ProcessorInterceptor.super.before(location, parameters, event);
      }

      // This is done for using mockito spied object in the case of default methods.
      @Override
      public void after(ComponentLocation location, InterceptionEvent event, Optional<Throwable> thrown) {
        ProcessorInterceptor.super.after(location, event, thrown);
      }
    });
    startFlowWithInterceptors(interceptor);

    expected.expectCause(sameInstance(expectedException));
    try {
      process(flow, eventBuilder(muleContext).message(Message.of("")).build());
    } finally {
      if (useMockInterceptor) {
        InOrder inOrder = inOrder(processor, interceptor);

        inOrder.verify(interceptor).before(any(), any(), any());
        inOrder.verify(interceptor).around(any(), any(), any(), any());
        inOrder.verify(processor).process(any());
        inOrder.verify(interceptor).after(any(), any(), eq(of(expectedException)));

        verifyParametersResolvedAndDisposed(times(1));
      }
    }
  }

  @Test
  public void interceptorThrowsExceptionAroundAfterProceedInCallback() throws Exception {
    RuntimeException expectedException = new RuntimeException("Some Error");
    ProcessorInterceptor interceptor = prepareInterceptor(new ProcessorInterceptor() {

      @Override
      public CompletableFuture<InterceptionEvent> around(ComponentLocation location,
                                                         Map<String, ProcessorParameterValue> parameters,
                                                         InterceptionEvent event, InterceptionAction action) {
        return action.proceed().thenCompose(result -> supplyAsync(() -> {
          throw expectedException;
        }));
      }

      // This is done for using mockito spied object in the case of default methods.
      @Override
      public void before(ComponentLocation location, Map<String, ProcessorParameterValue> parameters, InterceptionEvent event) {
        ProcessorInterceptor.super.before(location, parameters, event);
      }

      // This is done for using mockito spied object in the case of default methods.
      @Override
      public void after(ComponentLocation location, InterceptionEvent event, Optional<Throwable> thrown) {
        ProcessorInterceptor.super.after(location, event, thrown);
      }
    });
    startFlowWithInterceptors(interceptor);

    expected.expect(MessagingException.class);
    expected.expect(withEventThat(hasErrorType(UNKNOWN.getNamespace(), UNKNOWN.getName())));
    expected.expectCause(sameInstance(expectedException));
    try {
      process(flow, eventBuilder(muleContext).message(Message.of("")).build());
    } finally {
      if (useMockInterceptor) {
        InOrder inOrder = inOrder(processor, interceptor);

        inOrder.verify(interceptor).before(any(), any(), any());
        inOrder.verify(interceptor).around(any(), any(), any(), any());
        inOrder.verify(processor).process(any());
        inOrder.verify(interceptor).after(any(), any(), eq(of(expectedException)));

        verifyParametersResolvedAndDisposed(times(1));
      }
    }
  }

  @Test
  public void interceptorThrowsExceptionAroundAfterProceedInCallbackChained() throws Exception {
    RuntimeException expectedException = new RuntimeException("Some Error");
    ProcessorInterceptor interceptor = prepareInterceptor(new ProcessorInterceptor() {

      @Override
      public CompletableFuture<InterceptionEvent> around(ComponentLocation location,
                                                         Map<String, ProcessorParameterValue> parameters,
                                                         InterceptionEvent event, InterceptionAction action) {
        return action.proceed().thenApplyAsync(e -> {
          throw expectedException;
        });
      }

      // This is done for using mockito spied object in the case of default methods.
      @Override
      public void before(ComponentLocation location, Map<String, ProcessorParameterValue> parameters, InterceptionEvent event) {
        ProcessorInterceptor.super.before(location, parameters, event);
      }

      // This is done for using mockito spied object in the case of default methods.
      @Override
      public void after(ComponentLocation location, InterceptionEvent event, Optional<Throwable> thrown) {
        ProcessorInterceptor.super.after(location, event, thrown);
      }
    });
    startFlowWithInterceptors(interceptor);

    expected.expect(MessagingException.class);
    expected.expect(withEventThat(hasErrorType(UNKNOWN.getNamespace(), UNKNOWN.getName())));
    expected.expectCause(sameInstance(expectedException));
    try {
      process(flow, eventBuilder(muleContext).message(Message.of("")).build());
    } finally {
      if (useMockInterceptor) {
        InOrder inOrder = inOrder(processor, interceptor);

        inOrder.verify(interceptor).before(any(), any(), any());
        inOrder.verify(interceptor).around(any(), any(), any(), any());
        inOrder.verify(processor).process(any());
        inOrder.verify(interceptor).after(any(), any(), eq(of(expectedException)));

        verifyParametersResolvedAndDisposed(times(1));
      }
    }
  }

  @Test
  public void interceptorThrowsExceptionAroundAfterSkipInCallback() throws Exception {
    RuntimeException expectedException = new RuntimeException("Some Error");
    ProcessorInterceptor interceptor = prepareInterceptor(new ProcessorInterceptor() {

      @Override
      public CompletableFuture<InterceptionEvent> around(ComponentLocation location,
                                                         Map<String, ProcessorParameterValue> parameters,
                                                         InterceptionEvent event, InterceptionAction action) {
        return action.skip().thenCompose(result -> supplyAsync(() -> {
          throw expectedException;
        }));
      }

      // This is done for using mockito spied object in the case of default methods.
      @Override
      public void before(ComponentLocation location, Map<String, ProcessorParameterValue> parameters, InterceptionEvent event) {
        ProcessorInterceptor.super.before(location, parameters, event);
      }

      // This is done for using mockito spied object in the case of default methods.
      @Override
      public void after(ComponentLocation location, InterceptionEvent event, Optional<Throwable> thrown) {
        ProcessorInterceptor.super.after(location, event, thrown);
      }
    });
    startFlowWithInterceptors(interceptor);

    expected.expect(MessagingException.class);
    expected.expect(withEventThat(hasErrorType(UNKNOWN.getNamespace(), UNKNOWN.getName())));
    expected.expectCause(sameInstance(expectedException));
    try {
      process(flow, eventBuilder(muleContext).message(Message.of("")).build());
    } finally {
      if (useMockInterceptor) {
        InOrder inOrder = inOrder(processor, interceptor);

        inOrder.verify(interceptor).before(any(), any(), any());
        inOrder.verify(interceptor).around(any(), any(), any(), any());
        inOrder.verify(processor, never()).process(any());
        inOrder.verify(interceptor).after(any(), any(), eq(of(expectedException)));

        verifyParametersResolvedAndDisposed(times(1));
      }
    }
  }

  @Test
  public void interceptorThrowsExceptionAroundAfterSkipInCallbackChained() throws Exception {
    RuntimeException expectedException = new RuntimeException("Some Error");
    ProcessorInterceptor interceptor = prepareInterceptor(new ProcessorInterceptor() {

      @Override
      public CompletableFuture<InterceptionEvent> around(ComponentLocation location,
                                                         Map<String, ProcessorParameterValue> parameters,
                                                         InterceptionEvent event, InterceptionAction action) {
        return action.skip().thenApplyAsync(e -> {
          throw expectedException;
        });
      }

      // This is done for using mockito spied object in the case of default methods.
      @Override
      public void before(ComponentLocation location, Map<String, ProcessorParameterValue> parameters, InterceptionEvent event) {
        ProcessorInterceptor.super.before(location, parameters, event);
      }

      // This is done for using mockito spied object in the case of default methods.
      @Override
      public void after(ComponentLocation location, InterceptionEvent event, Optional<Throwable> thrown) {
        ProcessorInterceptor.super.after(location, event, thrown);
      }
    });
    startFlowWithInterceptors(interceptor);

    expected.expect(MessagingException.class);
    expected.expect(withEventThat(hasErrorType(UNKNOWN.getNamespace(), UNKNOWN.getName())));
    expected.expectCause(sameInstance(expectedException));
    try {
      process(flow, eventBuilder(muleContext).message(Message.of("")).build());
    } finally {
      if (useMockInterceptor) {
        InOrder inOrder = inOrder(processor, interceptor);

        inOrder.verify(interceptor).before(any(), any(), any());
        inOrder.verify(interceptor).around(any(), any(), any(), any());
        inOrder.verify(processor, never()).process(any());
        inOrder.verify(interceptor).after(any(), any(), eq(of(expectedException)));

        verifyParametersResolvedAndDisposed(times(1));
      }
    }
  }

  @Test
  public void interceptedThrowsException() throws Exception {
    RuntimeException expectedException = new RuntimeException("Some Error");
    ProcessorInterceptor interceptor = prepareInterceptor(new ProcessorInterceptor() {

      @Override
      public void before(ComponentLocation location,
                         Map<String, ProcessorParameterValue> parameters,
                         InterceptionEvent event) {
        // If we don't include either a before or after method override, then the interceptor is not applied and the
        // test is not as useful
        ProcessorInterceptor.super.before(location, parameters, event);
      }

      // This is done for using mockito spied object in the case of default methods.
      @Override
      public CompletableFuture<InterceptionEvent> around(ComponentLocation location,
                                                         Map<String, ProcessorParameterValue> parameters, InterceptionEvent event,
                                                         InterceptionAction action) {
        return ProcessorInterceptor.super.around(location, parameters, event, action);
      }

      // This is done for using mockito spied object in the case of default methods.
      @Override
      public void after(ComponentLocation location, InterceptionEvent event, Optional<Throwable> thrown) {
        ProcessorInterceptor.super.after(location, event, thrown);
      }
    });
    startFlowWithInterceptors(interceptor);

    when(processor.process(any())).thenThrow(expectedException);

    expected.expect(MessagingException.class);
    expected.expect(withEventThat(hasErrorType(UNKNOWN.getNamespace(), UNKNOWN.getName())));
    expected.expectCause(sameInstance(expectedException));
    try {
      process(flow, eventBuilder(muleContext).message(Message.of("")).build());
    } finally {
      if (useMockInterceptor) {
        InOrder inOrder = inOrder(processor, interceptor);

        inOrder.verify(interceptor).before(any(), any(), any());
        inOrder.verify(interceptor).around(any(), any(), any(), any());
        inOrder.verify(processor).process(any());
        inOrder.verify(interceptor).after(any(), any(), eq(of(expectedException)));

        verifyParametersResolvedAndDisposed(times(1));
      }
    }
  }

  @Test
  @Issue("MULE-19593")
  public void interceptedThrowsErrorCallbackNotImplemented() throws Exception {
    UnsupportedOperationException expectedException = errorCallbackNotImplemented(new RuntimeException("Some Error"));
    ProcessorInterceptor interceptor = prepareInterceptor(new ProcessorInterceptor() {

      @Override
      public void before(ComponentLocation location,
                         Map<String, ProcessorParameterValue> parameters,
                         InterceptionEvent event) {
        // If we don't include either a before or after method override, then the interceptor is not applied and the
        // test is not as useful
        ProcessorInterceptor.super.before(location, parameters, event);
      }

      // This is done for using mockito spied object in the case of default methods.
      @Override
      public void after(ComponentLocation location, InterceptionEvent event, Optional<Throwable> thrown) {
        ProcessorInterceptor.super.after(location, event, thrown);
      }

      // This is done for using mockito spied object in the case of default methods.
      @Override
      public CompletableFuture<InterceptionEvent> around(ComponentLocation location,
                                                         Map<String, ProcessorParameterValue> parameters, InterceptionEvent event,
                                                         InterceptionAction action) {
        return ProcessorInterceptor.super.around(location, parameters, event, action);
      }
    });
    startFlowWithInterceptors(interceptor);

    when(processor.process(any())).thenThrow(expectedException);

    expected.expect(MessagingException.class);
    expected.expect(withEventThat(hasErrorType(UNKNOWN.getNamespace(), UNKNOWN.getName())));
    expected.expectCause(instanceOf(MuleRuntimeException.class));
    expected.expectCause(hasCause(sameInstance(expectedException)));
    try {
      process(flow, eventBuilder(muleContext).message(Message.of("")).build());
    } finally {
      if (useMockInterceptor) {
        InOrder inOrder = inOrder(processor, interceptor);

        inOrder.verify(interceptor).before(any(), any(), any());
        inOrder.verify(interceptor).around(any(), any(), any(), any());
        inOrder.verify(processor).process(any());
        inOrder.verify(interceptor).after(any(),
                                          any(),
                                          argThat(optionalWithValue(allOf(instanceOf(RuntimeException.class),
                                                                          hasCause(sameInstance(expectedException))))));

        verifyParametersResolvedAndDisposed(times(1));
      }
    }
  }

  @Test
  public void interceptorSkipsProcessor() throws Exception {
    ProcessorInterceptor interceptor = prepareInterceptor(new ProcessorInterceptor() {

      @Override
      public CompletableFuture<InterceptionEvent> around(ComponentLocation location,
                                                         Map<String, ProcessorParameterValue> parameters,
                                                         InterceptionEvent event, InterceptionAction action) {
        return action.skip();
      }

      // This is done for using mockito spied object in the case of default methods.
      @Override
      public void before(ComponentLocation location, Map<String, ProcessorParameterValue> parameters, InterceptionEvent event) {
        ProcessorInterceptor.super.before(location, parameters, event);
      }

      // This is done for using mockito spied object in the case of default methods.
      @Override
      public void after(ComponentLocation location, InterceptionEvent event, Optional<Throwable> thrown) {
        ProcessorInterceptor.super.after(location, event, thrown);
      }
    });
    startFlowWithInterceptors(interceptor);

    CoreEvent result = process(flow, eventBuilder(muleContext).message(Message.of("")).build());
    assertThat(result.getMessage().getPayload().getValue(), is(""));
    assertThat(result.getError().isPresent(), is(false));

    if (useMockInterceptor) {
      InOrder inOrder = inOrder(processor, interceptor);

      inOrder.verify(interceptor).before(any(), any(), any());
      inOrder.verify(interceptor).around(any(), any(), any(), any());
      inOrder.verify(processor, never()).process(any());
      inOrder.verify(interceptor).after(any(), any(), eq(empty()));

      assertThat(((InternalEvent) result).getInternalParameters().entrySet(), hasSize(0));
      verifyParametersResolvedAndDisposed(times(1));
    }
  }

  @Test
  public void firstInterceptorMutatesEventBefore() throws Exception {
    ProcessorInterceptor interceptor1 = prepareInterceptor(new TestProcessorInterceptor("outer") {

      @Override
      public void before(ComponentLocation location, Map<String, ProcessorParameterValue> parameters, InterceptionEvent event) {
        event.message(Message.of(TEST_PAYLOAD));
      }
    });
    ProcessorInterceptor interceptor2 = prepareInterceptor(new TestProcessorInterceptor("inner") {});
    startFlowWithInterceptors(interceptor1, interceptor2);

    CoreEvent result = process(flow, eventBuilder(muleContext).message(Message.of("")).build());
    assertThat(result.getMessage().getPayload().getValue(), is(TEST_PAYLOAD));
    assertThat(result.getError().isPresent(), is(false));

    if (useMockInterceptor) {
      InOrder inOrder = inOrder(processor, interceptor1, interceptor2);

      inOrder.verify(interceptor1).before(any(), mapArgWithEntry("param", ""), any());
      inOrder.verify(interceptor2).before(any(), mapArgWithEntry("param", TEST_PAYLOAD),
                                          argThat(interceptionHasPayloadValue(TEST_PAYLOAD)));
      inOrder.verify(interceptor1).around(any(), mapArgWithEntry("param", TEST_PAYLOAD),
                                          argThat(interceptionHasPayloadValue(TEST_PAYLOAD)), any());
      inOrder.verify(interceptor2).around(any(), mapArgWithEntry("param", TEST_PAYLOAD),
                                          argThat(interceptionHasPayloadValue(TEST_PAYLOAD)), any());
      inOrder.verify(processor).process(argThat(hasPayloadValue(TEST_PAYLOAD)));
      inOrder.verify(interceptor2).after(any(), argThat(interceptionHasPayloadValue(TEST_PAYLOAD)), eq(empty()));
      inOrder.verify(interceptor1).after(any(), argThat(interceptionHasPayloadValue(TEST_PAYLOAD)), eq(empty()));

      assertThat(((InternalEvent) result).getInternalParameters().entrySet(), hasSize(0));
      verifyParametersResolvedAndDisposed(times(2));
    }
  }

  @Test
  public void secondInterceptorMutatesEventBefore() throws Exception {
    ProcessorInterceptor interceptor1 = prepareInterceptor(new TestProcessorInterceptor("outer") {});
    ProcessorInterceptor interceptor2 = prepareInterceptor(new TestProcessorInterceptor("inner") {

      @Override
      public void before(ComponentLocation location, Map<String, ProcessorParameterValue> parameters, InterceptionEvent event) {
        event.message(Message.of(TEST_PAYLOAD));
      }
    });
    startFlowWithInterceptors(interceptor1, interceptor2);

    CoreEvent result = process(flow, eventBuilder(muleContext).message(Message.of("")).build());
    assertThat(result.getMessage().getPayload().getValue(), is(TEST_PAYLOAD));
    assertThat(result.getError().isPresent(), is(false));

    if (useMockInterceptor) {
      InOrder inOrder = inOrder(processor, interceptor1, interceptor2);

      inOrder.verify(interceptor1).before(any(), mapArgWithEntry("param", ""), any());
      inOrder.verify(interceptor2).before(any(), mapArgWithEntry("param", ""), any());
      inOrder.verify(interceptor1).around(any(), mapArgWithEntry("param", TEST_PAYLOAD), any(), any());
      inOrder.verify(interceptor2).around(any(), mapArgWithEntry("param", TEST_PAYLOAD),
                                          argThat(interceptionHasPayloadValue(TEST_PAYLOAD)), any());
      inOrder.verify(processor).process(argThat(hasPayloadValue(TEST_PAYLOAD)));
      inOrder.verify(interceptor2).after(any(), argThat(interceptionHasPayloadValue(TEST_PAYLOAD)), eq(empty()));
      inOrder.verify(interceptor1).after(any(), argThat(interceptionHasPayloadValue(TEST_PAYLOAD)), eq(empty()));

      assertThat(((InternalEvent) result).getInternalParameters().entrySet(), hasSize(0));
      verifyParametersResolvedAndDisposed(times(2));
    }
  }

  @Test
  public void firstInterceptorMutatesEventAfter() throws Exception {
    ProcessorInterceptor interceptor1 = prepareInterceptor(new TestProcessorInterceptor("outer") {

      @Override
      public void after(ComponentLocation location, InterceptionEvent event, Optional<Throwable> thrown) {
        event.message(Message.of(TEST_PAYLOAD));
      }
    });
    ProcessorInterceptor interceptor2 = prepareInterceptor(new TestProcessorInterceptor("inner") {});
    startFlowWithInterceptors(interceptor1, interceptor2);

    CoreEvent result = process(flow, eventBuilder(muleContext).message(Message.of("")).build());
    assertThat(result.getMessage().getPayload().getValue(), is(TEST_PAYLOAD));
    assertThat(result.getError().isPresent(), is(false));

    if (useMockInterceptor) {
      InOrder inOrder = inOrder(processor, interceptor1, interceptor2);

      inOrder.verify(interceptor1).before(any(), any(), any());
      inOrder.verify(interceptor2).before(any(), any(), any());
      inOrder.verify(interceptor1).around(any(), any(), any(), any());
      inOrder.verify(interceptor2).around(any(), any(), any(), any());
      inOrder.verify(processor).process(argThat(hasPayloadValue("")));
      inOrder.verify(interceptor2).after(any(), any(), eq(empty()));
      inOrder.verify(interceptor1).after(any(), any(), eq(empty()));

      assertThat(((InternalEvent) result).getInternalParameters().entrySet(), hasSize(0));
      verifyParametersResolvedAndDisposed(times(1));
    }
  }

  @Test
  public void secondInterceptorMutatesEventAfter() throws Exception {
    ProcessorInterceptor interceptor1 = prepareInterceptor(new TestProcessorInterceptor("outer") {});
    ProcessorInterceptor interceptor2 = prepareInterceptor(new TestProcessorInterceptor("inner") {

      @Override
      public void after(ComponentLocation location, InterceptionEvent event, Optional<Throwable> thrown) {
        event.message(Message.of(TEST_PAYLOAD));
      }
    });
    startFlowWithInterceptors(interceptor1, interceptor2);

    CoreEvent result = process(flow, eventBuilder(muleContext).message(Message.of("")).build());
    assertThat(result.getMessage().getPayload().getValue(), is(TEST_PAYLOAD));
    assertThat(result.getError().isPresent(), is(false));

    if (useMockInterceptor) {
      InOrder inOrder = inOrder(processor, interceptor1, interceptor2);

      inOrder.verify(interceptor1).before(any(), any(), any());
      inOrder.verify(interceptor2).before(any(), any(), any());
      inOrder.verify(interceptor1).around(any(), any(), any(), any());
      inOrder.verify(interceptor2).around(any(), any(), any(), any());
      inOrder.verify(processor).process(argThat(hasPayloadValue("")));
      inOrder.verify(interceptor2).after(any(), any(), eq(empty()));
      inOrder.verify(interceptor1).after(any(), argThat(interceptionHasPayloadValue(TEST_PAYLOAD)), eq(empty()));

      assertThat(((InternalEvent) result).getInternalParameters().entrySet(), hasSize(0));
      verifyParametersResolvedAndDisposed(times(1));
    }
  }

  @Test
  public void firstInterceptorMutatesEventAroundBeforeProceed() throws Exception {
    ProcessorInterceptor interceptor1 = prepareInterceptor(new TestProcessorInterceptor("outer") {

      @Override
      public CompletableFuture<InterceptionEvent> around(ComponentLocation location,
                                                         Map<String, ProcessorParameterValue> parameters,
                                                         InterceptionEvent event, InterceptionAction action) {
        event.message(Message.of(TEST_PAYLOAD));
        return action.proceed();
      }
    });
    ProcessorInterceptor interceptor2 = prepareInterceptor(new TestProcessorInterceptor("inner") {});
    startFlowWithInterceptors(interceptor1, interceptor2);

    CoreEvent result = process(flow, eventBuilder(muleContext).message(Message.of("")).build());
    assertThat(result.getMessage().getPayload().getValue(), is(TEST_PAYLOAD));
    assertThat(result.getError().isPresent(), is(false));

    if (useMockInterceptor) {
      InOrder inOrder = inOrder(processor, interceptor1, interceptor2);

      inOrder.verify(interceptor1).before(any(), mapArgWithEntry("param", ""), any());
      inOrder.verify(interceptor2).before(any(), mapArgWithEntry("param", ""), any());
      inOrder.verify(interceptor1).around(any(), mapArgWithEntry("param", ""),
                                          argThat(interceptionHasPayloadValue(TEST_PAYLOAD)), any());
      inOrder.verify(interceptor2).around(any(), mapArgWithEntry("param", TEST_PAYLOAD),
                                          argThat(interceptionHasPayloadValue(TEST_PAYLOAD)), any());
      inOrder.verify(processor).process(argThat(hasPayloadValue(TEST_PAYLOAD)));
      inOrder.verify(interceptor2).after(any(), argThat(interceptionHasPayloadValue(TEST_PAYLOAD)), eq(empty()));
      inOrder.verify(interceptor1).after(any(), argThat(interceptionHasPayloadValue(TEST_PAYLOAD)), eq(empty()));

      assertThat(((InternalEvent) result).getInternalParameters().entrySet(), hasSize(0));
      verifyParametersResolvedAndDisposed(times(2));
    }
  }

  @Test
  public void secondInterceptorMutatesEventAroundBeforeProceed() throws Exception {
    ProcessorInterceptor interceptor1 = prepareInterceptor(new TestProcessorInterceptor("outer") {});
    ProcessorInterceptor interceptor2 = prepareInterceptor(new TestProcessorInterceptor("inner") {

      @Override
      public CompletableFuture<InterceptionEvent> around(ComponentLocation location,
                                                         Map<String, ProcessorParameterValue> parameters,
                                                         InterceptionEvent event, InterceptionAction action) {
        event.message(Message.of(TEST_PAYLOAD));
        return action.proceed();
      }
    });
    startFlowWithInterceptors(interceptor1, interceptor2);

    CoreEvent result = process(flow, eventBuilder(muleContext).message(Message.of("")).build());
    assertThat(result.getMessage().getPayload().getValue(), is(TEST_PAYLOAD));
    assertThat(result.getError().isPresent(), is(false));

    if (useMockInterceptor) {
      InOrder inOrder = inOrder(processor, interceptor1, interceptor2);

      inOrder.verify(interceptor1).before(any(), mapArgWithEntry("param", ""), any());
      inOrder.verify(interceptor2).before(any(), mapArgWithEntry("param", ""), any());
      inOrder.verify(interceptor1).around(any(), mapArgWithEntry("param", ""), any(), any());
      inOrder.verify(interceptor2).around(any(), mapArgWithEntry("param", ""), any(), any());
      inOrder.verify(processor).process(argThat(hasPayloadValue(TEST_PAYLOAD)));
      inOrder.verify(interceptor2).after(any(), argThat(interceptionHasPayloadValue(TEST_PAYLOAD)), eq(empty()));
      inOrder.verify(interceptor1).after(any(), argThat(interceptionHasPayloadValue(TEST_PAYLOAD)), eq(empty()));

      assertThat(((InternalEvent) result).getInternalParameters().entrySet(), hasSize(0));
      verifyParametersResolvedAndDisposed(times(1));
    }
  }

  @Test
  public void firstInterceptorMutatesEventAroundAfterProceed() throws Exception {
    ProcessorInterceptor interceptor1 = prepareInterceptor(new TestProcessorInterceptor("outer") {

      @Override
      public CompletableFuture<InterceptionEvent> around(ComponentLocation location,
                                                         Map<String, ProcessorParameterValue> parameters,
                                                         InterceptionEvent event, InterceptionAction action) {
        return action.proceed().thenCompose(result -> supplyAsync(() -> {
          result.message(Message.of(TEST_PAYLOAD));
          return result;
        }));
      }
    });
    ProcessorInterceptor interceptor2 = prepareInterceptor(new TestProcessorInterceptor("inner") {});
    startFlowWithInterceptors(interceptor1, interceptor2);

    CoreEvent result = process(flow, eventBuilder(muleContext).message(Message.of("")).build());
    assertThat(result.getMessage().getPayload().getValue(), is(TEST_PAYLOAD));
    assertThat(result.getError().isPresent(), is(false));

    if (useMockInterceptor) {
      InOrder inOrder = inOrder(processor, interceptor1, interceptor2);

      inOrder.verify(interceptor1).before(any(), mapArgWithEntry("param", ""), any());
      inOrder.verify(interceptor2).before(any(), mapArgWithEntry("param", ""), any());
      inOrder.verify(interceptor1).around(any(), mapArgWithEntry("param", ""), any(), any());
      inOrder.verify(interceptor2).around(any(), mapArgWithEntry("param", ""), any(), any());
      inOrder.verify(processor).process(argThat(hasPayloadValue("")));
      inOrder.verify(interceptor2).after(any(), any(), eq(empty()));
      inOrder.verify(interceptor1).after(any(), argThat(interceptionHasPayloadValue(TEST_PAYLOAD)), eq(empty()));

      assertThat(((InternalEvent) result).getInternalParameters().entrySet(), hasSize(0));
      verifyParametersResolvedAndDisposed(times(1));
    }
  }

  @Test
  public void firstInterceptorMutatesEventAroundAfterProceedChained() throws Exception {
    ProcessorInterceptor interceptor1 = prepareInterceptor(new TestProcessorInterceptor("outer") {

      @Override
      public CompletableFuture<InterceptionEvent> around(ComponentLocation location,
                                                         Map<String, ProcessorParameterValue> parameters,
                                                         InterceptionEvent event, InterceptionAction action) {
        return action.proceed().thenApplyAsync(e -> {
          e.message(Message.of(TEST_PAYLOAD));
          return e;
        });
      }
    });
    ProcessorInterceptor interceptor2 = prepareInterceptor(new TestProcessorInterceptor("inner") {});
    startFlowWithInterceptors(interceptor1, interceptor2);

    CoreEvent result = process(flow, eventBuilder(muleContext).message(Message.of("")).build());
    assertThat(result.getMessage().getPayload().getValue(), is(TEST_PAYLOAD));
    assertThat(result.getError().isPresent(), is(false));

    if (useMockInterceptor) {
      InOrder inOrder = inOrder(processor, interceptor1, interceptor2);

      inOrder.verify(interceptor1).before(any(), mapArgWithEntry("param", ""), any());
      inOrder.verify(interceptor2).before(any(), mapArgWithEntry("param", ""), any());
      inOrder.verify(interceptor1).around(any(), mapArgWithEntry("param", ""), any(), any());
      inOrder.verify(interceptor2).around(any(), mapArgWithEntry("param", ""), any(), any());
      inOrder.verify(processor).process(argThat(hasPayloadValue("")));
      inOrder.verify(interceptor2).after(any(), any(), eq(empty()));
      inOrder.verify(interceptor1).after(any(), argThat(interceptionHasPayloadValue(TEST_PAYLOAD)), eq(empty()));

      assertThat(((InternalEvent) result).getInternalParameters().entrySet(), hasSize(0));
      verifyParametersResolvedAndDisposed(times(1));
    }
  }

  @Test
  public void secondInterceptorMutatesEventAroundAfterProceed() throws Exception {
    ProcessorInterceptor interceptor1 = prepareInterceptor(new TestProcessorInterceptor("outer") {});
    ProcessorInterceptor interceptor2 = prepareInterceptor(new TestProcessorInterceptor("inner") {

      @Override
      public CompletableFuture<InterceptionEvent> around(ComponentLocation location,
                                                         Map<String, ProcessorParameterValue> parameters,
                                                         InterceptionEvent event, InterceptionAction action) {
        return action.proceed().thenCompose(result -> supplyAsync(() -> {
          result.message(Message.of(TEST_PAYLOAD));
          return result;
        }));
      }
    });
    startFlowWithInterceptors(interceptor1, interceptor2);

    CoreEvent result = process(flow, eventBuilder(muleContext).message(Message.of("")).build());
    assertThat(result.getMessage().getPayload().getValue(), is(TEST_PAYLOAD));
    assertThat(result.getError().isPresent(), is(false));

    if (useMockInterceptor) {
      InOrder inOrder = inOrder(processor, interceptor1, interceptor2);

      inOrder.verify(interceptor1).before(any(), mapArgWithEntry("param", ""), any());
      inOrder.verify(interceptor2).before(any(), mapArgWithEntry("param", ""), any());
      inOrder.verify(interceptor1).around(any(), mapArgWithEntry("param", ""), any(), any());
      inOrder.verify(interceptor2).around(any(), mapArgWithEntry("param", ""), any(), any());
      inOrder.verify(processor).process(argThat(hasPayloadValue("")));
      inOrder.verify(interceptor2).after(any(), argThat(interceptionHasPayloadValue(TEST_PAYLOAD)), eq(empty()));
      inOrder.verify(interceptor1).after(any(), argThat(interceptionHasPayloadValue(TEST_PAYLOAD)), eq(empty()));

      assertThat(((InternalEvent) result).getInternalParameters().entrySet(), hasSize(0));
      verifyParametersResolvedAndDisposed(times(1));
    }
  }

  @Test
  public void secondInterceptorMutatesEventAroundAfterProceedChained() throws Exception {
    ProcessorInterceptor interceptor1 = prepareInterceptor(new TestProcessorInterceptor("outer") {});
    ProcessorInterceptor interceptor2 = prepareInterceptor(new TestProcessorInterceptor("inner") {

      @Override
      public CompletableFuture<InterceptionEvent> around(ComponentLocation location,
                                                         Map<String, ProcessorParameterValue> parameters,
                                                         InterceptionEvent event, InterceptionAction action) {
        return action.proceed().thenApplyAsync(e -> {
          e.message(Message.of(TEST_PAYLOAD));
          return e;
        });
      }
    });
    startFlowWithInterceptors(interceptor1, interceptor2);

    CoreEvent result = process(flow, eventBuilder(muleContext).message(Message.of("")).build());
    assertThat(result.getMessage().getPayload().getValue(), is(TEST_PAYLOAD));
    assertThat(result.getError().isPresent(), is(false));

    if (useMockInterceptor) {
      InOrder inOrder = inOrder(processor, interceptor1, interceptor2);

      inOrder.verify(interceptor1).before(any(), mapArgWithEntry("param", ""), any());
      inOrder.verify(interceptor2).before(any(), mapArgWithEntry("param", ""), any());
      inOrder.verify(interceptor1).around(any(), mapArgWithEntry("param", ""), any(), any());
      inOrder.verify(interceptor2).around(any(), mapArgWithEntry("param", ""), any(), any());
      inOrder.verify(processor).process(argThat(hasPayloadValue("")));
      inOrder.verify(interceptor2).after(any(), argThat(interceptionHasPayloadValue(TEST_PAYLOAD)), eq(empty()));
      inOrder.verify(interceptor1).after(any(), argThat(interceptionHasPayloadValue(TEST_PAYLOAD)), eq(empty()));

      assertThat(((InternalEvent) result).getInternalParameters().entrySet(), hasSize(0));
      verifyParametersResolvedAndDisposed(times(1));
    }
  }

  @Test
  public void firstInterceptorThrowsExceptionBefore() throws Exception {
    RuntimeException expectedException = new RuntimeException("Some Error");
    ProcessorInterceptor interceptor1 = prepareInterceptor(new TestProcessorInterceptor("outer") {

      @Override
      public void before(ComponentLocation location, Map<String, ProcessorParameterValue> parameters, InterceptionEvent event) {
        throw expectedException;
      }
    });
    ProcessorInterceptor interceptor2 = prepareInterceptor(new TestProcessorInterceptor("inner") {});
    startFlowWithInterceptors(interceptor1, interceptor2);

    expected.expectCause(sameInstance(expectedException));
    try {
      process(flow, eventBuilder(muleContext).message(Message.of("")).build());
    } finally {
      if (useMockInterceptor) {
        InOrder inOrder = inOrder(processor, interceptor1, interceptor2);

        inOrder.verify(interceptor1).before(any(), any(), any());
        inOrder.verify(interceptor2, never()).before(any(), any(), any());
        inOrder.verify(interceptor1, never()).around(any(), any(), any(), any());
        inOrder.verify(interceptor2, never()).around(any(), any(), any(), any());
        inOrder.verify(processor, never()).process(any());
        inOrder.verify(interceptor2, never()).after(any(), any(), eq(of(expectedException)));
        inOrder.verify(interceptor1).after(any(), any(), eq(of(expectedException)));

        verifyParametersResolvedAndDisposed(times(1));
      }
    }
  }

  @Test
  public void secondInterceptorThrowsExceptionBefore() throws Exception {
    RuntimeException expectedException = new RuntimeException("Some Error");
    ProcessorInterceptor interceptor1 = prepareInterceptor(new TestProcessorInterceptor("outer") {});
    ProcessorInterceptor interceptor2 = prepareInterceptor(new TestProcessorInterceptor("inner") {

      @Override
      public void before(ComponentLocation location, Map<String, ProcessorParameterValue> parameters, InterceptionEvent event) {
        throw expectedException;
      }
    });
    startFlowWithInterceptors(interceptor1, interceptor2);

    expected.expectCause(sameInstance(expectedException));
    try {
      process(flow, eventBuilder(muleContext).message(Message.of("")).build());
    } finally {
      if (useMockInterceptor) {
        InOrder inOrder = inOrder(processor, interceptor1, interceptor2);

        inOrder.verify(interceptor1).before(any(), any(), any());
        inOrder.verify(interceptor2).before(any(), any(), any());
        inOrder.verify(interceptor1, never()).around(any(), any(), any(), any());
        inOrder.verify(interceptor2, never()).around(any(), any(), any(), any());
        inOrder.verify(processor, never()).process(any());
        inOrder.verify(interceptor2).after(any(), any(), eq(of(expectedException)));
        inOrder.verify(interceptor1).after(any(), any(), eq(of(expectedException)));

        verifyParametersResolvedAndDisposed(times(1));
      }
    }
  }

  @Test
  public void firstInterceptorThrowsExceptionAfter() throws Exception {
    RuntimeException expectedException = new RuntimeException("Some Error");
    ProcessorInterceptor interceptor1 = prepareInterceptor(new TestProcessorInterceptor("outer") {

      @Override
      public void after(ComponentLocation location, InterceptionEvent event, Optional<Throwable> thrown) {
        throw expectedException;
      }
    });
    ProcessorInterceptor interceptor2 = prepareInterceptor(new TestProcessorInterceptor("inner") {});
    startFlowWithInterceptors(interceptor1, interceptor2);

    expected.expectCause(sameInstance(expectedException));
    try {
      process(flow, eventBuilder(muleContext).message(Message.of("")).build());
    } finally {
      if (useMockInterceptor) {
        InOrder inOrder = inOrder(processor, interceptor1, interceptor2);

        inOrder.verify(interceptor1).before(any(), any(), any());
        inOrder.verify(interceptor2).before(any(), any(), any());
        inOrder.verify(interceptor1).around(any(), any(), any(), any());
        inOrder.verify(interceptor2).around(any(), any(), any(), any());
        inOrder.verify(processor).process(any());
        inOrder.verify(interceptor2).after(any(), any(), eq(empty()));
        inOrder.verify(interceptor1).after(any(), any(), eq(empty()));

        verifyParametersResolvedAndDisposed(times(1));
      }
    }
  }

  @Test
  public void processorFailsAndfirstInterceptorThrowsExceptionAfter() throws Exception {
    when(processor.process(any())).thenThrow(new RuntimeException("Processor Error"));
    RuntimeException expectedException = new RuntimeException("Some Error");
    ProcessorInterceptor interceptor1 = prepareInterceptor(new TestProcessorInterceptor("outer") {

      @Override
      public void after(ComponentLocation location, InterceptionEvent event, Optional<Throwable> thrown) {
        throw expectedException;
      }
    });
    startFlowWithInterceptors(interceptor1);

    expected.expect(MessagingException.class);
    expected.expectCause(sameInstance(expectedException));
    try {
      process(flow, eventBuilder(muleContext).message(Message.of("")).build());
    } catch (MessagingException e) {
      assertThat(e.getExceptionInfo().isAlreadyLogged(), is(true));
      throw e;
    }
  }

  @Test
  public void secondInterceptorThrowsExceptionAfter() throws Exception {
    RuntimeException expectedException = new RuntimeException("Some Error");
    ProcessorInterceptor interceptor1 = prepareInterceptor(new TestProcessorInterceptor("outer") {});
    ProcessorInterceptor interceptor2 = prepareInterceptor(new TestProcessorInterceptor("inner") {

      @Override
      public void after(ComponentLocation location, InterceptionEvent event, Optional<Throwable> thrown) {
        throw expectedException;
      }
    });
    startFlowWithInterceptors(interceptor1, interceptor2);

    expected.expectCause(sameInstance(expectedException));
    try {
      process(flow, eventBuilder(muleContext).message(Message.of("")).build());
    } finally {
      if (useMockInterceptor) {
        InOrder inOrder = inOrder(processor, interceptor1, interceptor2);

        inOrder.verify(interceptor1).before(any(), any(), any());
        inOrder.verify(interceptor2).before(any(), any(), any());
        inOrder.verify(interceptor1).around(any(), any(), any(), any());
        inOrder.verify(interceptor2).around(any(), any(), any(), any());
        inOrder.verify(processor).process(any());
        inOrder.verify(interceptor2).after(any(), any(), eq(empty()));
        inOrder.verify(interceptor1).after(any(), any(), eq(of(expectedException)));

        verifyParametersResolvedAndDisposed(times(1));
      }
    }
  }

  @Test
  public void firstInterceptorThrowsExceptionAround() throws Exception {
    RuntimeException expectedException = new RuntimeException("Some Error");
    ProcessorInterceptor interceptor1 = prepareInterceptor(new TestProcessorInterceptor("outer") {

      @Override
      public CompletableFuture<InterceptionEvent> around(ComponentLocation location,
                                                         Map<String, ProcessorParameterValue> parameters,
                                                         InterceptionEvent event, InterceptionAction action) {
        throw expectedException;
      }
    });
    ProcessorInterceptor interceptor2 = prepareInterceptor(new TestProcessorInterceptor("inner") {});
    startFlowWithInterceptors(interceptor1, interceptor2);

    expected.expectCause(sameInstance(expectedException));
    try {
      process(flow, eventBuilder(muleContext).message(Message.of("")).build());
    } finally {
      if (useMockInterceptor) {
        InOrder inOrder = inOrder(processor, interceptor1, interceptor2);

        inOrder.verify(interceptor1).before(any(), any(), any());
        inOrder.verify(interceptor2).before(any(), any(), any());
        inOrder.verify(interceptor1).around(any(), any(), any(), any());
        inOrder.verify(interceptor2, never()).around(any(), any(), any(), any());
        inOrder.verify(processor, never()).process(any());
        inOrder.verify(interceptor2).after(any(), any(), eq(of(expectedException)));
        inOrder.verify(interceptor1).after(any(), any(), eq(of(expectedException)));

        verifyParametersResolvedAndDisposed(times(1));
      }
    }
  }

  @Test
  public void secondInterceptorThrowsExceptionAround() throws Exception {
    RuntimeException expectedException = new RuntimeException("Some Error");
    ProcessorInterceptor interceptor1 = prepareInterceptor(new TestProcessorInterceptor("outer") {});
    ProcessorInterceptor interceptor2 = prepareInterceptor(new TestProcessorInterceptor("inner") {

      @Override
      public CompletableFuture<InterceptionEvent> around(ComponentLocation location,
                                                         Map<String, ProcessorParameterValue> parameters,
                                                         InterceptionEvent event, InterceptionAction action) {
        throw expectedException;
      }
    });
    startFlowWithInterceptors(interceptor1, interceptor2);

    expected.expectCause(sameInstance(expectedException));
    try {
      process(flow, eventBuilder(muleContext).message(Message.of("")).build());
    } finally {
      if (useMockInterceptor) {
        InOrder inOrder = inOrder(processor, interceptor1, interceptor2);

        inOrder.verify(interceptor1).before(any(), any(), any());
        inOrder.verify(interceptor2).before(any(), any(), any());
        inOrder.verify(interceptor1).around(any(), any(), any(), any());
        inOrder.verify(interceptor2).around(any(), any(), any(), any());
        inOrder.verify(processor, never()).process(any());
        inOrder.verify(interceptor2).after(any(), any(), eq(of(expectedException)));
        inOrder.verify(interceptor1).after(any(), any(), eq(of(expectedException)));

        verifyParametersResolvedAndDisposed(times(1));
      }
    }
  }

  @Test
  public void firstInterceptorFailsAround() throws Exception {
    RuntimeException expectedException = new RuntimeException("Some Error");
    ProcessorInterceptor interceptor1 = prepareInterceptor(new TestProcessorInterceptor("outer") {

      @Override
      public CompletableFuture<InterceptionEvent> around(ComponentLocation location,
                                                         Map<String, ProcessorParameterValue> parameters,
                                                         InterceptionEvent event, InterceptionAction action) {
        return action.fail(expectedException);
      }
    });
    ProcessorInterceptor interceptor2 = prepareInterceptor(new TestProcessorInterceptor("inner") {});
    startFlowWithInterceptors(interceptor1, interceptor2);

    expected.expectCause(sameInstance(expectedException));
    try {
      process(flow, eventBuilder(muleContext).message(Message.of("")).build());
    } finally {
      if (useMockInterceptor) {
        InOrder inOrder = inOrder(processor, interceptor1, interceptor2);

        inOrder.verify(interceptor1).before(any(), any(), any());
        inOrder.verify(interceptor2).before(any(), any(), any());
        inOrder.verify(interceptor1).around(any(), any(), any(), any());
        inOrder.verify(interceptor2, never()).around(any(), any(), any(), any());
        inOrder.verify(processor, never()).process(any());
        inOrder.verify(interceptor2).after(any(), any(), eq(of(expectedException)));
        inOrder.verify(interceptor1).after(any(), any(), eq(of(expectedException)));

        verifyParametersResolvedAndDisposed(times(1));
      }
    }
  }

  @Test
  public void secondInterceptorFailsAround() throws Exception {
    RuntimeException expectedException = new RuntimeException("Some Error");
    ProcessorInterceptor interceptor1 = prepareInterceptor(new TestProcessorInterceptor("outer") {});
    ProcessorInterceptor interceptor2 = prepareInterceptor(new TestProcessorInterceptor("inner") {

      @Override
      public CompletableFuture<InterceptionEvent> around(ComponentLocation location,
                                                         Map<String, ProcessorParameterValue> parameters,
                                                         InterceptionEvent event, InterceptionAction action) {
        return action.fail(expectedException);
      }
    });
    startFlowWithInterceptors(interceptor1, interceptor2);

    expected.expectCause(sameInstance(expectedException));
    try {
      process(flow, eventBuilder(muleContext).message(Message.of("")).build());
    } finally {
      if (useMockInterceptor) {
        InOrder inOrder = inOrder(processor, interceptor1, interceptor2);

        inOrder.verify(interceptor1).before(any(), any(), any());
        inOrder.verify(interceptor2).before(any(), any(), any());
        inOrder.verify(interceptor1).around(any(), any(), any(), any());
        inOrder.verify(interceptor2).around(any(), any(), any(), any());
        inOrder.verify(processor, never()).process(any());
        inOrder.verify(interceptor2).after(any(), any(), eq(of(expectedException)));
        inOrder.verify(interceptor1).after(any(), any(), eq(of(expectedException)));

        verifyParametersResolvedAndDisposed(times(1));
      }
    }
  }

  @Test
  public void firstInterceptorThrowsExceptionAroundAfterProceed() throws Exception {
    RuntimeException expectedException = new RuntimeException("Some Error");
    ProcessorInterceptor interceptor1 = prepareInterceptor(new TestProcessorInterceptor("outer") {

      @Override
      public CompletableFuture<InterceptionEvent> around(ComponentLocation location,
                                                         Map<String, ProcessorParameterValue> parameters,
                                                         InterceptionEvent event, InterceptionAction action) {
        return action.proceed().thenCompose(result -> {
          final CompletableFuture<InterceptionEvent> completableFuture = new CompletableFuture<>();
          completableFuture.completeExceptionally(expectedException);
          return completableFuture;
        });
      }
    });
    ProcessorInterceptor interceptor2 = prepareInterceptor(new TestProcessorInterceptor("inner") {});
    startFlowWithInterceptors(interceptor1, interceptor2);

    expected.expectCause(sameInstance(expectedException));
    try {
      process(flow, eventBuilder(muleContext).message(Message.of("")).build());
    } finally {
      if (useMockInterceptor) {
        InOrder inOrder = inOrder(processor, interceptor1, interceptor2);

        inOrder.verify(interceptor1).before(any(), any(), any());
        inOrder.verify(interceptor2).before(any(), any(), any());
        inOrder.verify(interceptor1).around(any(), any(), any(), any());
        inOrder.verify(interceptor2).around(any(), any(), any(), any());
        inOrder.verify(processor).process(any());
        inOrder.verify(interceptor2, never()).after(any(), any(), eq(empty()));
        inOrder.verify(interceptor1).after(any(), any(), eq(of(expectedException)));

        verifyParametersResolvedAndDisposed(times(1));
      }
    }
  }

  @Test
  public void secondInterceptorThrowsExceptionAroundAfterProceed() throws Exception {
    RuntimeException expectedException = new RuntimeException("Some Error");
    ProcessorInterceptor interceptor1 = prepareInterceptor(new TestProcessorInterceptor("outer") {});
    ProcessorInterceptor interceptor2 = prepareInterceptor(new TestProcessorInterceptor("inner") {

      @Override
      public CompletableFuture<InterceptionEvent> around(ComponentLocation location,
                                                         Map<String, ProcessorParameterValue> parameters,
                                                         InterceptionEvent event, InterceptionAction action) {
        return action.proceed().thenCompose(result -> {
          final CompletableFuture<InterceptionEvent> completableFuture = new CompletableFuture<>();
          completableFuture.completeExceptionally(expectedException);
          return completableFuture;
        });
      }
    });
    startFlowWithInterceptors(interceptor1, interceptor2);

    expected.expectCause(sameInstance(expectedException));
    try {
      process(flow, eventBuilder(muleContext).message(Message.of("")).build());
    } finally {
      if (useMockInterceptor) {
        InOrder inOrder = inOrder(processor, interceptor1, interceptor2);

        inOrder.verify(interceptor1).before(any(), any(), any());
        inOrder.verify(interceptor2).before(any(), any(), any());
        inOrder.verify(interceptor1).around(any(), any(), any(), any());
        inOrder.verify(interceptor2).around(any(), any(), any(), any());
        inOrder.verify(processor).process(any());
        inOrder.verify(interceptor2).after(any(), any(), eq(of(expectedException)));
        inOrder.verify(interceptor1).after(any(), any(), eq(of(expectedException)));

        verifyParametersResolvedAndDisposed(times(1));
      }
    }
  }

  @Test
  public void firstInterceptorSkipsProcessor() throws Exception {
    ProcessorInterceptor interceptor1 = prepareInterceptor(new TestProcessorInterceptor("outer") {

      @Override
      public CompletableFuture<InterceptionEvent> around(ComponentLocation location,
                                                         Map<String, ProcessorParameterValue> parameters,
                                                         InterceptionEvent event, InterceptionAction action) {
        return action.skip();
      }
    });
    ProcessorInterceptor interceptor2 = prepareInterceptor(new TestProcessorInterceptor("inner") {});
    startFlowWithInterceptors(interceptor1, interceptor2);

    CoreEvent result = process(flow, eventBuilder(muleContext).message(Message.of("")).build());
    assertThat(result.getMessage().getPayload().getValue(), is(""));

    if (useMockInterceptor) {
      InOrder inOrder = inOrder(processor, interceptor1, interceptor2);

      inOrder.verify(interceptor1).before(any(), any(), any());
      inOrder.verify(interceptor2).before(any(), any(), any());
      inOrder.verify(interceptor1).around(any(), any(), any(), any());
      inOrder.verify(interceptor2, never()).around(any(), any(), any(), any());
      inOrder.verify(processor, never()).process(any());
      inOrder.verify(interceptor2).after(any(), any(), eq(empty()));
      inOrder.verify(interceptor1).after(any(), any(), eq(empty()));

      assertThat(((InternalEvent) result).getInternalParameters().entrySet(), hasSize(0));
      verifyParametersResolvedAndDisposed(times(1));
    }
  }

  @Test
  public void secondInterceptorSkipsProcessor() throws Exception {
    ProcessorInterceptor interceptor1 = prepareInterceptor(new TestProcessorInterceptor("outer") {});
    ProcessorInterceptor interceptor2 = prepareInterceptor(new TestProcessorInterceptor("inner") {

      @Override
      public CompletableFuture<InterceptionEvent> around(ComponentLocation location,
                                                         Map<String, ProcessorParameterValue> parameters,
                                                         InterceptionEvent event, InterceptionAction action) {
        return action.skip();
      }
    });
    startFlowWithInterceptors(interceptor1, interceptor2);

    CoreEvent result = process(flow, eventBuilder(muleContext).message(Message.of("")).build());
    assertThat(result.getMessage().getPayload().getValue(), is(""));

    if (useMockInterceptor) {
      InOrder inOrder = inOrder(processor, interceptor1, interceptor2);

      inOrder.verify(interceptor1).before(any(), any(), any());
      inOrder.verify(interceptor2).before(any(), any(), any());
      inOrder.verify(interceptor1).around(any(), any(), any(), any());
      inOrder.verify(interceptor2).around(any(), any(), any(), any());
      inOrder.verify(processor, never()).process(any());
      inOrder.verify(interceptor2).after(any(), any(), eq(empty()));
      inOrder.verify(interceptor1).after(any(), any(), eq(empty()));

      assertThat(((InternalEvent) result).getInternalParameters().entrySet(), hasSize(0));
      verifyParametersResolvedAndDisposed(times(1));
    }
  }

  @Test
  public void firstInterceptorDoesntApply() throws Exception {
    ProcessorInterceptor interceptor1 = prepareInterceptor(new TestProcessorInterceptor("outer") {});
    ProcessorInterceptor interceptor2 = prepareInterceptor(new TestProcessorInterceptor("inner") {});
    startFlowWithInterceptorFactories(new ProcessorInterceptorFactory() {

      @Override
      public boolean intercept(ComponentLocation location) {
        return false;
      }

      @Override
      public ProcessorInterceptor get() {
        return interceptor1;
      };
    }, () -> interceptor2);

    CoreEvent result = process(flow, eventBuilder(muleContext).message(Message.of("")).build());
    assertThat(result.getMessage().getPayload().getValue(), is(""));
    assertThat(result.getError().isPresent(), is(false));

    if (useMockInterceptor) {
      InOrder inOrder = inOrder(processor, interceptor1, interceptor2);

      inOrder.verify(interceptor1, never()).before(any(), any(), any());
      inOrder.verify(interceptor2).before(any(), any(), any());
      inOrder.verify(interceptor1, never()).around(any(), any(), any(), any());
      inOrder.verify(interceptor2).around(any(), any(), any(), any());
      inOrder.verify(processor).process(any());
      inOrder.verify(interceptor2).after(any(), any(), eq(empty()));
      inOrder.verify(interceptor1, never()).after(any(), any(), eq(empty()));

      assertThat(((InternalEvent) result).getInternalParameters().entrySet(), hasSize(0));
      verifyParametersResolvedAndDisposed(times(1));
    }
  }

  @Test
  public void secondInterceptorDoesntApply() throws Exception {
    ProcessorInterceptor interceptor1 = prepareInterceptor(new TestProcessorInterceptor("outer") {});
    ProcessorInterceptor interceptor2 = prepareInterceptor(new TestProcessorInterceptor("inner") {});
    startFlowWithInterceptorFactories(() -> interceptor1, new ProcessorInterceptorFactory() {

      @Override
      public boolean intercept(ComponentLocation location) {
        return false;
      }

      @Override
      public ProcessorInterceptor get() {
        return interceptor2;
      };
    });

    CoreEvent result = process(flow, eventBuilder(muleContext).message(Message.of("")).build());
    assertThat(result.getMessage().getPayload().getValue(), is(""));
    assertThat(result.getError().isPresent(), is(false));

    if (useMockInterceptor) {
      InOrder inOrder = inOrder(processor, interceptor1, interceptor2);

      inOrder.verify(interceptor1).before(any(), any(), any());
      inOrder.verify(interceptor1).around(any(), any(), any(), any());
      inOrder.verify(interceptor2, never()).before(any(), any(), any());
      inOrder.verify(interceptor2, never()).around(any(), any(), any(), any());
      inOrder.verify(processor).process(any());
      inOrder.verify(interceptor2, never()).after(any(), any(), eq(empty()));
      inOrder.verify(interceptor1).after(any(), any(), eq(empty()));

      assertThat(((InternalEvent) result).getInternalParameters().entrySet(), hasSize(0));
      verifyParametersResolvedAndDisposed(times(1));
    }
  }

  @Test
  public void paramWithErrorExpression() throws Exception {
    Component annotatedProcessor = (Component) processor;
    Map<QName, Object> annotations = new HashMap<>(annotatedProcessor.getAnnotations());
    Map<String, String> params = new HashMap<>((Map<String, String>) annotations.get(ANNOTATION_PARAMETERS));
    params.put("errorExpr", "#[notAnExpression]");
    annotations.put(ANNOTATION_PARAMETERS, params);
    ((Component) processor).setAnnotations(annotations);

    ProcessorInterceptor interceptor = prepareInterceptor(new ProcessorInterceptor() {

      // This is done for using mockito spied object in the case of default methods.
      @Override
      public CompletableFuture<InterceptionEvent> around(ComponentLocation location,
                                                         Map<String, ProcessorParameterValue> parameters, InterceptionEvent event,
                                                         InterceptionAction action) {
        return ProcessorInterceptor.super.around(location, parameters, event, action);
      }

      // This is done for using mockito spied object in the case of default methods.
      @Override
      public void before(ComponentLocation location, Map<String, ProcessorParameterValue> parameters, InterceptionEvent event) {
        ProcessorInterceptor.super.before(location, parameters, event);
      }

      // This is done for using mockito spied object in the case of default methods.
      @Override
      public void after(ComponentLocation location, InterceptionEvent event, Optional<Throwable> thrown) {
        ProcessorInterceptor.super.after(location, event, thrown);
      }
    });
    startFlowWithInterceptors(interceptor);

    process(flow, eventBuilder(muleContext).message(Message.of("")).build());

    if (useMockInterceptor) {
      InOrder inOrder = inOrder(processor, interceptor);

      inOrder.verify(interceptor)
          .before(any(),
                  mapArgWithErrorEntry("errorExpr", instanceOf(ExpressionRuntimeException.class)/* "#[notAnExpression]" */),
                  any());
      inOrder.verify(interceptor)
          .around(any(),
                  mapArgWithErrorEntry("errorExpr", instanceOf(ExpressionRuntimeException.class)/* "#[notAnExpression]" */),
                  any(), any());
      inOrder.verify(processor).process(any());
      inOrder.verify(interceptor).after(any(), any(), any());

      verifyParametersResolvedAndDisposed(times(1));
    }
  }

  @Test
  public void threadForBeforeAfter() throws Exception {
    AtomicReference<Thread> threadBefore = new AtomicReference<>();
    AtomicReference<Thread> threadAfter = new AtomicReference<>();

    ProcessorInterceptor interceptor = prepareInterceptor(new ProcessorInterceptor() {

      @Override
      public void before(ComponentLocation location, Map<String, ProcessorParameterValue> parameters, InterceptionEvent event) {
        threadBefore.set(currentThread());
      }

      @Override
      public void after(ComponentLocation location, InterceptionEvent event, java.util.Optional<Throwable> thrown) {
        threadAfter.set(currentThread());
      };

      // This is done for using mockito spied object in the case of default methods.
      @Override
      public CompletableFuture<InterceptionEvent> around(ComponentLocation location,
                                                         Map<String, ProcessorParameterValue> parameters, InterceptionEvent event,
                                                         InterceptionAction action) {
        return ProcessorInterceptor.super.around(location, parameters, event, action);
      }
    });
    startFlowWithInterceptors(interceptor);

    process(flow, eventBuilder(muleContext).message(Message.of("")).build());

    assertThat(threadAfter.get().getName(), threadAfter.get().getThreadGroup().getName(),
               not(is(NonBlockingProcessorInApp.SELECTOR_EMULATOR_SCHEDULER_NAME)));
    assertThat(threadAfter.get().getName(), threadAfter.get().getThreadGroup().getName(),
               is(threadBefore.get().getThreadGroup().getName()));
  }

  @Test
  @io.qameta.allure.Description("Simulates the error handling scenario for XML SDK operations")
  public void interceptorErrorResumeAround() throws Exception {
    Exception thrown = new Exception();

    ProcessorInterceptor interceptor = prepareInterceptor(new ProcessorInterceptor() {

      @Override
      public CompletableFuture<InterceptionEvent> around(ComponentLocation location,
                                                         Map<String, ProcessorParameterValue> parameters,
                                                         InterceptionEvent event, InterceptionAction action) {
        Mono<InterceptionEvent> errorMono = Mono.error(thrown);
        return Mono.from(((BaseEventContext) event.getContext()).error(thrown)).then(errorMono).toFuture();
      }

      @Override
      public void after(ComponentLocation location, InterceptionEvent event, Optional<Throwable> thrown) {

      }

      // This is done for using mockito spied object in the case of default methods.
      @Override
      public void before(ComponentLocation location, Map<String, ProcessorParameterValue> parameters, InterceptionEvent event) {
        ProcessorInterceptor.super.before(location, parameters, event);
      }
    });
    startFlowWithInterceptors(interceptor);

    expected.expectCause(sameInstance(thrown));
    try {
      process(flow, eventBuilder(muleContext).message(Message.of("")).build());
    } finally {
      if (useMockInterceptor) {
        new PollingProber().probe(() -> {
          verify(interceptor).after(any(), any(), eq(Optional.of(thrown)));
          return true;
        });
      }
    }
  }

  private ProcessorInterceptor interceptorThatFailsWith(Component mockedComponent) {
    return prepareInterceptor(new ProcessorInterceptor() {

      @Override
      public CompletableFuture<InterceptionEvent> around(ComponentLocation location,
                                                         Map<String, ProcessorParameterValue> parameters,
                                                         InterceptionEvent event, InterceptionAction action) {
        InternalEvent internalEvent = ((DefaultInterceptionEvent) event).resolve();
        CompletableFuture<InterceptionEvent> completableFuture = new CompletableFuture<>();
        completableFuture.completeExceptionally(new MessagingException(createStaticMessage("Some Error"), internalEvent,
                                                                       new RuntimeException("Some Error"), mockedComponent));
        return completableFuture;
      }

      // This is done for using mockito spied object in the case of default methods.
      @Override
      public void before(ComponentLocation location, Map<String, ProcessorParameterValue> parameters, InterceptionEvent event) {
        ProcessorInterceptor.super.before(location, parameters, event);
      }

      // This is done for using mockito spied object in the case of default methods.
      @Override
      public void after(ComponentLocation location, InterceptionEvent event, Optional<Throwable> thrown) {
        ProcessorInterceptor.super.after(location, event, thrown);
      }
    });
  }

  private void verifyParametersResolvedAndDisposed(final VerificationMode times) {
    if (processor instanceof OperationProcessorInApp) {
      verify((OperationProcessorInApp) processor, times).resolveParameters(any(), any());
      verify((OperationProcessorInApp) processor, times).disposeResolvedParameters(any());
    }
  }

  private void startFlowWithInterceptors(ProcessorInterceptor... interceptors) throws Exception {
    processorInterceptiorManager.setInterceptorFactories(of(asList(interceptors).stream()
        .map((Function<ProcessorInterceptor, ProcessorInterceptorFactory>) interceptionHandler -> () -> interceptionHandler)
        .collect(toList())));

    flow.initialise();
    flow.start();
  }

  private void startFlowWithInterceptorFactories(ProcessorInterceptorFactory... interceptorFactories) throws Exception {
    processorInterceptiorManager.setInterceptorFactories(of(asList(interceptorFactories)));

    flow.initialise();
    flow.start();
  }

  private static class ProcessorInApp extends AbstractComponent implements Processor {

    private final boolean useMockInterceptor;

    public ProcessorInApp(boolean useMockInterceptor) {
      this.useMockInterceptor = useMockInterceptor;

      setAnnotations(ImmutableMap.<QName, Object>builder()
          .put(ANNOTATION_PARAMETERS, singletonMap("param", "#[payload]"))
          .put(LOCATION_KEY, buildLocation("test:processor"))
          .build());
    }

    @Override
    public CoreEvent process(CoreEvent event) throws MuleException {
      return event;
    }

    @Override
    public Publisher<CoreEvent> apply(Publisher<CoreEvent> publisher) {
      return from(publisher)
          .handle(nullSafeMap(checkedFunction(this::process)))
          .contextWrite(ctx -> {
            if (useMockInterceptor) {
              assertThat(ctx.getOrDefault(WITHIN_PROCESS_TO_APPLY, false), is(true));
            }
            return ctx;
          });
    }

    @Override
    public String toString() {
      return "Processor";
    }
  }

  private static class NonBlockingProcessorInApp extends AbstractComponent implements Processor, Initialisable, Disposable {

    private static final String SELECTOR_EMULATOR_SCHEDULER_NAME = "selector-emulator";

    private Scheduler scheduler;

    public NonBlockingProcessorInApp() {
      setAnnotations(ImmutableMap.<QName, Object>builder()
          .put(ANNOTATION_PARAMETERS, singletonMap("param", "#[payload]"))
          .put(LOCATION_KEY, buildLocation("test:nb-processor"))
          .build());
    }

    @Override
    public void initialise() throws InitialisationException {
      scheduler = muleContext.getSchedulerService()
          .customScheduler(SchedulerConfig.config().withMaxConcurrentTasks(1).withName(SELECTOR_EMULATOR_SCHEDULER_NAME));
    }

    @Override
    public void dispose() {
      scheduler.stop();
    }

    @Override
    public ProcessingType getProcessingType() {
      return CPU_LITE_ASYNC;
    }

    @Override
    public CoreEvent process(CoreEvent event) throws MuleException {
      // Nothing to do.
      return event;
    }

    @Override
    public Publisher<CoreEvent> apply(Publisher<CoreEvent> publisher) {
      return from(publisher)
          // Just call `process` here to keep the assertions in the tests working and not having to introspect reactor stuff
          .doOnNext((CheckedConsumer<CoreEvent>) (event -> process(event)))
          .publishOn(fromExecutorService(scheduler));
    }

    @Override
    public String toString() {
      return "NonBlockingProcessor";
    }
  }

  private static class OperationProcessorInApp extends AbstractComponent
      implements ParametersResolverProcessor<ComponentModel>, Processor {

    private final ExecutionContext executionContext = mock(ExecutionContext.class);

    public OperationProcessorInApp() {
      setAnnotations(ImmutableMap.<QName, Object>builder()
          .put(ANNOTATION_PARAMETERS, singletonMap("param", "#[payload]"))
          .put(LOCATION_KEY, buildLocation("test:operationProcessor"))
          .build());
    }

    @Override
    public CoreEvent process(CoreEvent event) throws MuleException {
      return event;
    }

    @Override
    public void disposeResolvedParameters(ExecutionContext executionContext) {
      assertThat(executionContext, sameInstance(this.executionContext));
    }

    @Override
    public void resolveParameters(CoreEvent.Builder eventBuilder,
                                  BiConsumer<Map<String, Supplier<Object>>, ExecutionContext> afterConfigurer) {
      afterConfigurer.accept(singletonMap("operationParam", new LazyValue<>(new ProcessorParameterValue() {

        @Override
        public String parameterName() {
          return "operationParam";
        }

        @Override
        public String providedValue() {
          return "operationParamValue";
        }

        @Override
        public Object resolveValue() {
          return "operationParamValue";
        }
      })), executionContext);
    }

    @Override
    public String toString() {
      return "OperationProcessor";
    }
  }

  private static DefaultComponentLocation buildLocation(final String componentIdentifier) {
    final TypedComponentIdentifier part =
        builder().identifier(buildFromStringRepresentation(componentIdentifier)).type(OPERATION).build();
    return new DefaultComponentLocation(of("flowName"),
                                        singletonList(new DefaultLocationPart("0", of(part), empty(), OptionalInt.empty(),
                                                                              OptionalInt.empty())));
  }

  private static Map<String, ProcessorParameterValue> mapArgWithEntry(String key, Object value) {
    return mapArgWithEntry(key, new ProcessorParameterValueMatcher(equalTo(value)));
  }

  private static Map<String, ProcessorParameterValue> mapArgWithErrorEntry(String key, Matcher<Throwable> errorMatcher) {
    return mapArgWithEntry(key, new ProcessorParameterValueErrorMatcher(errorMatcher));
  }

  private static Map<String, ProcessorParameterValue> mapArgWithEntry(String key, Matcher<ProcessorParameterValue> valueMatcher) {
    return (Map<String, ProcessorParameterValue>) argThat(hasEntry(equalTo(key), valueMatcher));
  }

  private static final class ProcessorParameterValueMatcher extends TypeSafeMatcher<ProcessorParameterValue> {

    private final Matcher<Object> resolvedValueMatcher;
    private Throwable thrown;

    public ProcessorParameterValueMatcher(Matcher<Object> resolvedValueMatcher) {
      this.resolvedValueMatcher = resolvedValueMatcher;
    }

    @Override
    public void describeTo(Description description) {
      if (thrown != null) {
        description.appendText("but resolvedValue() was ");
        resolvedValueMatcher.describeTo(description);
      } else {
        description.appendText("but resolvedValue() threw ");
        description.appendValue(thrown);
      }
    }

    @Override
    protected boolean matchesSafely(ProcessorParameterValue item) {
      try {
        return resolvedValueMatcher.matches(item.resolveValue());
      } catch (Throwable e) {
        thrown = e;
        return false;
      }
    }

  }

  private static final class ProcessorParameterValueErrorMatcher extends TypeSafeMatcher<ProcessorParameterValue> {

    private final Matcher<Throwable> resolutionErrorMatcher;

    public ProcessorParameterValueErrorMatcher(Matcher<Throwable> resolutionErrorMatcher) {
      this.resolutionErrorMatcher = resolutionErrorMatcher;
    }

    @Override
    public void describeTo(Description description) {
      resolutionErrorMatcher.describeTo(description);
    }

    @Override
    protected boolean matchesSafely(ProcessorParameterValue item) {
      try {
        item.resolveValue();
        return false;
      } catch (Throwable t) {
        return resolutionErrorMatcher.matches(t);
      }
    }

  }

  private static final class EventPayloadMatcher extends TypeSafeMatcher<CoreEvent> {

    private final Matcher<Object> payloadMatcher;

    public EventPayloadMatcher(Matcher<Object> payloadMatcher) {
      this.payloadMatcher = payloadMatcher;
    }

    @Override
    public void describeTo(Description description) {
      payloadMatcher.describeTo(description);
    }

    @Override
    protected boolean matchesSafely(CoreEvent item) {
      return payloadMatcher.matches(item.getMessage().getPayload().getValue());
    }

  }

  private static EventPayloadMatcher hasPayloadValue(Object expectedPayload) {
    return new EventPayloadMatcher(is(expectedPayload));
  }

  private static final class InterceptionPayloadMatcher extends TypeSafeMatcher<InterceptionEvent> {

    private final Matcher<Object> payloadMatcher;

    public InterceptionPayloadMatcher(Matcher<Object> payloadMatcher) {
      this.payloadMatcher = payloadMatcher;
    }

    @Override
    public void describeTo(Description description) {
      payloadMatcher.describeTo(description);
    }

    @Override
    protected boolean matchesSafely(InterceptionEvent item) {
      return payloadMatcher.matches(item.getMessage().getPayload().getValue());
    }

  }

  private static InterceptionPayloadMatcher interceptionHasPayloadValue(Object expectedPayload) {
    return new InterceptionPayloadMatcher(is(expectedPayload));
  }

  private class TestProcessorInterceptor implements ProcessorInterceptor {

    private final String name;

    public TestProcessorInterceptor(String name) {
      this.name = name;
    }

    @Override
    public String toString() {
      return "TestProcessorInterceptor: " + name;
    }

    // This is done for using mockito spied object in the case of default methods.
    @Override
    public CompletableFuture<InterceptionEvent> around(ComponentLocation location,
                                                       Map<String, ProcessorParameterValue> parameters, InterceptionEvent event,
                                                       InterceptionAction action) {
      return ProcessorInterceptor.super.around(location, parameters, event, action);
    }

    @Override
    public void before(ComponentLocation location, Map<String, ProcessorParameterValue> parameters, InterceptionEvent event) {
      ProcessorInterceptor.super.before(location, parameters, event);
    }

    @Override
    public void after(ComponentLocation location, InterceptionEvent event, Optional<Throwable> thrown) {
      ProcessorInterceptor.super.after(location, event, thrown);
    }
  }

  private static final class OptionalMatcher<T> extends TypeSafeMatcher<Optional<T>> {

    private final Matcher<?> matcher;

    public OptionalMatcher(Matcher<?> valueMatcher) {
      this.matcher = valueMatcher;
    }

    public void describeTo(Description description) {
      description.appendText("optional with value ");
      description.appendDescriptionOf(this.matcher);
    }

    protected boolean matchesSafely(Optional<T> item) {
      return item.isPresent() && this.matcher.matches(item.get());
    }

    protected void describeMismatchSafely(Optional<T> item, Description description) {
      description.appendText("optional ");
      if (!item.isPresent()) {
        description.appendText("value is not present");
      } else {
        this.matcher.describeMismatch(item.get(), description);
      }
    }
  }

  private static <T> Matcher<Optional<T>> optionalWithValue(Matcher<?> matcher) {
    return new OptionalMatcher<>(matcher);
  }
}
