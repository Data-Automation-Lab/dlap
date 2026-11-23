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
 * Immutable value object describing where a cloud asset physically lives, as
 * resolved from either a da-catalog lookup or fallback URI parsing.
 *
 * @param provider       normalised cloud provider key (e.g. {@code GCP},
 *                       {@code AWS}, {@code AZURE}) - matches the keys used
 *                       by {@link com.dlap.autodelete.service.storage.CloudDeletionServiceFactory#forProvider(String)}.
 * @param locatorString  canonical location string passed to the deletion SDK
 *                       (e.g. {@code gs://bucket/key},
 *                       {@code bq://project/dataset/table},
 *                       {@code s3://bucket/key}).
 * @param displayName    human-readable identifier for logs / audit rows;
 *                       falls back to the locator itself when a catalog name
 *                       was not available.
 */
public record AssetLocator(String provider, String locatorString, String displayName) {
}
