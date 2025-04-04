/*
 * Copyright 2023 Salesforce, Inc. All rights reserved.
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.core.internal.routing;

import static org.mule.runtime.api.config.MuleRuntimeFeature.RETHROW_EXCEPTIONS_IN_IDEMPOTENT_MESSAGE_VALIDATOR;
import static org.mule.runtime.api.el.BindingContextUtils.CORRELATION_ID;
import static org.mule.runtime.api.i18n.I18nMessageFactory.createStaticMessage;
import static org.mule.runtime.api.metadata.DataType.STRING;
import static org.mule.runtime.core.api.config.MuleProperties.OBJECT_STORE_MANAGER;
import static org.mule.runtime.core.api.el.ExpressionManager.DEFAULT_EXPRESSION_POSTFIX;
import static org.mule.runtime.core.api.el.ExpressionManager.DEFAULT_EXPRESSION_PREFIX;
import static org.mule.runtime.core.api.lifecycle.LifecycleUtils.disposeIfNeeded;
import static org.mule.runtime.core.api.lifecycle.LifecycleUtils.initialiseIfNeeded;
import static org.mule.runtime.core.api.lifecycle.LifecycleUtils.startIfNeeded;
import static org.mule.runtime.core.api.lifecycle.LifecycleUtils.stopIfNeeded;
import static org.mule.runtime.core.internal.el.ExpressionLanguageUtils.compile;

import static java.lang.String.format;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;

import static org.slf4j.LoggerFactory.getLogger;

import org.mule.runtime.api.component.AbstractComponent;
import org.mule.runtime.api.config.FeatureFlaggingService;
import org.mule.runtime.api.el.BindingContext;
import org.mule.runtime.api.el.CompiledExpression;
import org.mule.runtime.api.el.ExpressionLanguageSession;
import org.mule.runtime.api.exception.MuleException;
import org.mule.runtime.api.lifecycle.InitialisationException;
import org.mule.runtime.api.lifecycle.Lifecycle;
import org.mule.runtime.api.store.ObjectAlreadyExistsException;
import org.mule.runtime.api.store.ObjectStore;
import org.mule.runtime.api.store.ObjectStoreException;
import org.mule.runtime.api.store.ObjectStoreManager;
import org.mule.runtime.api.store.ObjectStoreNotAvailableException;
import org.mule.runtime.api.store.ObjectStoreSettings;
import org.mule.runtime.core.api.MuleContext;
import org.mule.runtime.core.api.context.MuleContextAware;
import org.mule.runtime.core.api.event.CoreEvent;
import org.mule.runtime.core.api.expression.ExpressionRuntimeException;
import org.mule.runtime.core.api.processor.Processor;
import org.mule.runtime.core.internal.routing.split.DuplicateMessageException;

import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.inject.Named;

import org.slf4j.Logger;

/**
 * <code>IdempotentMessageValidator</code> ensures that only unique messages are passed on. It does this by checking the unique ID
 * of the incoming message. To compute the unique ID an expression or DW script can be used, even Crypto functions from DW capable
 * of computing hashes(SHA,MD5) from the data. Note that the underlying endpoint must support unique message IDs for this to work,
 * otherwise a <code>UniqueIdNotSupportedException</code> is thrown.<br>
 * <p>
 * <b>EIP Reference:</b> <a href="http://www.eaipatterns.com/IdempotentReceiver.html">
 * http://www.eaipatterns.com/IdempotentReceiver.html</a>
 */
