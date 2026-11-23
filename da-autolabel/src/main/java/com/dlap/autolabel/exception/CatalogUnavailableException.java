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

package com.dlap.autolabel.exception;

/**
 * Thrown when the asset catalog cannot be reached (or returns no row) while
 * auto-labeling is trying to build the LLM prompt context for an asset.
 *
 * <p>This exception exists so the service fails the labeling job loudly
 * instead of feeding placeholder text (e.g. "Asset {id} (no catalog row)")
 * into the LLM, which would produce hallucinated labels grounded in nothing.
 */
public class CatalogUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CatalogUnavailableException(String message) {
        super(message);
    }

    public CatalogUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
