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

package com.dlap.logingest.backfill;

import com.dlap.logingest.config.sources.EffectiveBinding;
import com.dlap.logingest.config.sources.EffectiveLogSources;
import com.dlap.logingest.config.sources.EffectiveStream;
import com.dlap.logingest.parse.core.PipelineMode;
import com.dlap.logingest.transport.TransportRegistry;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Accepts, lists and cancels backfill jobs (design B6). A job is a bounded, resumable read of a
 * binding's history through the same transport the stream polls, with {@code origin=BACKFILL}.
 *
 * <h3>What a request must satisfy</h3>
 * <ul>
 *   <li>the registry pipeline is active and the binding runs (valid, enabled);</li>
 *   <li>the stream is a pollable one with a registered transport and {@code backfill.allowed: true}
 *       in the bindings file: reading history can cost bytes or API quota, so the file decides;</li>
 *   <li>{@code from < to}, {@code to} is not in the future and the span is at most
 *       {@code dlap.logingest.backfill.max-window};</li>
 *   <li>the binding has no {@code PENDING} or {@code RUNNING} job. The partial unique index of
 *       Flyway V3 enforces this across replicas; this class only gives the friendlier message.</li>
 * </ul>
 * Running the job is {@link BackfillRunner}'s concern.
 */
@Service
public class BackfillService {

    private static final Logger log = LoggerFactory.getLogger(BackfillService.class);

    /**
     * A request to start a job.
     *
     * @param bindingId the binding
     * @param streamId  the stream, or {@code null} when the binding has exactly one backfill-allowed stream
     * @param from      inclusive start, event time
     * @param to        exclusive end, event time
     */
    @Schema(name = "BackfillCreateRequest")
    public record CreateRequest(String bindingId, String streamId, Instant from, Instant to) {
    }

