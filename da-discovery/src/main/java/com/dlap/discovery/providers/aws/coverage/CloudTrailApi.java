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

package com.dlap.discovery.providers.aws.coverage;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The CloudTrail and STS reads of the AWS coverage collector. Each method returns the API response
 * as JSON in the shape of the CloudTrail API reference, and throws
 * {@link com.dlap.discovery.coverage.MissingPermissionException} when the call is refused.
 */
public interface CloudTrailApi {

    /**
     * DescribeTrails with {@code includeShadowTrails=true}.
     *
     * @param region the region
     * @return the response
     */
    JsonNode describeTrails(String region);

    /**
     * GetEventSelectors.
     *
     * @param trailArn   the trail
     * @param homeRegion the trail's home region
     * @return the response
     */
    JsonNode getEventSelectors(String trailArn, String homeRegion);

    /**
     * GetTrailStatus.
     *
     * @param trailArn   the trail
     * @param homeRegion the trail's home region
     * @return the response
     */
    JsonNode getTrailStatus(String trailArn, String homeRegion);

    /**
     * The account of the credentials.
     *
     * @param region the STS region
     * @return the 12-digit account id
     */
    String callerAccount(String region);
}
