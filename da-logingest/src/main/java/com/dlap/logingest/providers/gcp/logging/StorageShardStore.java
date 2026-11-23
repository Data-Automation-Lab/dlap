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
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import org.springframework.beans.factory.DisposableBean;

/**
 * Lists and reads Cloud Storage sink shards with the Cloud Storage client, one client per credentials
 * reference and project. A test points it at an emulator by handing it a factory.
 */
final class StorageShardStore implements GcsSinkTransport.ShardStore, DisposableBean {

    private final Function<StreamContext, Storage> factory;
    private final Map<String, Storage> clients = new ConcurrentHashMap<>();

    /**
     * The store with Application Default Credentials or the stream's credentials file.
     *
     * @param environment the environment lookup for credentials references
     * @return the store
     */
    static StorageShardStore fromEnvironment(UnaryOperator<String> environment) {
        return new StorageShardStore(ctx -> {
            StorageOptions.Builder b = StorageOptions.newBuilder()
                    .setCredentials(GcpCredentials.resolve(ctx.stream().credentialsRef(), environment,
                            List.of(GcpCredentials.STORAGE_READ)));
            String project = ctx.stream().location().get("project");
            if (project == null || project.isBlank()) {
                project = ctx.stream().parse().assetIdHints().project();
            }
            if (project != null && !project.isBlank()) {
                b.setProjectId(project.strip());
            }
            return b.build().getService();
        });
    }

    /**
     * Creates the store with a client factory.
     *
     * @param factory builds the client of a stream
     */
    StorageShardStore(Function<StreamContext, Storage> factory) {
        this.factory = factory;
    }

    @Override
    public List<GcsSinkTransport.ShardObject> list(StreamContext context, String bucket, String prefix) {
        List<GcsSinkTransport.ShardObject> out = new ArrayList<>();
        for (Blob b : client(context).list(bucket, Storage.BlobListOption.prefix(prefix)).iterateAll()) {
            out.add(new GcsSinkTransport.ShardObject(b.getName(), b.getGeneration() == null ? "" : b.getGeneration().toString(),
                    b.getSize() == null ? 0 : b.getSize()));
        }
        return out;
    }

    @Override
    public byte[] read(StreamContext context, String bucket, GcsSinkTransport.ShardObject object) {
        BlobId id = object.generation().isEmpty() ? BlobId.of(bucket, object.name())
                : BlobId.of(bucket, object.name(), Long.parseLong(object.generation()));
        return client(context).readAllBytes(id);
    }

    private Storage client(StreamContext context) {
        String key = (context.stream().credentialsRef() == null ? "" : context.stream().credentialsRef()) + "|"
                + context.stream().location().getOrDefault("project", "") + "|" + context.stream().bindingId();
        return clients.computeIfAbsent(key, k -> factory.apply(context));
    }

    @Override
    public void destroy() throws Exception {
        for (Storage s : clients.values()) {
            s.close();
        }
        clients.clear();
    }
}
