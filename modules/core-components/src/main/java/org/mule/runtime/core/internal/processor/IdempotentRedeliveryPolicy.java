/*
 * Copyright 2023 Salesforce, Inc. All rights reserved.
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.core.internal.processor;

import static org.mule.runtime.api.i18n.I18nMessageFactory.createStaticMessage;
import static org.mule.runtime.api.metadata.DataType.STRING;
import static org.mule.runtime.core.api.config.MuleProperties.OBJECT_STORE_MANAGER;
import static org.mule.runtime.core.api.config.i18n.CoreMessages.initialisationFailure;
import static org.mule.runtime.core.api.lifecycle.LifecycleUtils.initialiseIfNeeded;
import static org.mule.runtime.core.api.lifecycle.LifecycleUtils.startIfNeeded;
import static org.mule.runtime.core.api.lifecycle.LifecycleUtils.stopIfNeeded;
import static org.mule.runtime.core.api.processor.ReactiveProcessor.ProcessingType.BLOCKING;
import static org.mule.runtime.core.internal.el.ExpressionLanguageUtils.compile;
import static org.mule.runtime.core.privileged.processor.MessageProcessors.processToApply;

import static java.lang.String.format;
import static java.lang.System.lineSeparator;
import static java.util.Optional.empty;
import static java.util.Optional.of;

import static org.slf4j.LoggerFactory.getLogger;

import org.mule.api.annotation.NoExtend;
import org.mule.runtime.api.el.CompiledExpression;
import org.mule.runtime.api.el.ExpressionLanguageSession;
import org.mule.runtime.api.exception.MuleException;
import org.mule.runtime.api.lifecycle.InitialisationException;
import org.mule.runtime.api.lock.LockFactory;
import org.mule.runtime.api.message.Error;
import org.mule.runtime.api.store.ObjectStore;
import org.mule.runtime.api.store.ObjectStoreException;
import org.mule.runtime.api.store.ObjectStoreManager;
import org.mule.runtime.api.store.ObjectStoreSettings;
import org.mule.runtime.core.api.el.ExpressionManager;
import org.mule.runtime.core.api.event.CoreEvent;
import org.mule.runtime.core.api.execution.ExceptionContextProvider;
import org.mule.runtime.core.api.expression.ExpressionRuntimeException;
import org.mule.runtime.core.api.transaction.TransactionCoordination;
import org.mule.runtime.core.internal.exception.MessagingExceptionResolver;
import org.mule.runtime.core.privileged.event.BaseEventContext;
import org.mule.runtime.core.privileged.exception.ErrorTypeLocator;
import org.mule.runtime.core.privileged.exception.MessageRedeliveredException;
import org.mule.runtime.core.privileged.exception.MessagingException;

import java.io.Serializable;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;

import org.slf4j.Logger;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import reactor.core.publisher.Mono;

/**
 * Implement a retry policy for Mule. This is similar to JMS retry policies that will redeliver a message a maximum number of
 * times. If this maximum is exceeded, fails with an exception.
 */
@NoExtend
public class IdempotentRedeliveryPolicy extends AbstractRedeliveryPolicy {

  private static final String EXPRESSION_RUNTIME_EXCEPTION_ERROR_MSG =
      "The message cannot be processed because the digest could not be generated. Either make the payload serializable or use an expression.";
  private static final String BLANK_MESSAGE_ID_ERROR_MSG =
      "The message cannot be processed because the message ID is null or blank.";

  public static final String SECURE_HASH_EXPR_FORMAT = "" +
      "%%dw 2.0" + lineSeparator() +
      "output text/plain" + lineSeparator() +
      "import dw::Crypto" + lineSeparator() +
      "---" + lineSeparator() +
      "if ((payload.^mimeType startsWith 'application/java') and payload.^class != 'java.lang.String') " +
      "java!java::util::Objects::hashCode(payload) " +
      "else " +
      "Crypto::hashWith(payload.^raw, '%s')";

  private static final Logger LOGGER = getLogger(IdempotentRedeliveryPolicy.class);

  private final MessagingExceptionResolver exceptionResolver = new MessagingExceptionResolver(this);

  @Inject
  private ErrorTypeLocator errorTypeLocator;

  @Inject
  private Collection<ExceptionContextProvider> exceptionContextProviders;

  private LockFactory lockFactory;
  private ObjectStoreManager objectStoreManager;
  private ExpressionManager expressionManager;

