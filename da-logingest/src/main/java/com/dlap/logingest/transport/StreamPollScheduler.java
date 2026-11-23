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

package com.dlap.logingest.transport;

import com.dlap.logingest.config.sources.EffectiveLogSources;
import com.dlap.logingest.config.sources.EffectiveStream;
import com.dlap.logingest.parse.core.ConditionalOnRegistryPipeline;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

/**
 * Schedules a poll of every pollable stream that has a registered transport, each at its own
 * {@code poll-interval} (fixed delay, so a slow poll never overlaps itself on one replica). Replicas
 * all schedule every stream; the stream's advisory lock lets one of them poll at a time, so no
 * ShedLock is needed here (design B5 "HA"). Streams whose transport is not registered are skipped
 * with a warning; the validator already reported them.
 */
@Component
@ConditionalOnRegistryPipeline
public class StreamPollScheduler implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(StreamPollScheduler.class);

    private final EffectiveLogSources sources;
    private final TransportRegistry transports;
    private final StreamRunner runner;
    private final TaskScheduler scheduler;
    private final List<ScheduledFuture<?>> scheduled = new ArrayList<>();

    /**
     * Creates the scheduler.
     *
     * @param sources    the effective configuration
     * @param transports the transports
     * @param runner     the stream runner
     * @param scheduler  the application task scheduler
     */
    public StreamPollScheduler(EffectiveLogSources sources, TransportRegistry transports, StreamRunner runner,
                               TaskScheduler scheduler) {
        this.sources = sources;
        this.transports = transports;
        this.runner = runner;
        this.scheduler = scheduler;
    }

    /** Starts the polls once the application is ready. */
    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        for (EffectiveStream stream : sources.pollableStreams()) {
            if (transports.find(stream.transport()).isEmpty()) {
                log.warn("Stream {}/{} not scheduled: no transport {} is registered", stream.bindingId(),
                        stream.streamId(), stream.transport());
                continue;
            }
            Duration every = stream.pollInterval().isZero() ? Duration.ofMinutes(1) : stream.pollInterval();
            scheduled.add(scheduler.scheduleWithFixedDelay(() -> pollQuietly(stream), every));
            log.info("Stream {}/{} ({}) polls every {}", stream.bindingId(), stream.streamId(), stream.transport(),
                    every);
        }
    }

    /**
     * How many streams are scheduled.
     *
     * @return the count
     */
    public synchronized int scheduledCount() {
        return scheduled.size();
    }

    @Override
    public synchronized void destroy() {
        scheduled.forEach(f -> f.cancel(false));
        scheduled.clear();
    }

    private void pollQuietly(EffectiveStream stream) {
        try {
            runner.poll(stream);
        } catch (RuntimeException e) {
            // A failing stream must not cancel its own schedule; the next tick retries.
            log.error("Poll of {}/{} failed: {}", stream.bindingId(), stream.streamId(), e.getMessage(), e);
        }
    }
}
