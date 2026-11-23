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

package com.dlap.autoarchival.model;

/**
 * DTO to hold the results of an archival operation.
 */
public class ArchivalResult {

    private String archiveId;          // Unique identifier for the created archive (e.g., S3 Object Version ID, Glacier Archive ID).
    private String storageLocation;    // URI or path to the archived object (e.g., s3://bucket/key, gs://bucket/object).
    private long sizeBytes;            // Size of the archived data in bytes.
    private String checksum;           // Checksum (e.g., MD5, SHA256) of the archived data, if available.
    private String storageClass;       // Actual storage class used (e.g., GLACIER, DEEP_ARCHIVE).
    private java.time.OffsetDateTime archivalTimestamp; // Timestamp when the archival was completed.

    // Constructors
    public ArchivalResult() {
    }

    public ArchivalResult(String archiveId, String storageLocation, long sizeBytes, String checksum, String storageClass, java.time.OffsetDateTime archivalTimestamp) {
        this.archiveId = archiveId;
        this.storageLocation = storageLocation;
        this.sizeBytes = sizeBytes;
        this.checksum = checksum;
        this.storageClass = storageClass;
        this.archivalTimestamp = archivalTimestamp;
    }

    // Getters and Setters
    public String getArchiveId() {
        return archiveId;
    }

    public void setArchiveId(String archiveId) {
        this.archiveId = archiveId;
    }

    public String getStorageLocation() {
        return storageLocation;
    }

    public void setStorageLocation(String storageLocation) {
        this.storageLocation = storageLocation;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public String getChecksum() {
        return checksum;
    }

    public void setChecksum(String checksum) {
        this.checksum = checksum;
    }

    public String getStorageClass() {
        return storageClass;
    }

    public void setStorageClass(String storageClass) {
        this.storageClass = storageClass;
    }

    public java.time.OffsetDateTime getArchivalTimestamp() {
        return archivalTimestamp;
    }

    public void setArchivalTimestamp(java.time.OffsetDateTime archivalTimestamp) {
        this.archivalTimestamp = archivalTimestamp;
    }

    @Override
    public String toString() {
        return "ArchivalResult{" +
                "archiveId='" + archiveId + '\'' +
                ", storageLocation='" + storageLocation + '\'' +
                ", sizeBytes=" + sizeBytes +
                ", checksum='" + checksum + '\'' +
                ", storageClass='" + storageClass + '\'' +
                ", archivalTimestamp=" + archivalTimestamp +
                '}';
    }
} 