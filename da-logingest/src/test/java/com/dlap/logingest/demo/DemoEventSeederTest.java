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

package com.dlap.logingest.demo;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.test.util.ReflectionTestUtils;

import com.dlap.logingest.service.ILogIngestService;

/**
 * {@link DemoEventSeeder} injects the configured number of synthetic events
 * through the ingest service on startup.
 */
class DemoEventSeederTest {

    @Test
    void injectsTheConfiguredNumberOfSyntheticEvents() {
        ILogIngestService ingest = mock(ILogIngestService.class);
        DemoEventSeeder seeder = new DemoEventSeeder(ingest);
        ReflectionTestUtils.setField(seeder, "eventCount", 7);
        ReflectionTestUtils.setField(seeder, "demoProjectId", "dlap-demo-project");

        seeder.run(new DefaultApplicationArguments());

        verify(ingest, times(7)).ingest(any());
    }
}