  private boolean useSecureHash;
  private String messageDigestAlgorithm;
  private String idExpression;
  private CompiledExpression compiledIdExpresion;
  private ObjectStore<RedeliveryCounter> store;
  private ObjectStore<RedeliveryCounter> privateStore;
  private String idrId;
  private boolean isOwnedObjectStore;


  /**
   * Holds information about the redelivery failures.
   *
   * @since 4.0
   */
  public static class RedeliveryCounter implements Serializable {

    private static final long serialVersionUID = 5513487261745816555L;

    private final AtomicInteger counter;
    private final List<Error> errors;

    public RedeliveryCounter(AtomicInteger counter, List<Error> errors) {
      this.counter = counter;
      this.errors = errors;
    }

    public AtomicInteger getCounter() {
      return counter;
    }

    public List<Error> getErrors() {
      return errors;
    }
  }

  @Override
  public void initialise() throws InitialisationException {
    super.initialise();
    initialiseExpression();
    initialiseStore();
  }

  private void initialiseExpression() throws InitialisationException {
    if (useSecureHash && idExpression != null) {
      useSecureHash = false;
      if (LOGGER.isWarnEnabled()) {
        LOGGER.warn("Disabling useSecureHash in idempotent-redelivery-policy since an idExpression has been configured");
      }
    }
    if (!useSecureHash && messageDigestAlgorithm != null) {
      throw new InitialisationException(
                                        initialisationFailure(format("The message digest algorithm '%s' was specified when a secure hash will not be used",
                                                                     messageDigestAlgorithm)),
                                        this);
    }
    if (!useSecureHash && idExpression == null) {
      throw new InitialisationException(initialisationFailure("No method for identifying messages was specified"),
                                        this);
    }
    if (useSecureHash) {
      if (messageDigestAlgorithm == null) {
        messageDigestAlgorithm = "SHA-256";
      }

      idExpression = format(SECURE_HASH_EXPR_FORMAT, messageDigestAlgorithm);
    }

    if (idExpression != null) {
      compiledIdExpresion = compile(idExpression, expressionManager);
    }
  }

  private void initialiseStore() throws InitialisationException {
    idrId = format("%s-%s-%s", muleContext.getConfiguration().getId(), getLocation().getRootContainerName(), "idr");

    isOwnedObjectStore = privateStore != null || store == null;

    if (store != null && privateStore != null) {
      throw new InitialisationException(
                                        createStaticMessage("Ambiguous definition of object store, both reference and private were configured"),
                                        this);
    }

    if (store == null) {
      // If no object store was defined, create one
      if (privateStore == null) {
        this.store = createInternalObjectStore();
      } else {
        // If object store was defined privately
        this.store = privateStore;
      }
    }

    if (isOwnedObjectStore) {
      initialiseIfNeeded(store, true, muleContext);
    }
  }

  private ObjectStore<RedeliveryCounter> createInternalObjectStore() {
    return objectStoreManager.createObjectStore(getObjectStoreName(), ObjectStoreSettings.builder().persistent(false)
        .entryTtl((long) 60 * 5 * 1000).expirationInterval(6000L).build());
  }

  @Override
  public void dispose() {
    if (isOwnedObjectStore) {
      disposeStore();
    }
    super.dispose();
  }

  private void disposeStore() {
    if (store != null) {
      try {
        store.close();
      } catch (ObjectStoreException e) {
        LOGGER.warn("error closing object store: " + e.getMessage(), e);
      }
      try {
        objectStoreManager.disposeStore(getObjectStoreName());
      } catch (ObjectStoreException e) {
        LOGGER.warn("error disposing object store: " + e.getMessage(), e);
      }
      store = null;
    }
  }

  @Override
  public void start() throws MuleException {
    super.start();
    if (isOwnedObjectStore) {
      startIfNeeded(store);
    }
  }

  @Override
  public void stop() throws MuleException {
    if (isOwnedObjectStore) {
      stopIfNeeded(store);
    }
    super.stop();
  }

