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

import java.util.UUID;

/**
 * A stateless, seeded source of pseudo-random values. Every value is a pure function of
 * {@code (seed, index, lane)}, so a record can be rebuilt on its own, in any order and on any thread,
 * and the whole stream stays reproducible. The mixer is the SplitMix64 finalizer (public domain,
 * Sebastiano Vigna), written here from its published constants.
 */
public final class ScaleRandom {

    private static final long GOLDEN = 0x9E3779B97F4A7C15L;

    private ScaleRandom() {
    }

    /**
     * Mixes a seed, an index and a lane into 64 well-spread bits.
     *
     * @param seed  the run seed
     * @param index the record index
     * @param lane  which value of the record is drawn; different lanes are independent
     * @return the mixed value
     */
    public static long mix(long seed, long index, int lane) {
        long z = seed + GOLDEN * (index + 1) + 0xBF58476D1CE4E5B9L * (lane + 1L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /**
     * A value in {@code [0, bound)}.
     *
     * @param seed  the run seed
     * @param index the record index
     * @param lane  the value's lane
     * @param bound the exclusive upper bound, at least 1
     * @return the value
     */
    public static int below(long seed, long index, int lane, int bound) {
        return (int) Long.remainderUnsigned(mix(seed, index, lane), bound);
    }

    /**
     * A version 4 UUID, built from two mixed words, so ids are unique in practice and repeatable.
     *
     * @param seed  the run seed
     * @param index the record index
     * @param lane  the id's lane (two consecutive lanes are used)
     * @return the UUID text
     */
    public static String uuid(long seed, long index, int lane) {
        long hi = (mix(seed, index, lane) & 0xFFFFFFFFFFFF0FFFL) | 0x0000000000004000L;
        long lo = (mix(seed, index, lane + 1) & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(hi, lo).toString();
    }

    /**
     * Upper case hexadecimal text of a mixed value.
     *
     * @param seed   the run seed
     * @param index  the record index
     * @param lane   the value's lane
     * @param digits the number of hex digits, 1 to 16
     * @return the text
     */
    public static String hex(long seed, long index, int lane, int digits) {
        String full = String.format("%016X", mix(seed, index, lane));
        return full.substring(16 - digits);
    }

    /**
     * Picks an entry of a weighted list; weights are per mille and must add up to 1000.
     *
     * @param seed    the run seed
     * @param index   the record index
     * @param lane    the pick's lane
     * @param weights per mille weights
     * @return the index of the chosen entry
     */
    public static int weighted(long seed, long index, int lane, int[] weights) {
        int roll = below(seed, index, lane, 1000);
        int acc = 0;
        for (int i = 0; i < weights.length; i++) {
            acc += weights[i];
            if (roll < acc) {
                return i;
            }
        }
        return weights.length - 1;
    }
}
