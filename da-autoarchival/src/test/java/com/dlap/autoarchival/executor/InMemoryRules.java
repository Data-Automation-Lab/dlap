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

package com.dlap.autoarchival.executor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** A map-backed {@link ArchiveRuleRepository} for tests that do not need JPA. */
public final class InMemoryRules {

    private InMemoryRules() {
    }

    /**
     * Creates the repository.
     *
     * @param store the rows, keyed by rule id; the test may inspect it
     * @return the repository
     */
    @SuppressWarnings("unchecked")
    public static ArchiveRuleRepository over(Map<String, ArchiveRuleEntity> store) {
        ArchiveRuleRepository repo = mock(ArchiveRuleRepository.class);
        when(repo.findByApprovalId(anyString())).thenAnswer(inv -> store.values().stream()
                .filter(r -> r.getApprovalId().equals(inv.getArgument(0))).findFirst());
        when(repo.findByFireId(anyString())).thenAnswer(inv -> store.values().stream()
                .filter(r -> inv.getArgument(0).equals(r.getFireId())).findFirst());
        when(repo.findByTaskId(anyString())).thenAnswer(inv -> store.values().stream()
                .filter(r -> r.getTaskId().equals(inv.getArgument(0))).toList());
        when(repo.findByProviderAndScopeBucketAndStatusIn(anyString(), anyString(), any(Collection.class)))
                .thenAnswer(inv -> store.values().stream()
                        .filter(r -> r.getProvider().equals(inv.getArgument(0))
                                && r.getScopeBucket().equals(inv.getArgument(1))
                                && ((Collection<String>) inv.getArgument(2)).contains(r.getStatus())).toList());
        when(repo.findById(anyString())).thenAnswer(inv -> Optional.ofNullable(store.get((String) inv.getArgument(0))));
        // The database's conditional update: of any callers for one PLANNED row, exactly one sees 1.
        when(repo.claimPlanned(anyString(), any(Instant.class))).thenAnswer(inv -> {
            synchronized (store) {
                ArchiveRuleEntity r = store.get((String) inv.getArgument(0));
                if (r == null || !ArchiveRuleEntity.STATUS_PLANNED.equals(r.getStatus())) {
                    return 0;
                }
                r.markClaimed(inv.getArgument(1));
                return 1;
            }
        });
        when(repo.endIfPlanned(anyString(), anyString(), any(), any(Instant.class))).thenAnswer(inv -> {
            synchronized (store) {
                ArchiveRuleEntity r = store.get((String) inv.getArgument(0));
                if (r == null || !ArchiveRuleEntity.STATUS_PLANNED.equals(r.getStatus())) {
                    return 0;
                }
                r.markFailed(inv.getArgument(1), inv.getArgument(2), inv.getArgument(3));
                return 1;
            }
        });
        // Q-712: the claimer's own outcome writes are conditional on EXECUTING.
        when(repo.finishIfExecuting(anyString(), anyString(), any(), org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyBoolean(), any(Instant.class))).thenAnswer(inv -> {
                    synchronized (store) {
                        ArchiveRuleEntity r = store.get((String) inv.getArgument(0));
                        if (r == null || !ArchiveRuleEntity.STATUS_EXECUTING.equals(r.getStatus())) {
                            return 0;
                        }
                        r.markApplied(inv.getArgument(1), inv.getArgument(2), inv.getArgument(3),
                                inv.getArgument(4), inv.getArgument(5));
                        return 1;
                    }
                });
        when(repo.failIfExecuting(anyString(), any(), any(Instant.class))).thenAnswer(inv -> {
            synchronized (store) {
                ArchiveRuleEntity r = store.get((String) inv.getArgument(0));
                if (r == null || !ArchiveRuleEntity.STATUS_EXECUTING.equals(r.getStatus())) {
                    return 0;
                }
                r.markFailed(ArchiveRuleEntity.STATUS_FAILED, inv.getArgument(1), inv.getArgument(2));
                return 1;
            }
        });
        when(repo.markExecutedIf(anyString(), anyString(), any(), any(), any(Instant.class), any(), any(Instant.class)))
                .thenAnswer(inv -> {
                    synchronized (store) {
                        ArchiveRuleEntity r = store.get((String) inv.getArgument(0));
                        if (r == null || !r.getStatus().equals(inv.getArgument(1))) {
                            return 0;
                        }
                        r.markExecuted(inv.getArgument(2), inv.getArgument(3), inv.getArgument(4), inv.getArgument(5),
                                inv.getArgument(6));
                        return 1;
                    }
                });
        when(repo.markRemovedIf(anyString(), anyString(), any(Instant.class))).thenAnswer(inv -> {
            synchronized (store) {
                ArchiveRuleEntity r = store.get((String) inv.getArgument(0));
                if (r == null || !r.getStatus().equals(inv.getArgument(1))) {
                    return 0;
                }
                r.markRemoved(inv.getArgument(2));
                return 1;
            }
        });
        when(repo.resetExecutingToApplied(anyString(), org.mockito.ArgumentMatchers.anyBoolean(), any(Instant.class)))
                .thenAnswer(inv -> {
                    synchronized (store) {
                        ArchiveRuleEntity r = store.get((String) inv.getArgument(0));
                        if (r == null || !ArchiveRuleEntity.STATUS_EXECUTING.equals(r.getStatus())) {
                            return 0;
                        }
                        r.markApplied(ArchiveRuleEntity.STATUS_APPLIED, r.getRuleDocument(), false,
                                inv.getArgument(1), r.getUpdatedAt());
                        return 1;
                    }
                });
        when(repo.resetExecutingToFailed(anyString(), anyString(), any(Instant.class))).thenAnswer(inv -> {
            synchronized (store) {
                ArchiveRuleEntity r = store.get((String) inv.getArgument(0));
                if (r == null || !ArchiveRuleEntity.STATUS_EXECUTING.equals(r.getStatus())) {
                    return 0;
                }
                r.markFailed(ArchiveRuleEntity.STATUS_FAILED, inv.getArgument(1), inv.getArgument(2));
                return 1;
            }
        });
        when(repo.findLiveScope(anyString(), anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyInt(), any(Collection.class), anyString()))
                .thenAnswer(inv -> store.values().stream()
                        .filter(r -> r.getProvider().equals(inv.getArgument(0))
                                && r.getScopeBucket().equals(inv.getArgument(1))
                                && r.getScopePrefix().equals(inv.getArgument(2))
                                && r.getNativeClass().equals(inv.getArgument(3))
                                && r.getAfterDays() == (int) inv.getArgument(4)
                                && ArchiveRuleEntity.MODE_NATIVE_RULE.equals(r.getMode()) && !r.isMock()
                                && ((Collection<String>) inv.getArgument(5)).contains(r.getStatus())
                                && !r.getRuleId().equals(inv.getArgument(6))).toList());
        when(repo.saveAndFlush(any())).thenAnswer(inv -> {
            ArchiveRuleEntity r = inv.getArgument(0);
            store.put(r.getRuleId(), r);
            return r;
        });
        when(repo.save(any())).thenAnswer(inv -> {
            ArchiveRuleEntity r = inv.getArgument(0);
            store.put(r.getRuleId(), r);
            return r;
        });
        return repo;
    }

    /**
     * Creates the repository and its store.
     *
     * @return the repository over a fresh store
     */
    public static ArchiveRuleRepository empty() {
        return over(new HashMap<>());
    }
}
