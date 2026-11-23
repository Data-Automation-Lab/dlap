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

package com.dlap.logingest.repository;

import com.dlap.logingest.model.ConfigOrigin;
import com.dlap.logingest.model.SourceBindingEntity;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Persistence layer for {@link SourceBindingEntity}, the mirror of the bindings file.
 */
@Repository
public interface SourceBindingRepository extends JpaRepository<SourceBindingEntity, String> {

    /**
     * Bindings of the given origins.
     *
     * @param origins the origins
     * @return the rows
     */
    List<SourceBindingEntity> findByConfigOriginIn(Collection<ConfigOrigin> origins);
}
