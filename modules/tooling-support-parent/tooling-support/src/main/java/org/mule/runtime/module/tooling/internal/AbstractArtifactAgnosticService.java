/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.tooling.internal;

import static com.google.common.base.Throwables.getCausalChain;
import static org.mule.runtime.core.api.util.FileUtils.deleteTree;
import org.mule.runtime.api.exception.MuleRuntimeException;
import org.mule.runtime.deployment.model.api.application.Application;
import org.mule.runtime.module.repository.api.BundleNotFoundException;

import java.util.function.Function;

import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.transfer.ArtifactNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AbstractArtifactAgnosticService {

  private static final Logger LOGGER = LoggerFactory.getLogger(AbstractArtifactAgnosticService.class);
  private final ApplicationSupplier applicationSupplier;

  protected AbstractArtifactAgnosticService(ApplicationSupplier applicationSupplier) {
    this.applicationSupplier = applicationSupplier;
  }

  protected <T> T withTemporaryApplication(Function<Application, T> function, Function<Exception, T> errorHandler) {
    Application application;
    try {
      application = applicationSupplier.get();
    } catch (Exception e) {
      throw getCausalChain(e).stream()
          .filter(exception -> exception.getClass().equals(ArtifactNotFoundException.class)
              || exception.getClass().equals(ArtifactResolutionException.class))
          .findFirst().map(exception -> (RuntimeException) new BundleNotFoundException(exception))
          .orElse(new MuleRuntimeException(e));
    }
    try {
      try {
        application.install();
        application.init();
        application.start();
      } catch (Exception e) {
        return errorHandler.apply(e);
      }
      return function.apply(application);
    } finally {
      if (application != null) {
        final Application finalApplication = application;
        doWithoutFail(finalApplication::stop);
        doWithoutFail(finalApplication::dispose);
        doWithoutFail(() -> deleteTree(finalApplication.getLocation()));
      }
    }
  }

  public void doWithoutFail(Runnable runnable) {
    try {
      runnable.run();
    } catch (Exception e) {
      LOGGER.warn(e.getMessage());
      if (LOGGER.isDebugEnabled()) {
        LOGGER.debug(e.getMessage(), e);
      }
    }
  }


}
