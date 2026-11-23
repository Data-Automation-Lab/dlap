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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;

class JacksonDefaultTyping {
    void badEnable(ObjectMapper mapper) {
        // ruleid: dlap-java-jackson-default-typing
        mapper.enableDefaultTyping();
    }

    void badLaissezFaire(ObjectMapper mapper) {
        // ruleid: dlap-java-jackson-default-typing
        mapper.activateDefaultTyping(LaissezFaireSubTypeValidator.instance);
    }

    void badLaissezFaireWithTyping(ObjectMapper mapper) {
        // ruleid: dlap-java-jackson-default-typing
        mapper.activateDefaultTyping(LaissezFaireSubTypeValidator.instance, ObjectMapper.DefaultTyping.NON_FINAL);
    }

    void goodRestricted(ObjectMapper mapper) {
        // ok: dlap-java-jackson-default-typing
        mapper.activateDefaultTyping(BasicPolymorphicTypeValidator.builder().allowIfSubType("com.dlap.").build(),
                ObjectMapper.DefaultTyping.NON_FINAL);
    }

    void goodPlain(ObjectMapper mapper) {
        // ok: dlap-java-jackson-default-typing
        mapper.findAndRegisterModules();
    }
}
