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

package com.dlap.discovery.event.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

import com.dlap.discovery.config.KafkaReliabilityConfiguration;
import com.dlap.discovery.event.service.type.ResourceEvent;
import com.dlap.discovery.logspine.SpinePublishException;

/**
 * Kafka listener for resource events published by resource crawlers.
 * Delegates event processing to registered event processors.
 */
@Service
public class ResourceEventListener {
	private static final Logger log = LoggerFactory.getLogger(ResourceEventListener.class);

	private final EventProcessorRegistry processorRegistry;

	@Autowired
	public ResourceEventListener(EventProcessorRegistry processorRegistry) {
		this.processorRegistry = processorRegistry;
	}

	/**
	 * Handles resource events received from Kafka.
	 * Delegates to all registered processors that can handle the event.
	 *
	 * <p>Uses manual acknowledgment (CLAUDE.md §11) via the
	 * {@link KafkaReliabilityConfiguration#MANUAL_ACK_FACTORY} container
	 * factory. The offset is committed with {@code ack.acknowledge()} only
	 * after successful processing. Unrecoverable processing failures are
	 * propagated so the factory's {@code DefaultErrorHandler} retries with
	 * backoff and routes to the DLQ; the record is not acked in that case.
	 * A {@link SpinePublishException} is such a failure: the crawler event is
	 * not acknowledged until the event is on the log spine.
	 *
	 * @param event The resource event to process
	 * @param ack   Kafka acknowledgment for manual offset commit
	 */
	@KafkaListener(
			topics = "${kafka.topics.resource-events}",
			groupId = "${kafka.group.catalog-updater}",
			containerFactory = KafkaReliabilityConfiguration.RESOURCE_EVENT_FACTORY)
	public void handleResourceEvent(ResourceEvent event, Acknowledgment ack) {
		log.debug("Received resource event: {}", event);

		if (event == null || event.getPayload() == null) {
			// Non-retryable: a malformed record must not block the partition.
			log.error("Received null event or event with null payload; acknowledging and skipping");
			ack.acknowledge();
			return;
		}

		log.info("Processing {} event for resource: {} ({})",
				event.getChangeType(),
				event.getPayload().getId(),
				event.getPayload().getClass().getSimpleName());

		// Get processors that can handle this event
		var processors = processorRegistry.getProcessorsForEvent(event);

		if (processors.isEmpty()) {
			// Non-retryable: no handler exists for this event type.
			log.warn("No processors found for event: {}; acknowledging and skipping", event);
			ack.acknowledge();
			return;
		}

		// Process the event with each processor. A single failing processor is
		// logged and skipped so it does not fail the whole record; any other
		// (unexpected) failure propagates to the error handler / DLQ below.
		for (var processor : processors) {
			try {
				log.debug("Processing event with: {}", processor.getName());
				processor.process(event);
			} catch (SpinePublishException e) {
				// The event did not reach the log spine. Swallowing it would acknowledge the crawler
				// event with the asset lost; rethrow so the error handler retries with backoff and
				// then dead-letters. Every processor is idempotent, so a retry is safe (Q-411).
				log.error("Processor {} could not publish to the log spine; the record will be retried: {}",
						processor.getName(), e.getMessage());
				throw e;
			} catch (Exception e) {
				log.error("Error in processor {}: {}", processor.getName(), e.getMessage(), e);
				// Continue with next processor
			}
		}

		log.debug("Completed processing event with {} processors", processors.size());
		// Commit the offset only after successful processing.
		ack.acknowledge();
	}
}