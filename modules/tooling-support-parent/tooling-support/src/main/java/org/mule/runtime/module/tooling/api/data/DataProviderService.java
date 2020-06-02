/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.tooling.api.data;


import org.mule.api.annotation.NoImplement;
import org.mule.runtime.app.declaration.api.ComponentElementDeclaration;

import java.util.List;

//TODO: Validate name. Should this be here or in mule-api? What about return values?
@NoImplement
public interface DataProviderService {

  DataProviderResult<List<DataResult>> discover();

  DataProviderResult<DataResult> getValues(ComponentElementDeclaration component, String parameterName);

}
