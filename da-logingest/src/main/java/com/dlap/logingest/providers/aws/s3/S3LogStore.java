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

import com.dlap.logingest.transport.StreamContext;
import java.util.List;

/**
 * Lists and reads the objects of a log bucket. {@link SdkS3LogStore} wraps the S3 client; tests hand
 * the transports an in-memory store. Errors are unchecked and leave the stream's cursor where it was,
 * so the next poll reads the object again.
 */
public interface S3LogStore {

    /**
     * Lists the objects under a prefix, in key order.
     *
     * @param context    the stream, for its client settings
     * @param bucket     the bucket
     * @param prefix     the key prefix, {@code ""} for the whole bucket
     * @param startAfter list only keys after this one, {@code ""} from the start
     * @param stopAfter  stop at the first key after this one, {@code ""} to list to the end
     * @return the objects
     */
    List<S3LogObject> list(StreamContext context, String bucket, String prefix, String startAfter, String stopAfter);

    /**
     * Lists the folders directly under a prefix ({@code /} delimited).
     *
     * @param context the stream
     * @param bucket  the bucket
     * @param prefix  the prefix, ending in {@code /}
     * @return the common prefixes, each ending in {@code /}
     */
    List<String> folders(StreamContext context, String bucket, String prefix);

    /**
     * Reads the listed version of an object, at most {@code maxBytes} from its start.
     *
     * @param context  the stream
     * @param bucket   the bucket
     * @param object   the object as listed; a store reads that version only (its etag)
     * @param maxBytes the most bytes to return
     * @return the object's bytes, or its first {@code maxBytes} bytes
     */
    byte[] read(StreamContext context, String bucket, S3LogObject object, long maxBytes);
}
