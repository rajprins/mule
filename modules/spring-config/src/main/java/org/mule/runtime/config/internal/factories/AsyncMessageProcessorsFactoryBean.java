/*
 * Copyright 2023 Salesforce, Inc. All rights reserved.
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.config.internal.factories;

import org.mule.runtime.api.component.AbstractComponent;
import org.mule.runtime.api.meta.NameableObject;
import org.mule.runtime.core.api.MuleContext;
import org.mule.runtime.core.api.context.MuleContextAware;
import org.mule.runtime.core.api.processor.Processor;
import org.mule.runtime.core.internal.processor.AsyncDelegateMessageProcessor;
import org.mule.runtime.core.privileged.processor.chain.DefaultMessageProcessorChainBuilder;

import java.util.List;

import org.springframework.beans.factory.FactoryBean;

public class AsyncMessageProcessorsFactoryBean extends AbstractComponent
    implements FactoryBean<Processor>, MuleContextAware, NameableObject {

  protected MuleContext muleContext;

  protected List<Processor> messageProcessors;
  protected String name;
  protected Integer maxConcurrency;

  @Override
  public Class getObjectType() {
    return Processor.class;
  }

  public void setMessageProcessors(List<Processor> messageProcessors) {
    this.messageProcessors = messageProcessors;
  }

  @Override
  public AsyncDelegateMessageProcessor getObject() throws Exception {
    DefaultMessageProcessorChainBuilder builder = new DefaultMessageProcessorChainBuilder();
    builder.setName("'async' child chain");
    builder.chain(messageProcessors);
    AsyncDelegateMessageProcessor delegate = new AsyncDelegateMessageProcessor(builder, name);
    delegate.setAnnotations(getAnnotations());
    if (getMaxConcurrency() != null) {
      delegate.setMaxConcurrency(getMaxConcurrency());
    }
    return delegate;
  }

  @Override
  public boolean isSingleton() {
    return true;
  }

  @Override
  public void setMuleContext(MuleContext context) {
    this.muleContext = context;
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public void setName(String name) {
    this.name = name;
  }

  public Integer getMaxConcurrency() {
    return maxConcurrency;
  }

  public void setMaxConcurrency(Integer maxConcurrency) {
    this.maxConcurrency = maxConcurrency;
  }
}
