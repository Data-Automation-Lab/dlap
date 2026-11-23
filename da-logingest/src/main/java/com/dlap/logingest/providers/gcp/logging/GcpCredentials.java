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

package com.dlap.logingest.providers.gcp.logging;

import com.google.auth.oauth2.GoogleCredentials;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Resolves the credentials of a GCP stream from its {@code credentials-ref} (log-sources guide
 * section 2.1): without one, Application Default Credentials; with one, the environment variable it
 * names ({@code NAME} or {@code env:NAME}) holds the path of a service account or workload identity
 * federation credentials file. The file and its content are never logged, and an error names only
 * the variable.
 */
final class GcpCredentials {

    /** Read-only scope of Cloud Logging. */
    static final String LOGGING_READ = "https://www.googleapis.com/auth/logging.read";

    /** Read-only scope of Cloud Storage. */
    static final String STORAGE_READ = "https://www.googleapis.com/auth/devstorage.read_only";

    /** Scope of BigQuery queries. */
    static final String BIGQUERY = "https://www.googleapis.com/auth/bigquery";

    private GcpCredentials() {
    }

    /**
     * Resolves credentials.
     *
     * @param credentialsRef the stream's reference, or {@code null}
     * @param environment    the environment lookup
     * @param scopes         the OAuth scopes the client needs
     * @return the credentials
     * @throws IllegalStateException when the reference names an unset variable or an unreadable file
     */
    static GoogleCredentials resolve(String credentialsRef, UnaryOperator<String> environment, List<String> scopes) {
        try {
            if (credentialsRef == null || credentialsRef.isBlank()) {
                return GoogleCredentials.getApplicationDefault().createScoped(scopes);
            }
            if (credentialsRef.startsWith("arn:")) {
                throw new IllegalStateException("a GCP stream's credentials-ref names an environment variable, not an ARN");
            }
            String name = credentialsRef.startsWith("env:") ? credentialsRef.substring(4) : credentialsRef;
            String path = environment.apply(name);
            if (path == null || path.isBlank()) {
                throw new IllegalStateException("environment variable " + name + " is not set");
            }
            try (InputStream in = Files.newInputStream(Path.of(path.strip()))) {
                return GoogleCredentials.fromStream(in).createScoped(scopes);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot load the GCP credentials of credentials-ref "
                    + (credentialsRef == null ? "(application default)" : "variable " + credentialsRef), e);
        }
    }
}
