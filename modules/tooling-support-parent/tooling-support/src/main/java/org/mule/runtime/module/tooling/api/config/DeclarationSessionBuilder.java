/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.tooling.api.config;

import org.mule.runtime.app.declaration.api.GlobalElementDeclaration;
import org.mule.runtime.module.tooling.api.ArtifactAgnosticServiceBuilder;

import java.util.List;

/**
 * Provides all required steps to configure and build a new {@link DeclarationSession}
 *
 * @since 4.4.0
 */
public interface DeclarationSessionBuilder
    extends ArtifactAgnosticServiceBuilder<DeclarationSessionBuilder, DeclarationSession> {

  /**
   * Add {@link GlobalElementDeclaration}s to be used as static data to resolve all metadata with the constructed {@link DeclarationSession}
   *
   * @param globalElementDeclarations the {@link GlobalElementDeclaration}s to configure this session.
   * @return this builder
   */
  DeclarationSessionBuilder withGlobalElements(GlobalElementDeclaration... globalElementDeclarations);

  /**
   * Add {@link GlobalElementDeclaration}s to be used as static data to resolve all metadata with the constructed {@link DeclarationSession}
   *
   * @param globalElementDeclarations the list of {@link GlobalElementDeclaration}s to configure this session.
   * @return this builder
   */
  DeclarationSessionBuilder withGlobalElements(List<GlobalElementDeclaration> globalElementDeclarations);


}
