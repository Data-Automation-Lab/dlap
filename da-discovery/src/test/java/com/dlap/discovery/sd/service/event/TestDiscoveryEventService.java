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

package com.dlap.discovery.sd.service.event;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import com.dlap.discovery.crawler.service.event.DiscoveryEventListener;
import com.dlap.discovery.crawler.service.event.IDiscoveryEventService;
import com.dlap.discovery.crawler.service.event.dto.DiscoveryEventDTO;

/**
 * Test implementation of IDiscoveryEventService for integration tests.
 */
@Service
@Primary
@Profile("test")
public class TestDiscoveryEventService implements IDiscoveryEventService {

	private final List<DiscoveryEventDTO> events = new CopyOnWriteArrayList<>();
	private final List<DiscoveryEventListener> globalListeners = new CopyOnWriteArrayList<>();
	private final Map<String, TypedSubscription> subscriptions = new ConcurrentHashMap<>();

	private static class TypedSubscription {
		final String eventType;
		final Consumer<DiscoveryEventDTO> handler;

		TypedSubscription(String eventType, Consumer<DiscoveryEventDTO> handler) {
			this.eventType = eventType;
			this.handler = handler;
		}
	}

	@Override
	public void publishEvent(DiscoveryEventDTO event) {
		events.add(event);

		// Notify type-specific subscribers
		subscriptions.forEach((id, subscription) -> {
			if (subscription.eventType.equals(event.getEventType())) {
				try {
					subscription.handler.accept(event);
				} catch (Exception e) {
					// In a test implementation, we just ignore errors
				}
			}
		});

		// Notify global listeners
		for (DiscoveryEventListener listener : globalListeners) {
			try {
				listener.onEvent(event);
			} catch (Exception e) {
				// In a test implementation, we just ignore errors
			}
		}
	}

	@Override
	public String subscribeToEvents(String eventType, Consumer<DiscoveryEventDTO> handler) {
		String subscriptionId = UUID.randomUUID().toString();
		subscriptions.put(subscriptionId, new TypedSubscription(eventType, handler));
		return subscriptionId;
	}

	@Override
	public void unsubscribe(String subscriptionId) {
		subscriptions.remove(subscriptionId);
	}

	@Override
	public List<DiscoveryEventDTO> getRecentEvents(String eventType, int limit) {
		List<DiscoveryEventDTO> filtered = events.stream()
				.filter(e -> e.getEventType().equals(eventType))
				.collect(Collectors.toList());

		if (filtered.size() <= limit) {
			return filtered;
		}

		return filtered.subList(filtered.size() - limit, filtered.size());
	}

	@Override
	public void subscribe(DiscoveryEventListener listener) {
		globalListeners.add(listener);
	}

	@Override
	public void unsubscribe(DiscoveryEventListener listener) {
		globalListeners.remove(listener);
	}

	/**
	 * Clears all stored events.
	 */
	public void clearEvents() {
		events.clear();
	}

	/**
	 * Gets all events recorded so far.
	 */
	public List<DiscoveryEventDTO> getAllEvents() {
		return Collections.unmodifiableList(new ArrayList<>(events));
	}
}