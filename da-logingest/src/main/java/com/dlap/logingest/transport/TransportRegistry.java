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

import com.dlap.logingest.config.sources.TransportCatalog;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Every registered {@link LogTransport}, by transport id. Startup fails when two transports claim
 * one id. Implements {@link TransportCatalog} for the bindings validator.
 */
@Component
public class TransportRegistry implements TransportCatalog {

    private final Map<String, LogTransport> byId;

    /**
     * Collects the transport beans.
     *
     * @param transports every transport bean
     */
    @Autowired
    public TransportRegistry(ObjectProvider<LogTransport> transports) {
        this(transports.orderedStream().toList());
    }

    /**
     * Indexes transports.
     *
     * @param transports the transports
     * @throws IllegalStateException when two transports share an id
     */
    public TransportRegistry(Collection<? extends LogTransport> transports) {
        Map<String, LogTransport> m = new TreeMap<>();
        for (LogTransport t : transports) {
            String id = t.descriptor().transportId();
            LogTransport previous = m.putIfAbsent(id, t);
            if (previous != null) {
                throw new IllegalStateException("two transports claim id " + id + ": " + previous.getClass().getName()
                        + " and " + t.getClass().getName());
            }
        }
        this.byId = Collections.unmodifiableMap(m);
    }

    /**
     * Finds a transport.
     *
     * @param transportId the id
     * @return the transport, or empty
     */
    public Optional<LogTransport> find(String transportId) {
        return Optional.ofNullable(byId.get(transportId));
    }

    /**
     * The registered transport ids.
     *
     * @return sorted ids
     */
    public List<String> ids() {
        return List.copyOf(byId.keySet());
    }

    @Override
    public Optional<Requirements> requirements(String transportId) {
        return find(transportId).map(t -> new Requirements(t.descriptor().requiredLocation(),
                t.descriptor().optionalLocation()));
    }
}
