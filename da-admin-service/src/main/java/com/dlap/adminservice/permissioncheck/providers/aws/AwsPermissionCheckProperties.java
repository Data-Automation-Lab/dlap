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

package com.dlap.adminservice.permissioncheck.providers.aws;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The AWS block of the permission self-check, {@code dlap.permission-check.aws.*}. One entry per
 * executor under {@code executors}, for example
 * {@code dlap.permission-check.aws.executors.delete.role-arn} and {@code .buckets}.
 */
@ConfigurationProperties(prefix = "dlap.permission-check.aws")
public class AwsPermissionCheckProperties {

    private String iamEndpoint = "https://iam.amazonaws.com/";
    private String signingRegion = "us-east-1";
    private String partition = "aws";
    private String imdsEndpoint = "http://169.254.169.254";
    private Map<String, Executor> executors = new LinkedHashMap<>();

    /** @return the IAM endpoint (another partition has its own, for example {@code https://iam.cn-north-1.amazonaws.com.cn/}) */
    public String getIamEndpoint() {
        return iamEndpoint;
    }

    /** @param iamEndpoint the IAM endpoint */
    public void setIamEndpoint(String iamEndpoint) {
        this.iamEndpoint = iamEndpoint;
    }

    /** @return the SigV4 region of the IAM endpoint */
    public String getSigningRegion() {
        return signingRegion;
    }

    /** @param signingRegion the SigV4 region of the IAM endpoint */
    public void setSigningRegion(String signingRegion) {
        this.signingRegion = signingRegion;
    }

    /** @return the ARN partition of the buckets, {@code aws} by default */
    public String getPartition() {
        return partition;
    }

    /** @param partition the ARN partition */
    public void setPartition(String partition) {
        this.partition = partition;
    }

    /** @return the instance metadata endpoint for instance profile credentials */
    public String getImdsEndpoint() {
        return imdsEndpoint;
    }

    /** @param imdsEndpoint the instance metadata endpoint */
    public void setImdsEndpoint(String imdsEndpoint) {
        this.imdsEndpoint = imdsEndpoint;
    }

    /** @return the Actor of each executor ({@code delete}, {@code archive}) */
    public Map<String, Executor> getExecutors() {
        return executors;
    }

    /** @param executors the Actor of each executor */
    public void setExecutors(Map<String, Executor> executors) {
        this.executors = executors;
    }

    /** One executor's Actor role and the buckets it was granted on. */
    public static class Executor {
        private String roleArn = "";
        private List<String> buckets = new ArrayList<>();
        private boolean objectActions;

        /** @return the Actor role ARN to simulate; blank skips the executor */
        public String getRoleArn() {
            return roleArn;
        }

        /** @param roleArn the Actor role ARN */
        public void setRoleArn(String roleArn) {
            this.roleArn = roleArn;
        }

        /** @return the bucket names the role was applied with */
        public List<String> getBuckets() {
            return buckets;
        }

        /** @param buckets the bucket names */
        public void setBuckets(List<String> buckets) {
            this.buckets = buckets;
        }

        /** @return whether the role was applied with object actions on */
        public boolean isObjectActions() {
            return objectActions;
        }

        /** @param objectActions whether the role was applied with object actions on */
        public void setObjectActions(boolean objectActions) {
            this.objectActions = objectActions;
        }
    }
}
