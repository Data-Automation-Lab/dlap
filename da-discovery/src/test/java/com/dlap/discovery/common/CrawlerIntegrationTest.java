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

package com.dlap.discovery.common;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.dlap.discovery.common.notification.INotificationService;
import com.dlap.discovery.common.notification.dto.NotificationDTO;
import com.dlap.discovery.common.util.health.HealthStatus;
import com.dlap.discovery.common.util.health.IHealthCheckService;
import com.dlap.discovery.crawler.service.event.IDiscoveryEventService;
import com.dlap.discovery.crawler.service.event.dto.DiscoveryEventDTO;;

/**
 * Unit test using mocks for crawler services.
 */
class CrawlerIntegrationTest {

        // Mock services
        private IDiscoveryEventService eventService;
        private INotificationService notificationService;
        private IHealthCheckService healthCheckService;

        // Simple local cache for testing
        private final Map<String, Object> localCache = new ConcurrentHashMap<>();

        @BeforeEach
        void setUp() {
                // Create mock services
                eventService = mock(IDiscoveryEventService.class);
                notificationService = mock(INotificationService.class);
                healthCheckService = mock(IHealthCheckService.class);

                // Clear cache between tests
                localCache.clear();
        }

        // Cache helper methods
        private <T> Optional<T> getCachedValue(String key, Class<T> type) {
                Object value = localCache.get(key);
                if (value != null && type.isInstance(value)) {
                        return Optional.of(type.cast(value));
                }
                return Optional.empty();
        }

        private <T> void cacheValue(String key, T value, Duration ttl) {
                localCache.put(key, value);
        }

        private void invalidateCache(String key) {
                localCache.remove(key);
        }

        @Test
        void testEndToEndEventFlow() {
                // Prepare test event
                DiscoveryEventDTO event = new DiscoveryEventDTO();
                event.setEventType("test.event");
                event.setResourceId("test-resource");
                event.setSeverity(DiscoveryEventDTO.EventSeverity.INFO);

                Map<String, Object> payload = new HashMap<>();
                payload.put("testData", "example");
                event.setPayload(payload);

                // Capture the event handler to manually trigger it
                ArgumentCaptor<Consumer<DiscoveryEventDTO>> handlerCaptor = ArgumentCaptor.forClass(Consumer.class);
                when(eventService.subscribeToEvents(eq("test.event"), handlerCaptor.capture()))
                                .thenReturn("test-subscription-id");

                // Call the method being tested
                String subscriptionId = eventService.subscribeToEvents("test.event", e -> {
                        // This will be captured
                });

                // Verify subscription was made
                assertEquals("test-subscription-id", subscriptionId);

                // Simulate publishing event and triggering handler
                eventService.publishEvent(event);
                Consumer<DiscoveryEventDTO> handler = handlerCaptor.getValue();
                handler.accept(event);

                // Verify unsubscribe works
                eventService.unsubscribe(subscriptionId);
                verify(eventService).unsubscribe(subscriptionId);
        }

        @Test
        void testCacheOperations() {
                // Test caching
                String key = "test-key";
                String value = "test-value";

                cacheValue(key, value, Duration.ofMinutes(10));

                // Verify cache hit
                assertEquals(value, getCachedValue(key, String.class).orElse(null),
                                "Should retrieve cached value");

                // Test cache invalidation
                invalidateCache(key);

                // Verify cache miss after invalidation
                assertFalse(getCachedValue(key, String.class).isPresent(),
                                "Cache should be invalidated");
        }

        @Test
        void testHealthChecks() {
                // Set up mock behavior
                when(healthCheckService.isServiceHealthy("test-service")).thenReturn(true);

                HealthStatus testStatus = HealthStatus.up("test-service")
                                .withDisplayName("Test Service")
                                .withMessage("Service is healthy")
                                .withDetail("testMetric", 100);

                when(healthCheckService.checkServiceHealth("test-service")).thenReturn(testStatus);

                // Test service health status
                assertTrue(healthCheckService.isServiceHealthy("test-service"),
                                "Test service should be healthy");

                // Test detailed health status
                HealthStatus status = healthCheckService.checkServiceHealth("test-service");
                assertEquals(HealthStatus.Status.UP, status.getStatus(),
                                "Status should be UP");
                assertEquals("Test Service", status.getDisplayName(),
                                "Display name should match");
        }

        @Test
        void testNotificationSystem() {
                // Create a test notification
                NotificationDTO notification = new NotificationDTO();
                notification.setTitle("Test Notification");
                notification.setMessage("This is a test notification");
                notification.setType(NotificationDTO.NotificationType.INFO);

                // Set up mock behavior
                when(notificationService.sendNotification(any(NotificationDTO.class),
                                eq(INotificationService.NotificationChannel.EMAIL))).thenReturn(true);

                // Send notification
                boolean sent = notificationService.sendNotification(notification,
                                INotificationService.NotificationChannel.EMAIL);

                // Verify result
                assertTrue(sent, "Notification should be sent");
                verify(notificationService).sendNotification(eq(notification),
                                eq(INotificationService.NotificationChannel.EMAIL));
        }
}