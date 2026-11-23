/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.dlap.autodelete.service;

/**
 * Thrown when {@link AssetLocatorResolver} cannot determine the
 * {@code (provider, locator)} pair for a given asset identifier - neither the
 * catalog lookup nor the fallback URI parser succeeded.
 *
 * <p>Callers MUST NOT invoke any cloud deletion SDK on an unresolved asset:
 * the right action is to mark the task asset {@code FAILED} with
 * {@code reason="cannot resolve asset locator"} and write a {@code FAILED}
 * row to the deletion audit log.
 */
public class AssetLocatorResolutionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AssetLocatorResolutionException(String message) {
        super(message);
    }

    public AssetLocatorResolutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
