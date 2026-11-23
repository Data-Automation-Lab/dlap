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

package com.dlap.adminservice.credential;

/**
 * The single method a deployment implements to plug in its secret manager under
 * {@link CredentialProvider.Mode#SECRET_MANAGER}: given a reference DLAP stored on the
 * connection (e.g. {@code vault://secret/data/aws/prod#access}, {@code gcpsm://projects/p/secrets/s/versions/latest},
 * {@code awssm://prod/dlap/aws}), return the secret material. Implement this with Spring
 * Cloud Vault / AWS Secrets Manager / GCP Secret Manager / Azure Key Vault and register it
 * as a Spring bean; DLAP never stores or logs the secret itself.
 */
@FunctionalInterface
public interface SecretResolver {

    /**
     * Resolve a secret reference to its value.
     *
     * @param secretRef the opaque reference DLAP persisted on the connection
     * @return the secret material (never logged by DLAP)
     */
    String resolve(String secretRef);
}
