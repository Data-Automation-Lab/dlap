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

package com.dlap.common.model.entity;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Composite ID class for AssetLabelAssignment entity
 * 
 * Represents the composite primary key consisting of assetId and labelId.
 * 
 * @author DLAP Team
 * @version 1.0
 * @since 1.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AssetLabelAssignmentId implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Asset ID part of the composite key
     */
    private UUID assetId;

    /**
     * Label ID part of the composite key
     */
    private UUID labelId;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AssetLabelAssignmentId that = (AssetLabelAssignmentId) o;
        return Objects.equals(assetId, that.assetId) && Objects.equals(labelId, that.labelId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(assetId, labelId);
    }

    @Override
    public String toString() {
        return "AssetLabelAssignmentId{" +
                "assetId=" + assetId +
                ", labelId=" + labelId +
                '}';
    }
} 