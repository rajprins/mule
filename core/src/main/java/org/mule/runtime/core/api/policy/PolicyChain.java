/*
 * Copyright 2023 Salesforce, Inc. All rights reserved.
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.core.api.policy;

import static org.mule.runtime.api.notification.PolicyNotification.PROCESS_END;
import static org.mule.runtime.api.notification.PolicyNotification.PROCESS_START;
import static org.mule.runtime.core.api.lifecycle.LifecycleUtils.initialiseIfNeeded;
import static org.mule.runtime.core.privileged.processor.MessageProcessors.buildNewChainWithListOfProcessors;
import static org.mule.runtime.core.privileged.processor.MessageProcessors.processToApply;

import static java.util.Optional.empty;
import static java.util.Optional.of;
import static java.util.Optional.ofNullable;

import static reactor.core.publisher.Flux.from;

import org.mule.api.annotation.NoExtend;
import org.mule.runtime.api.component.AbstractComponent;
import org.mule.runtime.api.exception.MuleException;
import org.mule.runtime.api.lifecycle.Disposable;
import org.mule.runtime.api.lifecycle.Initialisable;
import org.mule.runtime.api.lifecycle.InitialisationException;
import org.mule.runtime.api.lifecycle.Startable;
import org.mule.runtime.api.lifecycle.Stoppable;
import org.mule.runtime.core.api.MuleContext;
import org.mule.runtime.core.api.context.notification.FlowStackElement;
import org.mule.runtime.core.api.context.notification.ServerNotificationHandler;
import org.mule.runtime.core.api.event.CoreEvent;
import org.mule.runtime.core.api.exception.BaseExceptionHandler;
import org.mule.runtime.core.api.processor.Processor;
import org.mule.runtime.core.api.processor.strategy.ProcessingStrategy;
import org.mule.runtime.core.internal.policy.PolicyNotificationHelper;
import org.mule.runtime.core.internal.rx.FluxSinkRecorder;
import org.mule.runtime.core.privileged.event.DefaultFlowCallStack;
import org.mule.runtime.core.privileged.exception.MessagingException;
import org.mule.runtime.core.privileged.processor.chain.MessageProcessorChain;
import org.mule.runtime.tracer.api.component.ComponentTracerFactory;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import jakarta.inject.Inject;

import org.reactivestreams.Publisher;

/**
 * Policy chain for handling the message processor associated to a policy.
 *
 * @since 4.0
 */
@NoExtend
public class PolicyChain extends AbstractComponent
    implements Initialisable, Startable, Stoppable, Disposable, Processor {

  @Inject
  private MuleContext muleContext;

  @Inject
  private ServerNotificationHandler notificationManager;

  @Inject
  private ComponentTracerFactory componentTracerFactory;

  private ProcessingStrategy processingStrategy;

  private List<Processor> processors;
  private MessageProcessorChain processorChain;

  private String flowStackEntryName;

  private PolicyNotificationHelper notificationHelper;

  private boolean propagateMessageTransformations;

  private Optional<Consumer<Exception>> onError = empty();

  public void setProcessors(List<Processor> processors) {
    this.processors = processors;
  }

  @Override
  public final void initialise() throws InitialisationException {
    processorChain =
        buildNewChainWithListOfProcessors(ofNullable(processingStrategy), processors, policyChainErrorHandler(),
                                          componentTracerFactory.fromComponent(this));

    initialiseIfNeeded(processorChain, muleContext);

    notificationHelper = new PolicyNotificationHelper(notificationManager, muleContext.getConfiguration().getId(), this);

    flowStackEntryName = getLocation().getLocation() + "[before next]";
  }

  public ProcessingStrategy getProcessingStrategy() {
    return processingStrategy;
  }

  public void setProcessingStrategy(ProcessingStrategy processingStrategy) {
    this.processingStrategy = processingStrategy;
  }

  // Keep an active sink while the policy chain is active to avoid premature stopping of the Schedulers in the PS when the policy
  // executes within a Mono
  // (ref: AbstractReactorStreamProcessingStrategy#stopSchedulersIfNeeded)
  private FluxSinkRecorder chainActiveSink;

  @Override
  public void start() throws MuleException {
    chainActiveSink = new FluxSinkRecorder();
    processingStrategy.registerInternalSink(chainActiveSink.flux(), flowStackEntryName);

    if (processorChain != null) {
      processorChain.start();
    }
  }

  @Override
  public void dispose() {
    if (processorChain != null) {
      processorChain.dispose();
    }
  }

  @Override
  public void stop() throws MuleException {
    if (processorChain != null) {
      processorChain.stop();
    }

    chainActiveSink.complete();
  }

  @Override
  public CoreEvent process(CoreEvent event) throws MuleException {
    return processToApply(event, processorChain);
  }

  @Override
  public Publisher<CoreEvent> apply(Publisher<CoreEvent> publisher) {
    return from(publisher)
        .doOnNext(pushBeforeNextFlowStackElement()
            .andThen(notificationHelper.notification(PROCESS_START)))
        .transform(processorChain)
        .doOnNext(popFlowFlowStackElement()
            .andThen(e -> notificationHelper.fireNotification(e, null,
                                                              PROCESS_END)));
  }

  public MuleContext getMuleContext() {
    return muleContext;
  }

  private BaseExceptionHandler policyChainErrorHandler() {
    return new BaseExceptionHandler() {

      @Override
      public void onError(Exception exception) {
        MessagingException t = (MessagingException) exception;
        notificationHelper.fireNotification(t.getEvent(), t, PROCESS_END);
        popFlowFlowStackElement().accept(t.getEvent());
        onError.ifPresent(onError -> onError.accept(t));
      }

      @Override
      public String toString() {
        return PolicyChain.class.getSimpleName() + ".errorHandler @ " + getLocation().getLocation();
      }
    };
  }

  private Consumer<CoreEvent> pushBeforeNextFlowStackElement() {
    return event -> ((DefaultFlowCallStack) event.getFlowCallStack())
        .push(new FlowStackElement(flowStackEntryName, getIdentifier(), null));
  }

  private Consumer<CoreEvent> popFlowFlowStackElement() {
    return event -> ((DefaultFlowCallStack) event.getFlowCallStack()).pop();
  }

  public boolean isPropagateMessageTransformations() {
    return propagateMessageTransformations;
  }

  public void setPropagateMessageTransformations(boolean propagateMessageTransformations) {
    this.propagateMessageTransformations = propagateMessageTransformations;
  }

  public PolicyChain onChainError(Consumer<Exception> onError) {
    this.onError = of(onError);
    return this;
  }
}
