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

package com.dlap.logingest.transport;

import java.util.Objects;

/**
 * An object of a file transport could not be read (permissions, a key the reader may not use, a
 * corrupt or oversize shard, a network failure). A transport throws it from
 * {@link RawBatchIterator#next()} so that {@link StreamRunner} knows which object failed.
 *
 * <p>Contract for the iterator that throws it: it is positioned past the failed object, so that the
 * next call continues with the following object. The runner stops the partition on the first
 * failures, leaving the cursor where it was, so the next poll reads the object again; once the
 * object has failed {@code dlap.logingest.transports.max-read-attempts} times it quarantines the
 * object as {@code READ_FAILED}, checkpoints it as done and keeps reading this iterator (Q-665).
 *
 * <p>The message and the cause are logged; neither is stored in the quarantine record, which names
 * only the exception class.
 */
public class ObjectReadException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient RawBatch.ObjectRef object;
    private final String uri;

    /**
     * Creates the exception.
     *
     * @param object the object that failed
     * @param uri    where the object lives, for the quarantine record's reference (for example
     *               {@code s3://bucket/key})
     * @param cause  why
     */
    public ObjectReadException(RawBatch.ObjectRef object, String uri, Throwable cause) {
        super("cannot read object " + Objects.requireNonNull(object, "object").key() + ": "
                + (cause == null ? "unknown" : cause.getClass().getSimpleName() + ": " + cause.getMessage()), cause);
        this.object = object;
        this.uri = uri == null || uri.isBlank() ? object.key() : uri;
    }

    /**
     * The object that failed.
     *
     * @return the object reference
     */
    public RawBatch.ObjectRef object() {
        return object;
    }

    /**
     * Where the object lives.
     *
     * @return the URI, or the object key when the transport gave none
     */
    public String uri() {
        return uri;
    }
}
