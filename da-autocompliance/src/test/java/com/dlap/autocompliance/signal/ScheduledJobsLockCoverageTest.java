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

package com.dlap.autocompliance.signal;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.SimpleMetadataReaderFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * HA-01 ratchet: every {@code @Scheduled} method in da-autocompliance is a singleton job and holds a
 * ShedLock lock under a name of its own (plan rule R7). A new scheduled job without a lock fails
 * here; a deliberately per-instance job has to be added to {@link #PER_INSTANCE} with its reason, and
 * to docs/guides/high-availability.md.
 */
class ScheduledJobsLockCoverageTest {

    /** Scheduled methods that run on every replica on purpose, as {@code Class#method}. None today. */
    private static final Set<String> PER_INSTANCE = Set.of();

    @Test
    void everyScheduledMethodHoldsAUniquelyNamedLock() throws Exception {
        List<Method> scheduled = scheduledMethods();

        assertThat(scheduled).as("the scan finds the compliance signal sync").isNotEmpty();
        Set<String> names = new HashSet<>();
        for (Method method : scheduled) {
            String id = method.getDeclaringClass().getSimpleName() + "#" + method.getName();
            if (PER_INSTANCE.contains(id)) {
                continue;
            }
            SchedulerLock lock = AnnotatedElementUtils.findMergedAnnotation(method, SchedulerLock.class);
            assertThat(lock).as("%s is @Scheduled and must hold a @SchedulerLock", id).isNotNull();
            assertThat(names.add(lock.name())).as("lock name %s of %s is unique", lock.name(), id).isTrue();
            assertThat(lock.name()).as("the shedlock name column holds 64 characters").hasSizeLessThan(64);
            assertThat(lock.lockAtMostFor()).as("%s names its own lockAtMostFor", id).isNotBlank();
        }
    }

    /**
     * Reads the class files under the service package with Spring's metadata reader, so class level
     * conditions (the sync exists only when its enabled property is set) do not hide a job.
     */
    private static List<Method> scheduledMethods() throws Exception {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        SimpleMetadataReaderFactory readers = new SimpleMetadataReaderFactory();
        List<Method> found = new ArrayList<>();
        for (Resource resource : resolver.getResources("classpath*:com/dlap/autocompliance/**/*.class")) {
            MetadataReader reader = readers.getMetadataReader(resource);
            if (reader.getAnnotationMetadata().getAnnotatedMethods(Scheduled.class.getName()).isEmpty()) {
                continue;
            }
            Class<?> type = Class.forName(reader.getClassMetadata().getClassName());
            for (Method method : type.getDeclaredMethods()) {
                if (AnnotatedElementUtils.hasAnnotation(method, Scheduled.class)) {
                    found.add(method);
                }
            }
        }
        return found;
    }
}
