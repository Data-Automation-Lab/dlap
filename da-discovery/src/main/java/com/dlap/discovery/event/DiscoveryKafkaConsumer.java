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

package com.dlap.discovery.event;

import java.util.Collection;
import java.util.concurrent.CountDownLatch;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import com.dlap.discovery.application.DADiscoveryAgent;
import com.dlap.discovery.common.config.DynamicConfig;
import com.dlap.discovery.crawler.service.IDiscoveryService;

@Service
public class DiscoveryKafkaConsumer {

    private final Logger log = LoggerFactory.getLogger(DiscoveryKafkaConsumer.class);

    private CountDownLatch latch = new CountDownLatch(1);

    private String payload = null;

    private final DynamicConfig dynamicConfig;
    private final DADiscoveryAgent dgCrawlerApp;
    private final Collection<IDiscoveryService<?>> discoveryServices;

    @Autowired
    public DiscoveryKafkaConsumer(DynamicConfig dynamicConfig, DADiscoveryAgent dgCrawlerApp,
            Collection<IDiscoveryService<?>> discoveryServices) {
        this.dynamicConfig = dynamicConfig;
        this.dgCrawlerApp = dgCrawlerApp;
        this.discoveryServices = discoveryServices;
    }

    // TODO:
    // 1. Name the topic to be used in the application-dev.yml file to reflect that
    // this
    // consumer will trigger a dicsovery job.
    // 2. Discovery job should use a qualifier name (Ex gcp, azure, aws, oci) to run
    // discovery
    // for right cloud provider.
    @KafkaListener(topics = "${kafka.topic.name}")
    public void receive(ConsumerRecord<?, ?> consumerRecord) {
        log.info("received payload='{}'", consumerRecord.toString());
        payload = consumerRecord.toString();
        latch.countDown();
        // TODO: create a composite message to wrap action type and payload:
        // 1. action type: start discovery, stop discovery, pause discovery, resume
        // discovery
        // 2. payload: Resource type and name
        // 3. cloud provider: gcp, azure, aws, oci
        // Accordingly use IDiscoveryService to execute the action
        // dgCrawlerApp.runGCPCrawler();
    }

    public void resetLatch() {
        latch = new CountDownLatch(1);
    }

    public CountDownLatch getLatch() {
        return latch;
    }

    public String getPayload() {
        return payload;
    }
}