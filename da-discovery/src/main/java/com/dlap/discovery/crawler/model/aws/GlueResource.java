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

package com.dlap.discovery.crawler.model.aws;

import com.dlap.discovery.common.constants.AWSConstants;
import com.dlap.discovery.common.model.ResourceType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * AWS Glue Data Catalog table (JOINED-inheritance subtype of {@link AwsResource}).
 *
 * <p>A Glue table is the catalog's view of a dataset - the high-signal
 * governance target for discovery: it carries the database it belongs to,
 * its storage location (often an S3 path), table type, and the
 * {@code classification} that crawlers infer (csv, parquet, json, …).
 */
@Entity
@Table(name = "glue_resource")
@Getter
@Setter
@NoArgsConstructor
public class GlueResource extends AwsResource {

    /** Glue database (catalog namespace) the table belongs to. */
    @Column(name = "glue_database")
    private String database;

    /** Table type (e.g. {@code EXTERNAL_TABLE}, {@code VIRTUAL_VIEW}). */
    @Column(name = "glue_table_type")
    private String tableType;

    /** Storage location backing the table (often an {@code s3://} URI). */
    @Column(name = "glue_location")
    private String location;

    /** Inferred data classification (e.g. {@code parquet}, {@code csv}, {@code json}). */
    @Column(name = "glue_classification")
    private String classification;

    public GlueResource(ResourceType resourceTypeRecord, String glueTableId, String name) {
        super(resourceTypeRecord, glueTableId, name);
        String typeId = getTypeId();
        if (!AWSConstants.RESOURCE_TYPE_GLUE_TABLE.equals(typeId)) {
            throw new IllegalArgumentException(
                    "GlueResource requires ResourceType " + AWSConstants.RESOURCE_TYPE_GLUE_TABLE
                            + " but got: " + typeId);
        }
    }
}
