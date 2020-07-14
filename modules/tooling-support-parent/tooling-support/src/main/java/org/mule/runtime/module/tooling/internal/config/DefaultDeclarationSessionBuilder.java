/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.tooling.internal.config;

import static org.mule.runtime.app.declaration.api.fluent.ElementDeclarer.newArtifact;
import org.mule.runtime.app.declaration.api.ArtifactDeclaration;
import org.mule.runtime.app.declaration.api.GlobalElementDeclaration;
import org.mule.runtime.app.declaration.api.fluent.ArtifactDeclarer;
import org.mule.runtime.module.tooling.api.config.DeclarationSession;
import org.mule.runtime.module.deployment.impl.internal.application.DefaultApplicationFactory;
import org.mule.runtime.module.tooling.api.config.DeclarationSessionBuilder;
import org.mule.runtime.module.tooling.internal.AbstractArtifactAgnosticServiceBuilder;
import org.mule.runtime.module.tooling.internal.ApplicationSupplier;

import java.util.Arrays;
import java.util.List;

public class DefaultDeclarationSessionBuilder
    extends AbstractArtifactAgnosticServiceBuilder<DeclarationSessionBuilder, DeclarationSession>
    implements DeclarationSessionBuilder {

  private ArtifactDeclarer artifactDeclarer = newArtifact();

  public DefaultDeclarationSessionBuilder(DefaultApplicationFactory defaultApplicationFactory) {
    super(defaultApplicationFactory);
  }

  @Override
  public DeclarationSessionBuilder withGlobalElements(GlobalElementDeclaration... globalElementDeclarations) {
    Arrays.stream(globalElementDeclarations).forEach(this::declareGlobalElement);
    return this;
  }

  @Override
  public DeclarationSessionBuilder withGlobalElements(List<GlobalElementDeclaration> globalElementDeclarations) {
    globalElementDeclarations.forEach(this::declareGlobalElement);
    return this;
  }

  private void declareGlobalElement(GlobalElementDeclaration globalElementDeclaration) {
    this.artifactDeclarer.withGlobalElement(globalElementDeclaration);
  }

  @Override
  protected DeclarationSession createService(ApplicationSupplier applicationSupplier) {
    return new DefaultDeclarationSession(applicationSupplier);
  }

  @Override
  protected ArtifactDeclaration getArtifactDeclaration() {
    return this.artifactDeclarer.getDeclaration();
  }

}
