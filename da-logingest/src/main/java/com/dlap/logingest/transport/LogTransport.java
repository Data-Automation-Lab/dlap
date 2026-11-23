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

import java.util.List;

/**
 * The transport SPI (design B6): pulls the raw records of one stream.
 *
 * <p>Transports self-register: declare an implementation as a Spring bean (SDK-backed ones under
 * {@code providers/<cloud>/}) and {@link TransportRegistry} finds it by
 * {@link TransportDescriptor#transportId()}; nothing central names a transport. Streaming and
 * backfill share a transport; {@link StreamContext#mode()} tells them apart.
 *
 * <p>Contract:
 * <ul>
 *   <li>{@link #fetch} returns batches in the order the cursor may advance. Each batch carries the
 *       cursor position that holds once all of its records reached an outcome. For a time cursor
 *       the low watermark is the oldest event time not yet delivered, never the newest seen, so a
 *       failed batch is read again (design B5); LookupEvents-style newest-first pages are
 *       reversed before they are returned.</li>
 *   <li>A file transport returns one batch per object, names it in {@link RawBatch#object()}, and
 *       skips objects {@link StreamContext#alreadyProcessed} reports.</li>
 *   <li>Records are split from their container with {@code RecordFraming} and built with
 *       {@link StreamContext#record}. A record that cannot be decoded is still delivered; the
 *       registry quarantines it.</li>
 *   <li>A transport never parses, stores or publishes; the runner does.</li>
 *   <li>Credentials come from the stream's {@code credentials-ref}; a transport never logs them.</li>
 * </ul>
 */
public interface LogTransport {

    /**
     * What the transport is and needs.
     *
     * @return the descriptor
     */
    TransportDescriptor descriptor();

    /**
     * The partitions of a stream, each with its own cursor (an AWS region, a shard).
     *
     * @param context the stream
     * @return partition keys; the default is one partition, {@code ""}
     */
    default List<String> partitions(StreamContext context) {
        return List.of("");
    }

    /**
     * Fetches the batches of one partition.
     *
     * @param context      the stream
     * @param partitionKey the partition
     * @param cursor       the partition's committed cursor, {@link CursorPosition#INITIAL} before the
     *                     first batch
     * @param window       the event-time window to read
     * @return the batches; closed by the caller
     */
    RawBatchIterator fetch(StreamContext context, String partitionKey, CursorPosition cursor, FetchWindow window);

    /**
     * Called after a batch's outcomes and cursor were committed, for transports with a server-side
     * acknowledgement. The default does nothing.
     *
     * @param context the stream
     * @param batch   the committed batch
     */
    default void committed(StreamContext context, RawBatch batch) {
    }
}