  @Override
  public CoreEvent process(CoreEvent event) throws MuleException {
    Optional<Exception> exceptionSeen = empty();

    String messageId = null;
    try {
      messageId = getIdForEvent(event);
    } catch (ExpressionRuntimeException e) {
      if (LOGGER.isDebugEnabled()) {
        // Logs the details of the error.
        LOGGER.warn(EXPRESSION_RUNTIME_EXCEPTION_ERROR_MSG, e);
      }

      // The current transaction needs to be committed, so it's not rolled back, what would cause an infinite loop.
      TransactionCoordination.getInstance().commitCurrentTransaction();

      throw new ExpressionRuntimeException(createStaticMessage(EXPRESSION_RUNTIME_EXCEPTION_ERROR_MSG), e);
    } catch (Exception ex) {
      exceptionSeen = of(ex);
    }

    if (messageId == null && !exceptionSeen.isPresent()) {
      // The current transaction needs to be committed, so it's not rolled back, what would cause an infinite loop.
      TransactionCoordination.getInstance().commitCurrentTransaction();

      throw new ExpressionRuntimeException(createStaticMessage(BLANK_MESSAGE_ID_ERROR_MSG));
    }

    Lock lock = lockFactory.createLock(idrId + "-" + messageId);
    lock.lock();
    try {

      RedeliveryCounter counter = findCounter(messageId);
      if (exceptionSeen.isPresent()) {
        throw new MessageRedeliveredException(messageId, counter.counter.get(), maxRedeliveryCount, exceptionSeen.get());
      } else if (counter != null && counter.counter.get() > maxRedeliveryCount) {
        throw new MessageRedeliveredException(messageId, counter.errors, counter.counter.get(), maxRedeliveryCount);
      }

      try {
        CoreEvent returnEvent =
            processToApply(event, nestedChain, false, Mono.from(((BaseEventContext) event.getContext()).getResponsePublisher()));
        counter = findCounter(messageId);
        if (counter != null) {
          resetCounter(messageId);
        }
        return returnEvent;
      } catch (MessagingException ex) {
        incrementCounter(messageId, ex);
        throw ex;
      } catch (Exception ex) {
        incrementCounter(messageId, createMessagingException(event, ex));
        throw ex;
      }
    } finally {
      lock.unlock();
    }
  }

  @Override
  public ProcessingType getProcessingType() {
    // This is because the execution of the flow happens with a lock taken, and if the thread is changed because of a non-blocking
    // execution, the lock cannot be released (reentrant locks can only be released in the same thread that acquired it).
    return BLOCKING;
  }

  private MessagingException createMessagingException(CoreEvent event, Throwable cause) {
    return exceptionResolver.resolve(new MessagingException(event, cause, this), errorTypeLocator, exceptionContextProviders);
  }

  private void resetCounter(String messageId) throws ObjectStoreException {
    store.remove(messageId);
    store.store(messageId, new RedeliveryCounter(new AtomicInteger(), new LinkedList<>()));
  }

  public RedeliveryCounter findCounter(String messageId) throws ObjectStoreException {
    boolean counterExists = store.contains(messageId);
    if (counterExists) {
      return store.retrieve(messageId);
    }
    return null;
  }

  private RedeliveryCounter incrementCounter(String messageId, MessagingException ex) throws ObjectStoreException {
    RedeliveryCounter counter = findCounter(messageId);
    if (counter == null) {
      counter = new RedeliveryCounter(new AtomicInteger(), new LinkedList<>());
    } else {
      store.remove(messageId);
    }
    counter.counter.incrementAndGet();
    ex.getEvent().getError().ifPresent(counter.errors::add);
    store.store(messageId, counter);
    return counter;
  }

  private String getIdForEvent(CoreEvent event) {
    try (ExpressionLanguageSession session = expressionManager.openSession(event.asBindingContext())) {
      return (String) session.evaluate(compiledIdExpresion, STRING).getValue();
    }
  }

  public boolean isUseSecureHash() {
    return useSecureHash;
  }

  public void setUseSecureHash(boolean useSecureHash) {
    this.useSecureHash = useSecureHash;
  }

  public String getMessageDigestAlgorithm() {
    return messageDigestAlgorithm;
  }

  public void setMessageDigestAlgorithm(String messageDigestAlgorithm) {
    this.messageDigestAlgorithm = messageDigestAlgorithm;
  }

  public String getIdExpression() {
    return idExpression;
  }

  public void setIdExpression(String idExpression) {
    this.idExpression = idExpression;
  }

  public void setObjectStore(ObjectStore<RedeliveryCounter> store) {
    this.store = store;
  }

  public void setPrivateObjectStore(ObjectStore<RedeliveryCounter> store) {
    this.privateStore = store;
  }

  @Inject
  public void setLockFactory(LockFactory lockFactory) {
    this.lockFactory = lockFactory;
  }

  @Inject
  @Named(OBJECT_STORE_MANAGER)
  public void setObjectStoreManager(ObjectStoreManager objectStoreManager) {
    this.objectStoreManager = objectStoreManager;
  }

  @Inject
  public void setExpressionManager(ExpressionManager expressionManager) {
    this.expressionManager = expressionManager;
  }

  private String getObjectStoreName() {
    return getLocation().getRootContainerName() + "." + getClass().getName();
  }

}

