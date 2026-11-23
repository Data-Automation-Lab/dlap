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

package com.dlap.logingest;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for da-logingest - the service that subscribes to per-cloud
 * audit log streams, normalizes events to {@code dlap.log.v1.DlapLogEvent},
 * and publishes them to {@code dlap.log.event.normalized}.
 *
 * <p>M2 ships the service skeleton + ingest-via-HTTP path (used for
 * replay and synthetic-event injection in tests). M3 wires the GCP
 * Audit Log subscriber. v0.2.0 adds the AWS CloudTrail subscriber per
 * ADR-0017.
 */
@SpringBootApplication
@EnableFeignClients
@EnableAsync
// Required for every @Scheduled in this service to fire at all: the replay-store
// retention sweeper and the CloudTrail poll cron. Without it Spring registers no
// scheduler and the cron expressions are inert.
@EnableScheduling
public class DaLogingestApplication {

    public static void main(String[] args) {
        SpringApplication.run(DaLogingestApplication.class, args);
    }
}
