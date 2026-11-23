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

package com.dlap.logingest.providers.aws.s3;

import java.time.Instant;
import java.util.Objects;

/**
 * One object of a log bucket as a listing returns it.
 *
 * @param key          the object key
 * @param etag         the entity tag without its quotes, {@code ""} when the store has none
 * @param size         the size in bytes
 * @param lastModified when S3 stored the object; the records' {@code observed_time}, or {@code null}
 */
public record S3LogObject(String key, String etag, long size, Instant lastModified) {

    /**
     * Validates the object.
     *
     * @param key          see the record description
     * @param etag         see the record description
     * @param size         see the record description
     * @param lastModified see the record description
     */
    public S3LogObject {
        Objects.requireNonNull(key, "key");
        etag = unquote(etag);
    }

    /**
     * Strips the quotes S3 puts around an entity tag.
     *
     * @param etag the tag as listed, or {@code null}
     * @return the bare tag, {@code ""} for none
     */
    static String unquote(String etag) {
        if (etag == null) {
            return "";
        }
        String t = etag.strip();
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            return t.substring(1, t.length() - 1);
        }
        return t;
    }
}
