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

package com.dlap.discovery.client.dto;

import jakarta.validation.constraints.*;
import java.io.Serializable;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import com.dlap.discovery.common.model.enumeration.ObjType;

/**
 * A DTO for the {@code ObjInfo} entity.
 */
@SuppressWarnings("common-java:DuplicatedBlocks")
public class ObjInfoDTO implements Serializable {

    private Long id;

    @NotNull(message = "must not be null")
    @Size(min = 4, max = 300)
    private String name;

    @Size(min = 4, max = 1000)
    private String path;

    private ObjType type;

    private Set<DataAssetDTO> sources = new HashSet<>();

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public ObjType getType() {
        return type;
    }

    public void setType(ObjType type) {
        this.type = type;
    }

    public Set<DataAssetDTO> getSources() {
        return sources;
    }

    public void setSources(Set<DataAssetDTO> sources) {
        this.sources = sources;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ObjInfoDTO)) {
            return false;
        }

        ObjInfoDTO objInfoDTO = (ObjInfoDTO) o;
        if (this.id == null) {
            return false;
        }
        return Objects.equals(this.id, objInfoDTO.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.id);
    }

    // prettier-ignore
    @Override
    public String toString() {
        return "ObjInfoDTO{" +
            "id=" + getId() +
            ", name='" + getName() + "'" +
            ", path='" + getPath() + "'" +
            ", type='" + getType() + "'" +
            ", sources=" + getSources() +
            "}";
    }
}
