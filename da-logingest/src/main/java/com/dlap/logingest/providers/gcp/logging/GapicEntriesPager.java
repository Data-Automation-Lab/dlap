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

import com.dlap.logingest.transport.StreamContext;
import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.cloud.logging.v2.LoggingClient;
import com.google.cloud.logging.v2.LoggingSettings;
import com.google.logging.v2.ListLogEntriesRequest;
import com.google.logging.v2.ListLogEntriesResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
import org.springframework.beans.factory.DisposableBean;

/**
 * {@code entries.list} through the generated Cloud Logging client, one request per call (the paged
 * helpers would fetch pages behind the rate limiter's back). Clients are created on first use per
 * credentials reference and closed with the bean.
 */
final class GapicEntriesPager implements LoggingApiTransport.EntriesPager, DisposableBean {

    private final UnaryOperator<String> environment;
    private final Map<String, LoggingClient> clients = new ConcurrentHashMap<>();

    /**
     * Creates the pager.
     *
     * @param environment the environment lookup for credentials references
     */
    GapicEntriesPager(UnaryOperator<String> environment) {
        this.environment = environment;
    }

    @Override
    public LoggingApiTransport.Page page(StreamContext context, String project, String filter, String pageToken,
                                         int pageSize) {
        LoggingClient client = clients.computeIfAbsent(key(context.stream().credentialsRef()),
                k -> create(context.stream().credentialsRef()));
        ListLogEntriesRequest request = ListLogEntriesRequest.newBuilder()
                .addResourceNames("projects/" + project)
                .setFilter(filter)
                .setOrderBy("timestamp asc")
                .setPageSize(pageSize)
                .setPageToken(pageToken)
                .build();
        ListLogEntriesResponse response = client.listLogEntriesCallable().call(request);
        return new LoggingApiTransport.Page(List.copyOf(response.getEntriesList()), response.getNextPageToken());
    }

    private LoggingClient create(String credentialsRef) {
        try {
            LoggingSettings settings = LoggingSettings.newBuilder()
                    .setCredentialsProvider(FixedCredentialsProvider.create(
                            GcpCredentials.resolve(credentialsRef, environment, List.of(GcpCredentials.LOGGING_READ))))
                    .build();
            return LoggingClient.create(settings);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create the Cloud Logging client", e);
        }
    }

    private static String key(String credentialsRef) {
        return credentialsRef == null ? "" : credentialsRef;
    }

    @Override
    public void destroy() {
        clients.values().forEach(LoggingClient::close);
        clients.clear();
    }
}
