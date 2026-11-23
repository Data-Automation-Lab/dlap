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

package com.dlap.adminservice.service;

import com.dlap.adminservice.dto.CloudConnectionDTO;
import com.dlap.adminservice.dto.CloudConnectionTestResultDTO;

import java.util.List;
import java.util.Optional;

public interface ICloudConnectionService {
    List<CloudConnectionDTO> getAllCloudConnections();
    Optional<CloudConnectionDTO> getCloudConnectionById(String id);
    CloudConnectionDTO createCloudConnection(CloudConnectionDTO cloudConnectionDTO);
    CloudConnectionDTO updateCloudConnection(String id, CloudConnectionDTO cloudConnectionDTO);
    void deleteCloudConnection(String id);
    CloudConnectionTestResultDTO testCloudConnection(String id);
    // Method to get decrypted credentials - internal use, not exposed via API directly
    String getDecryptedCredentials(String id);
} 