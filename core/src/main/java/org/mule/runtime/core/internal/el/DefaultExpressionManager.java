/*
 * Copyright 2023 Salesforce, Inc. All rights reserved.
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.core.internal.el;

import static org.mule.runtime.api.el.BindingContextUtils.NULL_BINDING_CONTEXT;
import static org.mule.runtime.api.el.ValidationResult.failure;
import static org.mule.runtime.api.el.ValidationResult.success;
import static org.mule.runtime.api.i18n.I18nMessageFactory.createStaticMessage;
import static org.mule.runtime.api.metadata.DataType.STRING;
import static org.mule.runtime.core.api.lifecycle.LifecycleUtils.disposeIfNeeded;
import static org.mule.runtime.core.api.lifecycle.LifecycleUtils.initialiseIfNeeded;
import static org.mule.runtime.core.api.util.ClassUtils.isInstance;
import static org.mule.runtime.core.api.util.StreamingUtils.updateTypedValueForStreaming;

import static java.lang.String.format;
import static java.lang.Thread.currentThread;
import static java.util.Collections.emptyList;

import static org.slf4j.LoggerFactory.getLogger;

import org.mule.metadata.api.model.MetadataType;
import org.mule.metadata.message.api.el.TypeBindings;
import org.mule.runtime.api.component.location.ComponentLocation;
import org.mule.runtime.api.el.BindingContext;
import org.mule.runtime.api.el.CompiledExpression;
import org.mule.runtime.api.el.DefaultValidationResult;
import org.mule.runtime.api.el.ExpressionCompilationException;
import org.mule.runtime.api.el.ExpressionExecutionException;
import org.mule.runtime.api.el.ValidationResult;
import org.mule.runtime.api.el.validation.ConstraintViolation;
import org.mule.runtime.api.el.validation.ScopePhaseValidationMessages;
import org.mule.runtime.api.el.validation.ValidationPhase;
import org.mule.runtime.api.lifecycle.Initialisable;
import org.mule.runtime.api.lifecycle.InitialisationException;
import org.mule.runtime.api.message.Message;
import org.mule.runtime.api.metadata.DataType;
import org.mule.runtime.api.metadata.TypedValue;
import org.mule.runtime.api.util.LazyValue;
import org.mule.runtime.core.api.MuleContext;
import org.mule.runtime.core.api.el.ExpressionManagerSession;
import org.mule.runtime.core.api.el.ExtendedExpressionManager;
import org.mule.runtime.core.api.event.CoreEvent;
import org.mule.runtime.core.api.event.CoreEvent.Builder;
import org.mule.runtime.core.api.expression.ExpressionRuntimeException;
import org.mule.runtime.core.api.streaming.StreamingManager;
import org.mule.runtime.core.api.transformer.TransformerException;
import org.mule.runtime.core.internal.transformer.TransformersRegistry;
import org.mule.runtime.core.internal.util.log.OneTimeWarning;

import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.inject.Inject;

import org.slf4j.Logger;

public class DefaultExpressionManager implements ExtendedExpressionManager, Initialisable {

  private static final Logger LOGGER = getLogger(DefaultExpressionManager.class);

  private final OneTimeWarning parseWarning = new OneTimeWarning(LOGGER,
                                                                 "Expression parsing is deprecated, regular expressions should be used instead.");

  private MuleContext muleContext;
  private TransformersRegistry transformersRegistry;

  private ExtendedExpressionLanguageAdaptor expressionLanguage;
  // Default style parser
  private final TemplateParser parser = TemplateParser.createMuleStyleParser();

  @Override
  public void addGlobalBindings(BindingContext bindingContext) {
    expressionLanguage.addGlobalBindings(bindingContext);
  }

  @Override
  public TypedValue<?> evaluate(String expression) {
    return evaluate(expression, NULL_BINDING_CONTEXT);
  }

  @Override
  public TypedValue<?> evaluate(String expression, CoreEvent event) {
    return evaluate(expression, event, NULL_BINDING_CONTEXT);
  }

  @Override
  public TypedValue<?> evaluate(String expression, BindingContext context) {
    return evaluate(expression, null, null, null, context);
  }

  @Override
  public TypedValue<?> evaluate(String expression, CoreEvent event, BindingContext context) {
    return evaluate(expression, event, CoreEvent.builder(event), null, context);
  }

  @Override
  public TypedValue<?> evaluate(String expression, CoreEvent event, ComponentLocation componentLocation) {
    return evaluate(expression, event, CoreEvent.builder(event), componentLocation, NULL_BINDING_CONTEXT);
  }

  @Override
  public TypedValue<?> evaluate(String expression, CoreEvent event, CoreEvent.Builder eventBuilder,
                                ComponentLocation componentLocation) {
    return evaluate(expression, event, eventBuilder, componentLocation, NULL_BINDING_CONTEXT);
  }

  @Override
  public TypedValue<?> evaluate(String expression, CoreEvent event, ComponentLocation componentLocation,
                                BindingContext context) {
    return evaluate(expression, event, CoreEvent.builder(event), componentLocation, context);
  }

  private TypedValue<?> evaluate(String expression, CoreEvent event, CoreEvent.Builder eventBuilder,
                                 ComponentLocation componentLocation,
                                 BindingContext context) {
    return updateTypedValueForStreaming(expressionLanguage.evaluate(expression, event, eventBuilder, componentLocation, context),
                                        event, getStreamingManager());
  }

  @Override
  public TypedValue<?> evaluate(String expression, DataType outputType) {
    return evaluate(expression, outputType, NULL_BINDING_CONTEXT);
  }

  @Override
  public TypedValue<?> evaluate(String expression, DataType outputType, BindingContext context) {
    return evaluate(expression, outputType, context, null);
  }

  @Override
  public TypedValue<?> evaluateLogExpression(String expression, BindingContext context) throws ExpressionExecutionException {
    return expressionLanguage.evaluateLogExpression(expression, null, null, context);
  }

  @Override
  public TypedValue<?> evaluate(String expression, DataType outputType, BindingContext context, CoreEvent event) {
    return evaluate(expression, outputType, context, event, null, false);
  }

  @Override
  public TypedValue<?> evaluate(String expression, DataType outputType, BindingContext context, CoreEvent event,
                                ComponentLocation componentLocation, boolean failOnNull)
      throws ExpressionRuntimeException {
    return updateTypedValueForStreaming(expressionLanguage.evaluate(expression, outputType, event, componentLocation, context,
                                                                    failOnNull),
                                        event, getStreamingManager());
  }

  private TypedValue<?> transform(TypedValue<?> target, DataType sourceType, DataType outputType) throws TransformerException {
    if (target.getValue() != null && !isInstance(outputType.getType(), target.getValue())) {
      Object result = transformersRegistry.lookupTransformer(sourceType, outputType).transform(target);
      return new TypedValue<>(result, outputType);
    } else {
      return target;
    }
  }

  @Override
  public CompiledExpression compile(String expression, BindingContext context) throws ExpressionCompilationException {
    return expressionLanguage.compile(expression, context);
  }

  @Override
  public boolean evaluateBoolean(String expression, CoreEvent event, ComponentLocation componentLocation)
      throws ExpressionRuntimeException {
    return evaluateBoolean(expression, event, componentLocation, false, false);
  }

  @Override
  public boolean evaluateBoolean(String expression, CoreEvent event, ComponentLocation componentLocation,
                                 boolean nullReturnsTrue,
                                 boolean nonBooleanReturnsTrue)
      throws ExpressionRuntimeException {
    return resolveBoolean(evaluate(expression, DataType.BOOLEAN, NULL_BINDING_CONTEXT, event, componentLocation, false)
        .getValue(), nullReturnsTrue, nonBooleanReturnsTrue, expression);
  }

  @Override
  public boolean evaluateBoolean(String expression, BindingContext bindingCtx, ComponentLocation componentLocation,
                                 boolean nullReturnsTrue, boolean nonBooleanReturnsTrue)
      throws ExpressionRuntimeException {
    return resolveBoolean(evaluate(expression, DataType.BOOLEAN, bindingCtx).getValue(), nullReturnsTrue, nonBooleanReturnsTrue,
                          expression);
  }

  @Override
  public ScopePhaseValidationMessages collectScopePhaseValidationMessages(String script, String nameIdentifier,
                                                                          TypeBindings bindings) {
    return expressionLanguage.collectScopePhaseValidationMessages(script, nameIdentifier, bindings);
  }

  protected static boolean resolveBoolean(Object result, boolean nullReturnsTrue, boolean nonBooleanReturnsTrue,
                                          String expression) {
    if (result == null) {
      return nullReturnsTrue;
    } else {
      Object value = result;
      if (value instanceof Boolean) {
        return (Boolean) value;
      } else if (value instanceof String) {
        if (value.toString().toLowerCase().equalsIgnoreCase("false")) {
          return false;
        } else if (result.toString().toLowerCase().equalsIgnoreCase("true")) {
          return true;
        } else {
          return nonBooleanReturnsTrue;
        }
      } else {
        LOGGER.warn("Expression: " + expression + ", returned an non-boolean result. Returning: " + nonBooleanReturnsTrue);
        return nonBooleanReturnsTrue;
      }
    }
  }

  @Override
  public String parse(String expression, CoreEvent event, ComponentLocation componentLocation)
      throws ExpressionRuntimeException {
    Builder eventBuilder = CoreEvent.builder(event);

    if (isExpression(expression)) {
      TypedValue evaluation = evaluate(expression, event, eventBuilder, componentLocation);
      try {
        return (String) transform(evaluation, evaluation.getDataType(), STRING).getValue();
      } catch (TransformerException e) {
        throw new ExpressionRuntimeException(createStaticMessage(format("Failed to transform %s to %s.", evaluation.getDataType(),
                                                                        STRING)),
                                             e);
      }
    } else {
      if (LOGGER.isDebugEnabled()) {
        LOGGER.debug(format("No expression marker found in expression '%s'. Parsing as plain String.", expression));
      }
      return expression;
    }
  }

  @Override
  public Iterator<TypedValue<?>> split(String expression, CoreEvent event, ComponentLocation componentLocation,
                                       BindingContext bindingContext)
      throws ExpressionRuntimeException {
    return expressionLanguage.split(expression, event, componentLocation, bindingContext);
  }

  @Override
  public Iterator<TypedValue<?>> split(String expression, CoreEvent event, BindingContext bindingContext)
      throws ExpressionRuntimeException {
    return expressionLanguage.split(expression, event, bindingContext);
  }

  @Override
  public String parseLogTemplate(String template, CoreEvent event, ComponentLocation componentLocation,
                                 BindingContext bindingContext)
      throws ExpressionRuntimeException {

    return parser.parse(token -> {
      TypedValue<?> evaluation = expressionLanguage.evaluateLogExpression(token, event, componentLocation, bindingContext);
      if (evaluation.getValue() instanceof Message) {
        // Do not apply transformation to Message since payload will be considered then
        return evaluation.getValue();
      }
      try {
        return transform(evaluation, evaluation.getDataType(), STRING).getValue();
      } catch (TransformerException e) {
        throw new ExpressionRuntimeException(
                                             createStaticMessage(format("Failed to transform %s to %s.",
                                                                        evaluation.getDataType(),
                                                                        STRING)),
                                             e);
      }
    }, template);
  }

  @Override
  public boolean isExpression(String expression) {
    return expression.contains(DEFAULT_EXPRESSION_PREFIX);
  }

  @Override
  public boolean isValid(String expression) {
    return validate(expression).isSuccess();
  }

  @Override
  public ValidationResult validate(String expression) {
    if (!muleContext.getConfiguration().isValidateExpressions()) {
      if (LOGGER.isDebugEnabled()) {
        LOGGER.debug("Validate expressions is turned off, no checking done for: " + expression);
      }
      return new DefaultValidationResult(true, null);
    }
    final StringBuilder message = new StringBuilder();
    if (!parser.isValid(expression)) {
      return failure("Invalid Expression", expression);
    }

    final AtomicBoolean valid = new AtomicBoolean(true);
    if (expression.contains(DEFAULT_EXPRESSION_PREFIX)) {
      parser.parse(token -> {
        if (valid.get()) {
          ValidationResult result = expressionLanguage.validate(token);
          if (!result.isSuccess()) {
            valid.compareAndSet(true, false);
            message.append(token).append(" is invalid\n");
            message.append(result.errorMessage().orElse(""));
          }
        }
        return null;
      }, expression);
    } else {
      return expressionLanguage.validate(expression);
    }

    if (message.length() > 0) {
      return failure(message.toString());
    }
    return success();
  }

  @Override
  public List<ConstraintViolation> validate(String script, String nameIdentifier, ValidationPhase validationScopePhase,
                                            TypeBindings typeBindings, Optional<MetadataType> outputType) {
    if (!muleContext.getConfiguration().isValidateExpressions()) {
      if (LOGGER.isDebugEnabled()) {
        LOGGER.debug("Validate expressions is turned off, no checking done for: " + script);
      }
      return emptyList();
    }

    return expressionLanguage.validate(script, nameIdentifier, validationScopePhase, typeBindings, outputType);
  }

  @Override
  public Iterator<TypedValue<?>> split(String expression, BindingContext context) {
    return expressionLanguage.split(expression, null, context);
  }

  @Override
  public ExpressionManagerSession openSession(BindingContext context) {
    return new DefaultExpressionManagerSession(new LazyValue<>(() -> expressionLanguage.openSession(null, null, context)),
                                               currentThread().getContextClassLoader());
  }

  @Override
  public ExpressionManagerSession openSession(ComponentLocation componentLocation, CoreEvent event, BindingContext context) {
    return new DefaultExpressionManagerSession(new LazyValue<>(() -> expressionLanguage.openSession(componentLocation, event,
                                                                                                    context)),
                                               currentThread().getContextClassLoader());
  }

  @Inject
  public void setMuleContext(MuleContext muleContext) {
    this.muleContext = muleContext;
  }

  @Inject
  public void setTransformersRegistry(TransformersRegistry transformersRegistry) {
    this.transformersRegistry = transformersRegistry;
  }

  public StreamingManager getStreamingManager() {
    return muleContext.getStreamingManager();
  }

  public void setExpressionLanguage(ExtendedExpressionLanguageAdaptor expressionLanguage) {
    this.expressionLanguage = expressionLanguage;
  }

  @Override
  public String toString() {
    return this.getClass().getName() + "[" + (expressionLanguage != null ? expressionLanguage.toString() : "null") + "]";
  }

  @Override
  public void initialise() throws InitialisationException {
    initialiseIfNeeded(expressionLanguage);
  }

  @Override
  public void dispose() {
    disposeIfNeeded(expressionLanguage, LOGGER);
  }

}
