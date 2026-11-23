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

package com.dlap.discovery.client.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dlap.discovery.common.model.CloudResource;
import com.dlap.discovery.crawler.model.gcp.CloudSQLResource;
import com.dlap.discovery.crawler.model.gcp.ComputeResource;
import com.dlap.discovery.crawler.model.gcp.GcsResource;

public class ResourceMetadataUtil {
    private static final Logger log = LoggerFactory.getLogger(ResourceMetadataUtil.class);

    public static String getResourceStatus(CloudResource resource) {
        if (resource instanceof ComputeResource) {
            return ((ComputeResource) resource).getStatus();
        } else if (resource instanceof CloudSQLResource) {
            return ((CloudSQLResource) resource).getStatus();
        }
        return "Unknown";
    }

    public static String getResourceOwner(CloudResource resource) {
        Map<String, String> tags = resource.getTags();
        return tags != null ? tags.getOrDefault("owner", "Unknown") : "Unknown";
    }

    public static String getContentTypeFromFileName(String fileName) {
        if (fileName == null) return null;
        
        // Convert to lowercase for case-insensitive comparison
        fileName = fileName.toLowerCase();
        
        // Check for specific file patterns first
        if (fileName.contains(".json")) {
            return "JSON";
        } else if (fileName.contains(".csv")) {
            return "CSV";
        } else if (fileName.contains(".tsx") || fileName.contains(".ts")) {
            return "TYPESCRIPT";
        } else if (fileName.contains(".js")) {
            return "JAVASCRIPT";
        } else if (fileName.contains(".html")) {
            return "HTML";
        } else if (fileName.contains(".xml")) {
            return "XML";
        } else if (fileName.contains(".pdf")) {
            return "PDF";
        } else if (fileName.contains(".png")) {
            return "PNG";
        } else if (fileName.contains(".jpg") || fileName.contains(".jpeg")) {
            return "JPEG";
        } else if (fileName.contains(".gif")) {
            return "GIF";
        } else if (fileName.contains(".zip")) {
            return "ZIP";
        } else if (fileName.contains(".tar")) {
            return "TAR";
        } else if (fileName.contains(".gz")) {
            return "GZIP";
        } else if (fileName.contains(".docx")) {
            return "DOCX";
        } else if (fileName.contains(".doc")) {
            return "DOC";
        } else if (fileName.contains(".xlsx")) {
            return "XLSX";
        } else if (fileName.contains(".xls")) {
            return "XLS";
        } else if (fileName.contains(".txt")) {
            return "TXT";
        }
        
        return "UNKNOWN";
    }

    public static Map<String, Object> getResourceMetadata(CloudResource resource) {
        Map<String, Object> metadata = new HashMap<>();
        
        if (resource instanceof GcsResource) {
            GcsResource gcsResource = (GcsResource) resource;
            metadata.put("size", formatBytes(gcsResource.getSizeBytes()));
            // Get content type from file name if not set
            String contentType = gcsResource.getContentType();
            if (contentType == null) {
                contentType = getContentTypeFromFileName(resource.getName());
            }
            metadata.put("contentType", contentType);
            metadata.put("storageClass", gcsResource.getStorageClass());
            metadata.put("isPublic", gcsResource.isPublic());
            metadata.put("format", resource.getTypeId());
            metadata.put("parentResource", gcsResource.getParentResource());
        } else if (resource.getTypeId().equals("gcp_bigquery_table")) {
            // Get size from technical metadata for BigQuery tables
            Map<String, String> techMetadata = resource.getTechnicalMetadata();
            if (techMetadata != null) {
                String numBytes = techMetadata.get("numBytes");
                String numRows = techMetadata.get("numRows");
                if (numBytes != null) {
                    try {
                        long sizeBytes = Long.parseLong(numBytes);
                        metadata.put("size", formatBytes(sizeBytes));
                    } catch (NumberFormatException e) {
                        metadata.put("size", "0 B");
                    }
                } else {
                    metadata.put("size", "0 B");
                }
                if (numRows != null) {
                    metadata.put("rowCount", numRows);
                }
                metadata.putAll(techMetadata);
            } else {
                metadata.put("size", "0 B");
            }
        } else {
            Map<String, String> techMetadata = resource.getTechnicalMetadata();
            if (techMetadata != null) {
                metadata.putAll(techMetadata);
            }
        }
        return metadata;
    }

    public static List<String> getResourceTags(CloudResource resource) {
        return new ArrayList<>(resource.getTags().keySet());
    }