public class IdempotentMessageValidator extends AbstractComponent
    implements Processor, MuleContextAware, Lifecycle {

  private static final Logger LOGGER = getLogger(IdempotentMessageValidator.class);

  protected MuleContext muleContext;

  @Inject
  @Named(OBJECT_STORE_MANAGER)
  private ObjectStoreManager objectStoreManager;

  @Inject
  private FeatureFlaggingService featureFlaggingService;

  protected volatile ObjectStore<String> store;
  protected ObjectStore<String> privateStore;
  protected String storePrefix;

  protected String idExpression = format("%s%s%s", DEFAULT_EXPRESSION_PREFIX, CORRELATION_ID, DEFAULT_EXPRESSION_POSTFIX);
  protected String valueExpression = format("%s%s%s", DEFAULT_EXPRESSION_PREFIX, CORRELATION_ID, DEFAULT_EXPRESSION_POSTFIX);

  private CompiledExpression compiledIdExpression;
  private CompiledExpression compiledValueExpression;
  private boolean rethrowEnabled;

  @Override
  public void setMuleContext(MuleContext context) {
    this.muleContext = context;
  }

  public void setRethrowEnabled(boolean rethrowEnabled) {
    this.rethrowEnabled = rethrowEnabled;
  }

  @Override
  public void initialise() throws InitialisationException {
    if (storePrefix == null) {
      storePrefix =
          format("%s.%s.%s.%s", muleContext.getConfiguration().getId(), getLocation().getRootContainerName(),
                 this.getClass().getName(), UUID.randomUUID());
    }
    setupObjectStore();
    if (featureFlaggingService.isEnabled(RETHROW_EXCEPTIONS_IN_IDEMPOTENT_MESSAGE_VALIDATOR)) {
      setRethrowEnabled(true);
    }
    compiledIdExpression = compile(idExpression, muleContext.getExpressionManager());
    compiledValueExpression = compile(valueExpression, muleContext.getExpressionManager());
  }

  private void setupObjectStore() throws InitialisationException {
    // Check if OS was properly configured
    if (store != null && privateStore != null) {
      throw new InitialisationException(
                                        createStaticMessage("Ambiguous definition of object store, both reference and private were configured"),
                                        this);
    }
    if (store == null) {
      if (privateStore == null) { // If no object store was defined, create one
        this.store = createMessageIdStore();
      } else { // If object store was defined privately
        this.store = privateStore;
      }
    }
    initialiseIfNeeded(store, true, muleContext);
  }

  @Override
  public void start() throws MuleException {
    startIfNeeded(store);
  }

  @Override
  public void stop() throws MuleException {
    stopIfNeeded(store);
  }

  @Override
  public void dispose() {
    disposeIfNeeded(store, LOGGER);
  }

  protected ObjectStore<String> createMessageIdStore() throws InitialisationException {
    return objectStoreManager.createObjectStore(storePrefix, ObjectStoreSettings.builder()
        .persistent(false)
        .entryTtl(MINUTES.toMillis(5))
        .expirationInterval(SECONDS.toMillis(6))
        .build());
  }

  protected String getValueForEvent(ExpressionLanguageSession session) {
    return evaluateString(session, compiledValueExpression);
  }

  protected String getIdForEvent(ExpressionLanguageSession session) {
    return evaluateString(session, compiledIdExpression);
  }

  private String evaluateString(ExpressionLanguageSession session, CompiledExpression expression) {
    return (String) session.evaluate(expression, STRING).getValue();
  }

  public String getIdExpression() {
    return idExpression;
  }

  public void setIdExpression(String idExpression) {
    this.idExpression = idExpression;
  }

  public ObjectStore<String> getObjectStore() {
    return store;
  }

  public void setObjectStore(ObjectStore<String> store) {
    this.store = store;
  }

  private boolean accept(CoreEvent event) {
    BindingContext bindingContext = event.asBindingContext();
    try (ExpressionLanguageSession session = muleContext.getExpressionManager().openSession(bindingContext)) {
      String id = getIdForEvent(session);
      String value = getValueForEvent(session);

      if (event != null && isNewMessage(event, id)) {
        try {
          store.store(id, value);
          return true;
        } catch (ObjectAlreadyExistsException ex) {
          return false;
        } catch (ObjectStoreNotAvailableException e) {
          LOGGER.error("ObjectStore not available: " + e.getMessage());
          return false;
        } catch (ObjectStoreException e) {
          LOGGER.warn("ObjectStore exception: " + e.getMessage());
          return false;
        }
      } else {
        return false;
      }
    } catch (ExpressionRuntimeException e) {
      throw e;
    } catch (Exception e) {
      LOGGER.warn("Could not retrieve Id or Value for event: " + e.getMessage());
      return false;
    }
  }

  private boolean acceptWithRethrowExceptionsInIdempotentMessageValidator(CoreEvent event) throws MuleException {
    BindingContext bindingContext = event.asBindingContext();
    try (ExpressionLanguageSession session = muleContext.getExpressionManager().openSession(bindingContext)) {
      String id = getIdForEvent(session);
      String value = getValueForEvent(session);

      if (event != null && isNewMessage(event, id)) {
        store.store(id, value);
        return true;
      } else {
        return false;
      }
    } catch (ObjectAlreadyExistsException ex) {
      return false;
    } catch (Exception e) {
      LOGGER.warn("Could not retrieve Id or Value for event: " + e.getMessage());
      throw e;
    }
  }

  private boolean rethrowIfFeatureFlagEnabled(MuleException e) throws MuleException {
    if (rethrowEnabled) {
      throw e;
    }
    return false;
  }

  @Override
  public final CoreEvent process(CoreEvent event) throws MuleException {
    if (rethrowEnabled ? acceptWithRethrowExceptionsInIdempotentMessageValidator(event) : accept(event)) {
      return event;
    } else {
      throw new DuplicateMessageException();
    }
  }

  protected boolean isNewMessage(CoreEvent event, String id) throws MuleException {
    try {
      if (store == null) {
        synchronized (this) {
          initialise();
        }
      }
      return !store.contains(id);
    } catch (MuleException e) {
      LOGGER.error("Exception attempting to determine idempotency of incoming message for " + getLocation().getRootContainerName()
          + " from the connector "
          + event.getContext().getOriginatingLocation().getComponentIdentifier().getIdentifier().getNamespace(), e);
      return rethrowIfFeatureFlagEnabled(e);
    }
  }

  public String getValueExpression() {
    return valueExpression;
  }

  public void setValueExpression(String valueExpression) {
    this.valueExpression = valueExpression;
  }

  public void setStorePrefix(String storePrefix) {
    this.storePrefix = storePrefix;
  }

  public void setPrivateObjectStore(ObjectStore<String> privateStore) {
    this.privateStore = privateStore;
  }
}
