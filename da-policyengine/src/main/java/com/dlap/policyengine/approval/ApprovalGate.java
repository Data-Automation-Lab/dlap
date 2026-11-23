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

package com.dlap.policyengine.approval;

import java.util.concurrent.CompletableFuture;

import org.springframework.stereotype.Component;

import com.dlap.policyengine.event.PolicyActionEvent;
import com.dlap.policyengine.kafka.producer.PolicyActionProducer;

/**
 * Where a policy fire goes (APPR-01): straight to {@code dlap.policies.actions}, or into the
 * approval service to wait for people.
 *
 * <p>A fire waits when its action is destructive (archive, soft delete, hard delete;
 * {@link ActionClass#destructive()}) and not a dry run, whatever {@code requireApproval} says, or
 * when its policy set {@code requireApproval: true}. A dry run of a destructive action executes
 * nothing (every executor only logs it), so it passes. A waiting fire publishes nothing until the
 * approval releases it.
 * In shadow mode ({@code dlap.policyengine.actions.enabled=false}) nothing is held either: the fire
 * goes to the producer, which logs and counts it, so a shadow pilot records no approval requests.
 *
 * <p>The event consumer and the schedule sweep route every fire through this class. A failure
 * to record the request (the database is down) is thrown, so the caller retries the fire instead
 * of dropping or executing it.
 */
@Component
public class ApprovalGate {

    /** What became of one fire. */
    public enum Route {
        /** Published on the action topic. */
        PUBLISHED,
        /** Withheld by shadow mode. */
        SHADOWED,
        /** The producer failed before the send. */
        FAILED,
        /** Recorded as a new approval request. */
        HELD,
        /** An approval request for this fire, or for the same policy, asset and action, already waits. */
        ALREADY_HELD
    }

    /**
     * What became of one fire, with its delivery (POL-02, Q-439).
     *
     * @param route     the route
     * @param delivered completes {@code true} when the fire is durably somewhere (acknowledged by the
     *                  broker, withheld by shadow mode, or recorded as an approval request) and
     *                  {@code false} when the send failed before or after the hand-off
     */
    public record Submission(Route route, CompletableFuture<Boolean> delivered) {
    }

    private final PolicyActionProducer producer;
    private final ApprovalService approvals;

    /**
     * Creates the gate.
     *
     * @param producer  the action producer
     * @param approvals the approval service
     */
    public ApprovalGate(PolicyActionProducer producer, ApprovalService approvals) {
        this.producer = producer;
        this.approvals = approvals;
    }

    /**
     * Whether a fire must wait for approval.
     *
     * @param action the fire
     * @return true for destructive actions that are not dry runs, and for {@code requireApproval: true}
     */
    public static boolean gated(PolicyActionEvent action) {
        boolean destructive = ActionClass.of(action.actionType(), action.actionParameters()).destructive();
        return (destructive && !action.dryRun()) || action.requireApproval();
    }

    /**
     * Routes one fire that no person triggered (an event or a schedule slot).
     *
     * @param action the fire
     * @return what became of it
     */
    public Route submit(PolicyActionEvent action) {
        return submit(action, null);
    }

    /**
     * Routes one fire, naming the person who triggered it when there is one (Q-569). The on-demand
     * evaluation passes the {@code sub} of the administrator who called it, and that person is then
     * an excluded party of the approval request, so they cannot approve what they set in motion.
     *
     * @param action      the fire
     * @param triggeredBy the JWT {@code sub} of the person who triggered the fire; null or blank for
     *                    event and schedule fires
     * @return what became of it
     */
    public Route submit(PolicyActionEvent action, String triggeredBy) {
        if (!gated(action) || !producer.actionsEnabled()) {
            return route(producer.send(action));
        }
        return hold(action, triggeredBy);
    }

    /**
     * Routes one fire that no person triggered and returns its delivery, so the scheduled sweep can
     * keep a slot open until every send of the slot is acknowledged (Q-439).
     *
     * @param action the fire
     * @return what became of it and its delivery
     */
    public Submission submitTracked(PolicyActionEvent action) {
        if (!gated(action) || !producer.actionsEnabled()) {
            PolicyActionProducer.Dispatch dispatch = producer.dispatch(action);
            return new Submission(route(dispatch.outcome()), dispatch.delivered());
        }
        return new Submission(hold(action, null), CompletableFuture.completedFuture(Boolean.TRUE));
    }

    private Route hold(PolicyActionEvent action, String triggeredBy) {
        ActionClass actionClass = ActionClass.of(action.actionType(), action.actionParameters());
        return approvals.hold(action, actionClass, triggeredBy) ? Route.HELD : Route.ALREADY_HELD;
    }

    private static Route route(PolicyActionProducer.Outcome outcome) {
        return switch (outcome) {
            case PUBLISHED -> Route.PUBLISHED;
            case SHADOWED -> Route.SHADOWED;
            case FAILED -> Route.FAILED;
        };
    }
}
