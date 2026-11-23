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

package com.dlap.fixtures.gen.scale;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps the file names of a public CloudTrail log archive (the flaws.cloud logs, downloaded by the
 * nightly workflow only; nothing of them is stored in this repository) to the keys a trail bucket uses,
 * so the {@code S3_TRAIL_FILES} transport finds them:
 * {@code AWSLogs/<account>/CloudTrail/<region>/yyyy/MM/dd/<file name>}. A file name that does not carry
 * an account, a region and a delivery stamp in CloudTrail's own naming cannot be placed and is reported
 * as unmapped.
 */
public final class FlawsKeys {

    private static final Pattern NAME = Pattern.compile(
            "(\\d{12})_CloudTrail_([a-z]{2}(?:-[a-z]+)+-\\d{1,2})_(\\d{8}T\\d{4}Z)_[A-Za-z0-9]+\\.json\\.gz");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmm'Z'");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    /**
     * A placed file.
     *
     * @param account   the 12 digit account
     * @param region    the region
     * @param delivered the delivery time from the file name
     * @param key       the bucket key
     */
    public record Placed(String account, String region, Instant delivered, String key) {
    }

    private FlawsKeys() {
    }

    /**
     * Places a file by its base name.
     *
     * @param fileName the base name, without a directory
     * @return the placement, or empty when the name is not a CloudTrail file name
     */
    public static Optional<Placed> place(String fileName) {
        Matcher m = NAME.matcher(fileName);
        if (!m.matches()) {
            return Optional.empty();
        }
        LocalDateTime at = LocalDateTime.parse(m.group(3), STAMP);
        String key = "AWSLogs/" + m.group(1) + "/CloudTrail/" + m.group(2) + "/" + DAY.format(at) + "/" + fileName;
        return Optional.of(new Placed(m.group(1), m.group(2), at.toInstant(ZoneOffset.UTC), key));
    }
}
