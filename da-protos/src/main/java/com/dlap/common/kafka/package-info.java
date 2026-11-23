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

/**
 * Shared Kafka client construction for every DLAP service: one builder that turns Spring Boot's
 * bound {@code spring.kafka.*} properties into producer, consumer, listener-container and admin
 * factories with idempotent producers, manual acknowledgement and dead-letter handling. Spring
 * Kafka and Spring Boot's Kafka module are compile-only here; each service brings them at run time.
 * No cloud-provider code lives in this package.
 */
package com.dlap.common.kafka;
