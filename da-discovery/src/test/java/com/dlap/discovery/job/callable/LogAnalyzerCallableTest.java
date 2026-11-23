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

package com.dlap.discovery.job.callable;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dlap.discovery.catalog.service.ICatalogService;
import com.dlap.discovery.common.model.DiscoveryJob;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.log.service.ICheckpointService;
import com.dlap.discovery.log.service.ILogAnalyzer;
import com.dlap.discovery.log.service.ILogAnalyzerRegistry;

/**
 * Unit tests for LogAnalyzerCallable (Updated for Async).
 */
@ExtendWith(MockitoExtension.class)
public class LogAnalyzerCallableTest {

	@Mock
	private DiscoveryJob job;

	@Mock
	private ILogAnalyzerRegistry analyzerRegistry;

	@Mock
	private ICheckpointService checkpointService;

	@Mock
	private ICatalogService catalogService;

	@Mock
	private ILogAnalyzer logAnalyzer;

	private LogAnalyzerCallable callable;
	private final String accountId = "test-account-123";
	private final CloudProvider provider = CloudProvider.GCP;
	private final Instant lastCheckpoint = Instant.now().minusSeconds(3600); // 1 hour ago
	private final Instant currentCheckpoint = Instant.now();
	private final ZonedDateTime lastCheckpointZdt = lastCheckpoint.atZone(ZoneId.systemDefault());
	private final ZonedDateTime currentCheckpointZdt = currentCheckpoint.atZone(ZoneId.systemDefault());

	@BeforeEach
	void setUp() {
		callable = new LogAnalyzerCallable(job, analyzerRegistry, checkpointService, catalogService);

		// Common job setup
		when(job.getJobId()).thenReturn(UUID.randomUUID());
		when(job.getCloudProvider()).thenReturn(provider);
		when(job.getAccountId()).thenReturn(accountId);
	}

	@Test
	void testCallWithNoNewLogs() throws Exception {
		// Setup analyzer
		when(analyzerRegistry.getAnalyzer(provider)).thenReturn(logAnalyzer);

		// Setup checkpoint
		when(checkpointService.getLastCheckpoint(provider, accountId)).thenReturn(lastCheckpoint);

		// No new logs
		when(logAnalyzer.hasNewLogs(accountId, lastCheckpoint)).thenReturn(false);

		// Execute the callable
		boolean result = callable.call();

		// Verify results
		assertTrue(result, "Call should succeed even with no new logs");

		// Verify interactions
		verify(analyzerRegistry).getAnalyzer(provider);
		verify(checkpointService).getLastCheckpoint(provider, accountId);
		verify(logAnalyzer).hasNewLogs(accountId, lastCheckpoint);
		verify(logAnalyzer, never()).triggerLogAnalysisAsync(anyString(), any(ZonedDateTime.class),
				any(ZonedDateTime.class), isNull(ILogAnalyzer.AnalysisOptions.class));
		verifyNoMoreInteractions(logAnalyzer);
		verifyNoInteractions(catalogService);
	}

	@Test
	void testCallTriggersAsyncAnalysis() throws Exception {
		// Setup analyzer
		when(analyzerRegistry.getAnalyzer(provider)).thenReturn(logAnalyzer);

		// Setup checkpoint
		when(checkpointService.getLastCheckpoint(provider, accountId)).thenReturn(lastCheckpoint);

		// Has new logs
		when(logAnalyzer.hasNewLogs(accountId, lastCheckpoint)).thenReturn(true);

		// Expect the async method to be called (returns void)
		doNothing().when(logAnalyzer).triggerLogAnalysisAsync(eq(accountId),
				any(ZonedDateTime.class),
				any(ZonedDateTime.class),
				isNull(ILogAnalyzer.AnalysisOptions.class));

		// Expect checkpoint update
		when(checkpointService.updateCheckpoint(eq(provider), eq(accountId), any(Instant.class))).thenReturn(true);

		// Execute the callable
		boolean result = callable.call();

		// Verify results
		assertTrue(result, "Call should succeed when triggering async analysis");

		// Verify interactions in order
		verify(analyzerRegistry).getAnalyzer(provider);
		verify(checkpointService).getLastCheckpoint(provider, accountId);
		verify(logAnalyzer).hasNewLogs(accountId, lastCheckpoint);
		verify(logAnalyzer).triggerLogAnalysisAsync(eq(accountId),
				any(ZonedDateTime.class),
				any(ZonedDateTime.class),
				isNull(ILogAnalyzer.AnalysisOptions.class));
		verify(checkpointService).updateCheckpoint(eq(provider), eq(accountId), any(Instant.class));
		verifyNoInteractions(catalogService);
		verifyNoMoreInteractions(logAnalyzer);
	}

	@Test
	void testCallWithMissingProvider() {
		// Setup null provider
		when(job.getCloudProvider()).thenReturn(null);

		// Execute and verify exception
		assertThrows(IllegalArgumentException.class, () -> callable.call());
	}

	@Test
	void testCallWithMissingAccountId() {
		// Setup null account ID
		when(job.getAccountId()).thenReturn(null);

		// Execute and verify exception
		assertThrows(IllegalArgumentException.class, () -> callable.call());
	}

	@Test
	void testCallWithNoAnalyzerFound() {
		// Return null for analyzer
		when(analyzerRegistry.getAnalyzer(provider)).thenReturn(null);

		// Execute and verify exception
		assertThrows(IllegalStateException.class, () -> callable.call());
	}
}