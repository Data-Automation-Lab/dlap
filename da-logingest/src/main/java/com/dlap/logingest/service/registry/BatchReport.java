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

package com.dlap.logingest.service.registry;

import java.util.List;

/**
 * The outcome of a batch of records.
 *
 * @param records one report per record, in batch order
 */
public record BatchReport(List<RecordReport> records) {

    /**
     * Freezes the list.
     *
     * @param records see the record description
     */
    public BatchReport {
        records = List.copyOf(records);
    }

    /**
     * Counts the quarantined records.
     *
     * @return the count
     */
    public long quarantinedCount() {
        return records.stream().filter(RecordReport::quarantined).count();
    }

    /**
     * Counts events by disposition.
     *
     * @param disposition the disposition
     * @return the count
     */
    public long count(Disposition disposition) {
        return records.stream().flatMap(r -> r.events().stream())
                .filter(e -> e.disposition() == disposition).count();
    }
}
