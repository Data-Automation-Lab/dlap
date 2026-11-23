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
package fixtures;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class LogCredential {
    private static final Logger log = LoggerFactory.getLogger(LogCredential.class);

    record Connection(String id, String secret) {
        String getPassword() {
            return "";
        }
    }

    void bad(String password, String clientSecret, String accessToken, Connection connection) {
        // ruleid: dlap-java-log-credential
        log.info("login with {}", password);
        // ruleid: dlap-java-log-credential
        log.debug("client {} secret {}", connection.id(), clientSecret);
        // ruleid: dlap-java-log-credential
        log.warn("token {}", accessToken);
        // ruleid: dlap-java-log-credential
        log.error("failed for {}", connection.getPassword());
    }

    void good(String password, String tokenType, int passwordLength, Connection connection) {
        // ok: dlap-java-log-credential
        log.info("login with a password of {} characters", passwordLength);
        // ok: dlap-java-log-credential
        log.debug("token type {}", tokenType);
        // ok: dlap-java-log-credential
        log.info("credentials resolved for connection {}", connection.id());
        // ok: dlap-java-log-credential
        log.info("no credential value here");
        if (password.isEmpty()) {
            // ok: dlap-java-log-credential
            log.warn("empty password rejected for connection {}", connection.id());
        }
    }
}
