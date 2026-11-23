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

package com.dlap.discovery.coverage;

import java.util.List;

/**
 * Reads one cloud's logging configuration and says, per subject, whether data reads are recorded
 * (COV-01). Implementations live under {@code providers/<cloud>/coverage}; this interface and the
 * job that runs them hold no provider SDK.
 *
 * <p>A collector never throws for a refused read: the affected subjects get an unknown verdict
 * naming the permission. It throws only when it cannot run at all (no credentials, the provider
 * unreachable), and then the job counts the failure and publishes nothing for that collector, so
 * stored coverage is never replaced by a guess.
 */
public interface ReadCoverageCollector {

    /**
     * The collector's id, used in metrics and as the observation's collector.
     *
     * @return for example {@code aws-cloudtrail-coverage}
     */
    String id();

    /**
     * Reads the configuration and builds the observations.
     *
     * @return one observation per subject
     */
    List<ReadCoverageObservation> collect();
}
