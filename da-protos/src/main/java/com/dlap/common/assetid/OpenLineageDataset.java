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

package com.dlap.common.assetid;

import java.util.Objects;

/**
 * An OpenLineage dataset identity: the {@code namespace} and {@code name} pair from the
 * OpenLineage naming conventions.
 *
 * @param namespace the dataset namespace, for example {@code s3://my-bucket} or {@code bigquery}
 * @param name      the dataset name, for example {@code path/to/object} or {@code p.d.t}
 */
public record OpenLineageDataset(String namespace, String name) {

    /**
     * Validates the components.
     *
     * @param namespace the namespace, never null
     * @param name      the name, never null
     */
    public OpenLineageDataset {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(name, "name");
    }
}
