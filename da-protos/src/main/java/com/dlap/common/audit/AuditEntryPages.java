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

package com.dlap.common.audit;

import java.util.List;

/** A source of audit entries in stream order, read page by page. */
@FunctionalInterface
public interface AuditEntryPages {

    /**
     * Entries after a position, in order.
     *
     * @param streamId the stream
     * @param afterSeq the last position already read; 0 to start at the beginning
     * @param limit    the most entries to return, at least 1
     * @return up to {@code limit} entries with {@code seq > afterSeq}, in ascending order; fewer
     *         than {@code limit} only at the end of the stream
     */
    List<AuditEntry> page(String streamId, long afterSeq, int limit);
}
