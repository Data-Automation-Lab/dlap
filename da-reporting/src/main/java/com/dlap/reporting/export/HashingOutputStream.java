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

package com.dlap.reporting.export;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Passes every byte through to the wrapped stream and keeps a SHA-256 digest and a byte count of
 * what was written. It is how a streamed export gets its manifest: the hash covers exactly the
 * bytes the client receives, computed in the same pass that sends them, so the export is never
 * held in memory or on disk.
 *
 * <p>Not thread-safe. Closing it closes the wrapped stream; {@link #sha256Hex()} can be read
 * before or after.
 */
public final class HashingOutputStream extends FilterOutputStream {

    private final MessageDigest digest;
    private long bytes;

    /**
     * Wraps a stream.
     *
     * @param out the stream that receives the bytes
     */
    public HashingOutputStream(OutputStream out) {
        super(out);
        try {
            this.digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every Java platform (Java SE MessageDigest specification).
            throw new IllegalStateException("The JVM has no SHA-256 implementation", e);
        }
    }

    @Override
    public void write(int b) throws IOException {
        out.write(b);
        digest.update((byte) b);
        bytes++;
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        out.write(b, off, len);
        digest.update(b, off, len);
        bytes += len;
    }

    /**
     * The SHA-256 of the bytes written so far, lower-case hexadecimal. Reading it does not reset
     * the digest.
     *
     * @return 64 hexadecimal characters
     */
    public String sha256Hex() {
        try {
            return HexFormat.of().formatHex(((MessageDigest) digest.clone()).digest());
        } catch (CloneNotSupportedException e) {
            throw new IllegalStateException("The SHA-256 implementation cannot be copied", e);
        }
    }

    /**
     * The number of bytes written so far.
     *
     * @return the byte count
     */
    public long bytesWritten() {
        return bytes;
    }
}
