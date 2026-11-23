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

package com.dlap.autolabel.service;

import java.util.List;
import java.util.UUID;

/**
 * Orchestrates a single labeling pass for one asset:
 *
 * <ol>
 *   <li>Fetch full asset state from da-catalog.</li>
 *   <li>Build a content string from asset metadata.</li>
 *   <li>Call the configured {@code ILLMClient.suggestLabelsWithConfidence}.</li>
 *   <li>Apply suggestions with {@code confidence >=
 *       dlap.autolabel.confidence-threshold} via the catalog Feign client.</li>
 *   <li>Queue sub-threshold suggestions for manual review.</li>
 * </ol>
 *
 * <p>Called from {@code AssetUpsertedConsumer} (Kafka path) and from
 * {@code triggerLabelingRun} batch path. Idempotent: re-running on the
 * same asset re-applies labels that are still confident and re-queues
 * sub-threshold ones (de-dupe in M5+).
 */
public interface IAutoLabelOrchestrator {

    /**
     * Run the labeling pipeline on a single asset.
     *
     * @param assetId catalog asset id.
     * @return number of labels actually applied (above threshold).
     */
    int labelAsset(UUID assetId);

    /**
     * Run the labeling pipeline on a single asset and report which label
     * names were actually applied (above threshold and persisted via the
     * catalog client). Backs the batch labeling-request path so a
     * {@code LabelingJob} can record a real {@code labelSummary}.
     *
     * @param assetId catalog asset id.
     * @return the label names applied; empty when nothing was applied.
     */
    List<String> labelAssetDetailed(UUID assetId);
}