    /** The registry pipeline is not active, so there is no transport to read. Maps to 409. */
    public static final class BackfillUnavailableException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        BackfillUnavailableException() {
            super("backfill needs dlap.logingest.pipeline=registry");
        }
    }

    /** The binding or job does not exist. Maps to 404. */
    public static final class BackfillNotFoundException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        BackfillNotFoundException(String message) {
            super(message);
        }
    }

    /** The request is well formed but the current state refuses it. Maps to 409. */
    public static final class BackfillConflictException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        BackfillConflictException(String message) {
            super(message);
        }
    }

    private final BackfillJobs jobs;
    private final EffectiveLogSources sources;
    private final TransportRegistry transports;
    private final BackfillProperties props;
    private final Clock clock;
    private final BackfillMetrics metrics;

    /**
     * Creates the service.
     *
     * @param jobs       the job table
     * @param sources    the effective configuration
     * @param transports the transports
     * @param props      the settings
     * @param clock      the application clock when unique, else the UTC system clock
     * @param metrics    the backfill meters (Q-558)
     */
    @Autowired
    public BackfillService(BackfillJobs jobs, EffectiveLogSources sources, TransportRegistry transports,
                           BackfillProperties props, ObjectProvider<Clock> clock, BackfillMetrics metrics) {
        this.jobs = jobs;
        this.sources = sources;
        this.transports = transports;
        this.props = props;
        this.clock = clock.getIfUnique(Clock::systemUTC);
        this.metrics = metrics;
    }

    /**
     * Creates the service with meters nobody scrapes.
     *
     * @param jobs       the job table
     * @param sources    the effective configuration
     * @param transports the transports
     * @param props      the settings
     * @param clock      the application clock when unique, else the UTC system clock
     */
    public BackfillService(BackfillJobs jobs, EffectiveLogSources sources, TransportRegistry transports,
                           BackfillProperties props, ObjectProvider<Clock> clock) {
        this(jobs, sources, transports, props, clock, BackfillMetrics.detached());
    }

    /**
     * Accepts a job.
     *
     * @param request what to read
     * @param actor   who asked, for the job row (a name, never a token)
     * @return the new {@code PENDING} job
     * @throws BackfillUnavailableException when the registry pipeline is not active
     * @throws BackfillNotFoundException    when the binding is unknown
     * @throws BackfillConflictException    when the binding does not run, the stream may not backfill or the
     *                                      binding already has an active job
     * @throws IllegalArgumentException     when the window or the stream selection is invalid
     */
    public BackfillJob create(CreateRequest request, String actor) {
        if (sources.pipeline() != PipelineMode.REGISTRY) {
            throw new BackfillUnavailableException();
        }
        if (request == null || request.bindingId() == null || request.bindingId().isBlank()) {
            throw new IllegalArgumentException("bindingId is required");
        }
        EffectiveBinding binding = sources.binding(request.bindingId())
                .orElseThrow(() -> new BackfillNotFoundException("no binding " + request.bindingId()));
        if (!binding.runs(sources.pipeline())) {
            throw new BackfillConflictException("binding " + binding.id() + " does not run (disabled or invalid)");
        }
        EffectiveStream stream = pickStream(binding, request.streamId());
        checkWindow(request.from(), request.to());
        String who = actor == null || actor.isBlank() ? "unknown" : actor;
        try {
            BackfillJob job = jobs.insert(UUID.randomUUID(), binding.id(), stream.streamId(), request.from(),
                    request.to(), who.length() <= 256 ? who : who.substring(0, 256), clock.instant());
            log.info("Backfill job {} accepted by {}: binding={} stream={} window=[{}, {})", job.id(), who,
                    job.bindingId(), job.streamId(), job.from(), job.to());
            return job;
        } catch (BackfillJobs.ActiveJobExistsException e) {
            String running = jobs.activeFor(binding.id()).map(j -> " (job " + j.id() + ")").orElse("");
            throw new BackfillConflictException("binding " + binding.id() + " already has an active backfill job"
                    + running);
        }
    }

    /**
     * Finds a job.
     *
     * @param id the id
     * @return the job
     * @throws BackfillNotFoundException when there is none
     */
    public BackfillJob get(UUID id) {
        return jobs.find(id).orElseThrow(() -> new BackfillNotFoundException("no backfill job " + id));
    }

    /**
     * One page of jobs, newest first.
     *
     * @param bindingId only this binding, or {@code null}
     * @param status    only this state, or {@code null}
     * @param page      zero-based page
     * @param size      page size
     * @return the jobs
     */
    public List<BackfillJob> list(String bindingId, BackfillStatus status, int page, int size) {
        return jobs.list(bindingId, status, size, (long) page * size);
    }

    /**
     * Counts the jobs a list would return.
     *
     * @param bindingId only this binding, or {@code null}
     * @param status    only this state, or {@code null}
     * @return the count
     */
    public long count(String bindingId, BackfillStatus status) {
        return jobs.count(bindingId, status);
    }

    /**
     * Cancels a job. A running job stops after the batch in flight; what it already ingested stays.
     *
     * @param id    the job
     * @param actor who asked
     * @return the cancelled job
     * @throws BackfillNotFoundException when there is no such job
     * @throws BackfillConflictException when the job had already ended
     */
    public BackfillJob cancel(UUID id, String actor) {
        BackfillJob job = get(id);
        if (!job.status().active()) {
            throw new BackfillConflictException("job " + id + " already ended as " + job.status());
        }
        Instant cancelledAt = clock.instant();
        if (!jobs.cancel(id, cancelledAt, "cancelled by " + (actor == null ? "unknown" : actor))) {
            throw new BackfillConflictException("job " + id + " ended before it could be cancelled");
        }
        metrics.jobEnded(job.bindingId(), BackfillStatus.CANCELLED,
                job.startedAt() != null ? job.startedAt() : job.requestedAt(), cancelledAt);
        log.info("Backfill job {} cancelled by {}", id, actor);
        return get(id);
    }

    private EffectiveStream pickStream(EffectiveBinding binding, String streamId) {
        if (streamId != null && !streamId.isBlank()) {
            EffectiveStream s = binding.streams().stream().filter(x -> x.streamId().equals(streamId)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("binding " + binding.id() + " has no stream "
                            + streamId));
            requireBackfillable(binding, s);
            return s;
        }
        List<EffectiveStream> candidates = binding.streams().stream()
                .filter(s -> !s.push() && s.backfillAllowed() && transports.find(s.transport()).isPresent()).toList();
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        if (candidates.isEmpty()) {
            throw new BackfillConflictException("binding " + binding.id()
                    + " has no stream that allows backfill: set backfill.allowed on a polled stream");
        }
        throw new IllegalArgumentException("binding " + binding.id() + " has " + candidates.size()
                + " streams that allow backfill; name one with streamId");
    }

    private void requireBackfillable(EffectiveBinding binding, EffectiveStream stream) {
        if (stream.push()) {
            throw new IllegalArgumentException("stream " + stream.streamId() + " is a push channel and has no "
                    + "history to read");
        }
        if (!stream.backfillAllowed()) {
            throw new BackfillConflictException("stream " + stream.streamId() + " of binding " + binding.id()
                    + " does not allow backfill: set backfill.allowed in the bindings file");
        }
        if (transports.find(stream.transport()).isEmpty()) {
            throw new BackfillConflictException("no transport " + stream.transport() + " is registered");
        }
    }

    private void checkWindow(Instant from, Instant to) {
        if (from == null || to == null) {
            throw new IllegalArgumentException("from and to are required");
        }
        if (!from.isBefore(to)) {
            throw new IllegalArgumentException("from must be before to");
        }
        if (to.isAfter(clock.instant())) {
            throw new IllegalArgumentException("to must not be in the future; the stream reads recent events");
        }
        Duration span = Duration.between(from, to);
        if (span.compareTo(props.maxWindow()) > 0) {
            throw new IllegalArgumentException("the window is " + span + " long; the most one job reads is "
                    + props.maxWindow() + " (dlap.logingest.backfill.max-window). Start several jobs in turn.");
        }
    }
}