    public static Map<String, Object> getResourceQuality(CloudResource resource) {
        Map<String, Object> quality = new HashMap<>();
        List<String> issues = new ArrayList<>();
        int score = 100; // Start with perfect score
        int maxScore = 100;
        
        // Get quality metrics from technical metadata
        Map<String, String> techMetadata = resource.getTechnicalMetadata();
        
        // Base quality checks applicable to all resources
        if (resource.getName() == null || resource.getName().isBlank()) {
            issues.add("Missing resource name");
            score -= 10;
        }
        
        if (resource.getDescription() == null || resource.getDescription().isBlank()) {
            issues.add("Missing description");
            score -= 5;
        }
        
        if (resource.getTags() == null || resource.getTags().isEmpty()) {
            issues.add("No tags defined");
            score -= 5;
        }
        
        // Provider-specific quality checks
        switch (resource.getCloudProviderEnum()) {
            case GCP:
                score += calculateGCPResourceQuality(resource, issues);
                break;
            case AWS:
                score += calculateAWSResourceQuality(resource, issues);
                break;
            case AZURE:
                score += calculateAzureResourceQuality(resource, issues);
                break;
            case OCI:
                score += calculateOracleResourceQuality(resource, issues);
                break;
            default:
                issues.add("Unknown cloud provider");
                score -= 10;
        }
        
        // Ensure score stays within bounds
        score = Math.max(0, Math.min(maxScore, score));
        
        quality.put("score", score);
        quality.put("issues", issues);
        quality.put("maxScore", maxScore);
        
        // Add quality level based on score
        String qualityLevel;
        if (score >= 90) {
            qualityLevel = "Excellent";
        } else if (score >= 75) {
            qualityLevel = "Good";
        } else if (score >= 60) {
            qualityLevel = "Fair";
        } else {
            qualityLevel = "Poor";
        }
        quality.put("level", qualityLevel);
        
        return quality;
    }

    private static int calculateGCPResourceQuality(CloudResource resource, List<String> issues) {
        int score = 0;
        Map<String, String> techMetadata = resource.getTechnicalMetadata();
        
        // Check resource type specific quality
        if (resource instanceof GcsResource) {
            GcsResource gcsResource = (GcsResource) resource;
            
            // Check size
            if (gcsResource.getSizeBytes() <= 0) {
                issues.add("Invalid or missing file size");
                score -= 5;
            }
            
            // Check content type
            if (gcsResource.getContentType() == null || gcsResource.getContentType().isBlank()) {
                issues.add("Missing content type");
                score -= 5;
            }
            
            // Check storage class
            if (gcsResource.getStorageClass() == null || gcsResource.getStorageClass().isBlank()) {
                issues.add("Missing storage class");
                score -= 5;
            }
            
            // Check public access
            if (gcsResource.isPublic()) {
                issues.add("Resource has public access");
                score -= 15;
            }
            
            // Check versioning
            String versioningEnabled = techMetadata.get("versioningEnabled");
            if (versioningEnabled != null && versioningEnabled.equals("true")) {
                score += 10;
            }
            
        } else if (resource instanceof CloudSQLResource) {
            // Check table size
            String numBytes = techMetadata.get("numBytes");
            String numRows = techMetadata.get("numRows");
            
            if (numBytes == null || numRows == null) {
                issues.add("Missing table size information");
                score -= 5;
            } else {
                try {
                    long bytes = Long.parseLong(numBytes);
                    long rows = Long.parseLong(numRows);
                    if (bytes > 0 && rows > 0) {
                        score += 10;
                    }
                } catch (NumberFormatException e) {
                    issues.add("Invalid table size format");
                    score -= 5;
                }
            }
            
            // Check table type
            String tableType = techMetadata.get("tableType");
            if (tableType != null && !tableType.equals("TABLE")) {
                issues.add("Non-standard table type: " + tableType);
                score -= 5;
            }
        }
        
        return score;
    }

    private static int calculateAWSResourceQuality(CloudResource resource, List<String> issues) {
        int score = 0;
        Map<String, String> techMetadata = resource.getTechnicalMetadata();
        
        // Add AWS-specific quality checks
        String region = techMetadata.get("region");
        if (region == null || region.isBlank()) {
            issues.add("Missing AWS region");
            score -= 5;
        }
        
        return score;
    }

    private static int calculateAzureResourceQuality(CloudResource resource, List<String> issues) {
        int score = 0;
        Map<String, String> techMetadata = resource.getTechnicalMetadata();
        
        // Add Azure-specific quality checks
        String location = techMetadata.get("location");
        if (location == null || location.isBlank()) {
            issues.add("Missing Azure location");
            score -= 5;
        }
        
        return score;
    }

    private static int calculateOracleResourceQuality(CloudResource resource, List<String> issues) {
        int score = 0;
        Map<String, String> techMetadata = resource.getTechnicalMetadata();
        
        // Add Oracle-specific quality checks
        String compartmentId = techMetadata.get("compartmentId");
        if (compartmentId == null || compartmentId.isBlank()) {
            issues.add("Missing compartment ID");
            score -= 5;
        }
        
        String lifecycleState = techMetadata.get("lifecycleState");
        if (lifecycleState != null && lifecycleState.equals("TERMINATED")) {
            issues.add("Resource is terminated");
            score -= 20;
        }
        
        return score;
    }

    public static String formatSize(CloudResource resource) {
        if (resource instanceof GcsResource) {
            return formatBytes(((GcsResource) resource).getSizeBytes());
        }
        
        // Get size from technical metadata for other resource types
        Map<String, String> techMetadata = resource.getTechnicalMetadata();
        if (techMetadata != null && techMetadata.containsKey("sizeBytes")) {
            try {
                long sizeBytes = Long.parseLong(techMetadata.get("sizeBytes"));
                return formatBytes(sizeBytes);
            } catch (NumberFormatException e) {
                log.warn("Invalid size format for resource {}: {}", resource.getId(), e.getMessage());
            }
        }
        return "0 B";
    }

    public static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp-1) + "";
        return String.format("%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }
} 