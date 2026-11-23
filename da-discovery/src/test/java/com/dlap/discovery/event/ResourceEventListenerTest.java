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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.kafka.support.Acknowledgment;

import com.dlap.discovery.common.model.CloudResource;
import com.dlap.discovery.common.model.ResourceChange.ChangeType;
import com.dlap.discovery.event.service.EventProcessorRegistry;
import com.dlap.discovery.event.service.IEventSubscriber;
import com.dlap.discovery.event.service.ResourceEventListener;
import com.dlap.discovery.event.service.type.ResourceEvent;
import com.dlap.discovery.logspine.SpinePublishException;

/**
 * Unit tests for ResourceEventListener.
 */
class ResourceEventListenerTest {

	@Mock
	private EventProcessorRegistry processorRegistry;

	@Mock
	private IEventSubscriber<ResourceEvent> mockProcessor1;

	@Mock
	private IEventSubscriber<ResourceEvent> mockProcessor2;

	@Mock
	private Acknowledgment ack;

	@InjectMocks
	private ResourceEventListener resourceEventListener;

	@BeforeEach
	void setUp() {
		MockitoAnnotations.openMocks(this);
	}

	@Test
	void testHandleResourceEvent_Success() {
		// Arrange
		// EC2Resource mockResource = new EC2Resource(); // Example resource
		CloudResource mockResource = mock(CloudResource.class); // Mock the resource
		ResourceEvent event = new ResourceEvent("AWS", "123", ChangeType.CREATE, mockResource);

		when(processorRegistry.getProcessorsForEvent(event)).thenReturn(List.of(mockProcessor1, mockProcessor2));
		when(mockProcessor1.getName()).thenReturn("MockProcessor1");
		when(mockProcessor2.getName()).thenReturn("MockProcessor2");

		// Act
		resourceEventListener.handleResourceEvent(event, ack);

		// Assert
		verify(processorRegistry).getProcessorsForEvent(event);
		verify(mockProcessor1).process(event);
		verify(mockProcessor2).process(event);
		verify(mockProcessor1).getName();
		verify(mockProcessor2).getName();
		verifyNoMoreInteractions(processorRegistry, mockProcessor1, mockProcessor2);
	}

	@Test
	void testHandleResourceEvent_NoProcessors() {
		// Arrange
		// EC2Resource mockResource = new EC2Resource();
		CloudResource mockResource = mock(CloudResource.class); // Mock the resource
		ResourceEvent event = new ResourceEvent("AWS", "123", ChangeType.CREATE, mockResource);

		when(processorRegistry.getProcessorsForEvent(event)).thenReturn(List.of());

		// Act
		resourceEventListener.handleResourceEvent(event, ack);

		// Assert
		verify(processorRegistry).getProcessorsForEvent(event);
		verifyNoMoreInteractions(processorRegistry);
		verifyNoInteractions(mockProcessor1, mockProcessor2); // Ensure no processors were called
	}

	@Test
	void testHandleResourceEvent_ProcessorError() {
		// Arrange
		// EC2Resource mockResource = new EC2Resource();
		CloudResource mockResource = mock(CloudResource.class); // Mock the resource
		ResourceEvent event = new ResourceEvent("AWS", "123", ChangeType.UPDATE, mockResource);

		when(processorRegistry.getProcessorsForEvent(event)).thenReturn(List.of(mockProcessor1, mockProcessor2));
		when(mockProcessor1.getName()).thenReturn("FailingProcessor");
		when(mockProcessor2.getName()).thenReturn("GoodProcessor");
		doThrow(new RuntimeException("Processing failed!")).when(mockProcessor1).process(event);

		// Act
		resourceEventListener.handleResourceEvent(event, ack);

		// Assert
		verify(processorRegistry).getProcessorsForEvent(event);
		verify(mockProcessor1).process(event); // Verify failing processor was called
		verify(mockProcessor2).process(event); // Verify next processor was still called
		// Verify getName was called for logging (twice for failing, once for
		// succeeding)
		verify(mockProcessor1, times(2)).getName(); // Called in try and catch
		verify(mockProcessor2).getName(); // Called only in try
		verifyNoMoreInteractions(processorRegistry, mockProcessor1, mockProcessor2);
	}

	@Test
	void testHandleResourceEvent_NullEvent() {
		// Act
		resourceEventListener.handleResourceEvent(null, ack);

		// Assert
		verifyNoInteractions(processorRegistry, mockProcessor1, mockProcessor2);
	}

	@Test
	void testHandleResourceEvent_SpinePublishFailureIsRethrownAndNotAcknowledged() {
		// Q-411: the crawler event must be retried and dead-lettered, not acknowledged with the asset lost.
		CloudResource mockResource = mock(CloudResource.class);
		ResourceEvent event = new ResourceEvent("GCP", "123", ChangeType.CREATE, mockResource);
		SpinePublishException failure = new SpinePublishException("broker did not accept an event", new RuntimeException("down"));

		when(processorRegistry.getProcessorsForEvent(event)).thenReturn(List.of(mockProcessor1, mockProcessor2));
		when(mockProcessor1.getName()).thenReturn("InventoryProcessor");
		when(mockProcessor2.getName()).thenReturn("SpineBridge");
		doThrow(failure).when(mockProcessor2).process(event);

		assertThatThrownBy(() -> resourceEventListener.handleResourceEvent(event, ack)).isSameAs(failure);

		verify(mockProcessor1).process(event);
		verify(mockProcessor2).process(event);
		verifyNoInteractions(ack);
	}

	@Test
	void testHandleResourceEvent_SpinePublishFailureStopsTheLaterProcessors() {
		CloudResource mockResource = mock(CloudResource.class);
		ResourceEvent event = new ResourceEvent("GCP", "123", ChangeType.UPDATE, mockResource);

		when(processorRegistry.getProcessorsForEvent(event)).thenReturn(List.of(mockProcessor1, mockProcessor2));
		when(mockProcessor1.getName()).thenReturn("SpineBridge");
		doThrow(new SpinePublishException("no acknowledgement", new RuntimeException("timeout")))
				.when(mockProcessor1).process(event);

		assertThatThrownBy(() -> resourceEventListener.handleResourceEvent(event, ack))
				.isInstanceOf(SpinePublishException.class);

		verify(mockProcessor2, never()).process(event);
		verifyNoInteractions(ack);
	}

	@Test
	void testHandleResourceEvent_OtherProcessorErrorsStillAcknowledge() {
		CloudResource mockResource = mock(CloudResource.class);
		ResourceEvent event = new ResourceEvent("GCP", "123", ChangeType.UPDATE, mockResource);

		when(processorRegistry.getProcessorsForEvent(event)).thenReturn(List.of(mockProcessor1));
		when(mockProcessor1.getName()).thenReturn("Other");
		doThrow(new IllegalStateException("not a spine failure")).when(mockProcessor1).process(event);

		resourceEventListener.handleResourceEvent(event, ack);

		verify(ack).acknowledge();
	}
}
