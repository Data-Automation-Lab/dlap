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

package com.dlap.policyengine.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/policyengine/policies/draft-from-text}.
 *
 * @param text Plain-English description of the intended policy. Required.
 * @param owner Optional owner (email or team handle) written into
 *     {@code metadata.owner} of the drafted DSL. When omitted the draft has no
 *     owner and the human completing it in the visual builder must supply one.
 */
public record PolicyTextDraftRequestDTO(

        @NotBlank(message = "text is required")
        @Size(max = 4000, message = "text must not exceed 4000 characters")
        String text,

        @Size(max = 255, message = "owner must not exceed 255 characters")
        String owner) {
}
