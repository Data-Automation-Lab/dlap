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

// Fixture for the Semgrep rules in /.semgrep.yml. Never compiled, never shipped.
// The three WARNING rules: reported, never blocking.
package fixtures;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.support.serializer.JsonDeserializer;

class Warnings {
    // ruleid: dlap-jackson-class-type-id
    @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "@class")
    interface ClassTyped {
    }

    // ok: dlap-jackson-class-type-id
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
    interface NameTyped {
    }

    // ruleid: dlap-kafka-trusted-packages-wildcard
    @Value("${spring.kafka.consumer.properties.spring.json.trusted.packages:*}")
    private String wildcardDefault;

    // ok: dlap-kafka-trusted-packages-wildcard
    @Value("${spring.kafka.consumer.properties.spring.json.trusted.packages:com.dlap.common.logevent}")
    private String closedDefault;

    Map<String, Object> wildcardPut() {
        Map<String, Object> props = new HashMap<>();
        // ruleid: dlap-kafka-trusted-packages-wildcard
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "*");
        return props;
    }

    Map<String, Object> closedPut() {
        Map<String, Object> props = new HashMap<>();
        // ok: dlap-kafka-trusted-packages-wildcard
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.dlap.common.logevent");
        return props;
    }

    void wildcardAdd(JsonDeserializer<Object> deserializer) {
        // ruleid: dlap-kafka-trusted-packages-wildcard
        deserializer.addTrustedPackages("*");
    }

    MessageDigest md5() throws Exception {
        // ruleid: dlap-java-weak-hash
        return MessageDigest.getInstance("MD5");
    }

    MessageDigest md5Qualified() throws Exception {
        // ruleid: dlap-java-weak-hash
        return java.security.MessageDigest.getInstance("MD5");
    }

    MessageDigest sha1() throws Exception {
        // ruleid: dlap-java-weak-hash
        return MessageDigest.getInstance("SHA-1");
    }

    MessageDigest sha256() throws Exception {
        // ok: dlap-java-weak-hash
        return MessageDigest.getInstance("SHA-256");
    }
}
