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

package com.dlap.reporting.drift;

import java.time.Instant;

/**
 * One actual asset read observed on the log spine, projected to the fields the
 * drift join needs. Sourced from a {@code DlapLogEvent} with
 * {@code changeType=ACCESS} on {@code dlap.discovery.asset.accessed}
 * (log-event.md): {@code targetResource.id} is the asset, {@code principal.id}
 * the reader, {@code eventTime} the read moment.
 *
 * @param eventId   the stable log-event id ({@code eventId}); dedupe key
 * @param subjectId the DLAP subject of the reading principal: {@code principal.id}
 *                  mapped through the principal identity map, else its normalized key
 * @param assetId   the asset join key: the rolled-up canonical URI of
 *                  {@code targetResource.canonicalUri} (else of {@code targetResource.id}),
 *                  or the id as written when it does not normalize
 * @param readAt    when the read occurred ({@code eventTime}, never the consumer clock)
 */
public record ReadRecord(
        String eventId,
        String subjectId,
        String assetId,
        Instant readAt) {
}
