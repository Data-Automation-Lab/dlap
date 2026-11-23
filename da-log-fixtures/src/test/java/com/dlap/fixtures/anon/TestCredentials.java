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

package com.dlap.fixtures.anon;

import java.util.Random;

/**
 * Builds credential-shaped values for the tests at run time. The values are never written as literals,
 * so the source tree holds nothing that a secret scanner would report, and they are the same on every
 * run because the generator is seeded.
 */
final class TestCredentials {

    /** The header of a current STS session token (two version characters, then {@code origin_ec}). */
    static final String STS_HEAD = "IQoJb3JpZ2luX2Vj";

    private static final String BASE64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

    private TestCredentials() {
    }

    /**
     * Returns a session token: the STS header, then random base64 up to the length.
     *
     * @param length the total length
     * @param seed   makes two tokens differ
     * @return the token
     */
    static String sessionToken(int length, long seed) {
        return STS_HEAD + base64(length - STS_HEAD.length(), seed);
    }

    /**
     * Returns a secret access key: 40 characters of random base64.
     *
     * @param seed makes two keys differ
     * @return the key
     */
    static String secretKey(long seed) {
        return base64(40, seed);
    }

    private static String base64(int length, long seed) {
        Random random = new Random(seed);
        StringBuilder out = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            out.append(BASE64.charAt(random.nextInt(BASE64.length())));
        }
        return out.toString();
    }
}
