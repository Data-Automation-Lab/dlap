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

package com.dlap.logingest.metrics;

import com.dlap.common.constants.DlapConstants;
import com.dlap.logingest.parse.core.ConditionalOnRegistryPipeline;
import com.dlap.logingest.service.registry.Disposition;
import com.dlap.proto.log.v1.DlapLogEvent;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * Observes {@code LogEventStore.write}, the single place where an event is stored and published or
 * recognised as a duplicate, so the status of a binding can report published and duplicate counts
 * and a failed publish is counted, without a change to the ingest service.
 *
 * <p>The advice never alters the result or swallows an exception: it records the disposition on
 * return, and on an exception whose cause chain holds a Kafka error it counts a publish failure
 * and rethrows. Active only with the registry pipeline.
 */
@Aspect
@Component
@ConditionalOnRegistryPipeline
public class StoreDispositionAspect {

    private final LogIngestMetrics metrics;

    /**
     * Creates the aspect.
     *
     * @param metrics where dispositions are recorded
     */
    public StoreDispositionAspect(LogIngestMetrics metrics) {
        this.metrics = metrics;
    }

    /**
     * Wraps {@code LogEventStore.write(event, ...)}.
     *
     * @param pjp   the join point
     * @param event the event being stored
     * @return what the store returned
     * @throws Throwable what the store threw
     */
    @Around("execution(* com.dlap.logingest.service.registry.LogEventStore.write(..)) && args(event, ..)")
    public Object aroundWrite(ProceedingJoinPoint pjp, DlapLogEvent event) throws Throwable {
        try {
            Object result = pjp.proceed();
            if (result instanceof Disposition d) {
                metrics.recordStore(event.getBindingId(), d);
            }
            return result;
        } catch (Throwable t) {
            if (isKafkaFailure(t)) {
                metrics.publishFailure(DlapConstants.KafkaTopic.LOG_EVENT_NORMALIZED);
            }
            throw t;
        }
    }

    /**
     * Whether an exception stems from the Kafka client.
     *
     * @param t the exception
     * @return true when it or a cause is a Kafka or Spring Kafka exception
     */
    static boolean isKafkaFailure(Throwable t) {
        int depth = 0;
        for (Throwable c = t; c != null && depth < 10; c = c.getCause(), depth++) {
            if (c instanceof org.apache.kafka.common.KafkaException || c instanceof org.springframework.kafka.KafkaException) {
                return true;
            }
        }
        return false;
    }
}
