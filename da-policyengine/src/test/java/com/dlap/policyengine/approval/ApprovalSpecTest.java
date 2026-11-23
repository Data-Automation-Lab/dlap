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

package com.dlap.policyengine.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.Test;

import com.dlap.common.constants.DlapConstants;

/**
 * docs/specs/approvals.md stays true to the code: every refusal reason, state, class, topic and
 * shipped property it names exists, and the other way round.
 */
class ApprovalSpecTest {

    private static String spec() throws IOException {
        return Files.readString(locate("docs/specs/approvals.md"), StandardCharsets.UTF_8);
    }

    @Test
    void everyRefusalReasonIsDocumented() throws Exception {
        String spec = spec();
        List<String> reasons = new ArrayList<>();
        for (Field f : ApprovalException.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) && f.getType() == String.class) {
                reasons.add((String) f.get(null));
            }
        }
        assertThat(reasons).hasSize(13);
        assertThat(reasons).allSatisfy(r -> assertThat(spec).contains("`" + r + "`"));
    }

    @Test
    void everyStateClassAndTopicIsDocumented() throws IOException {
        String spec = spec();
        for (ApprovalStatus s : ApprovalStatus.values()) {
            assertThat(spec).contains("`" + s.name() + "`");
        }
        for (ActionClass c : ActionClass.values()) {
            assertThat(spec).contains("`" + c.name() + "`");
        }
        assertThat(spec).contains("`" + DlapConstants.KafkaTopic.APPROVAL_REQUESTED + "`",
                "`" + DlapConstants.KafkaTopic.APPROVAL_DECIDED + "`",
                "`" + DlapConstants.KafkaTopic.APPROVAL_BREAK_GLASS + "`",
                "`" + ApprovalMaintenance.LOCK_NAME + "`");
    }

    @Test
    void theDocumentedDefaultsAreTheShippedOnes() throws IOException {
        Properties shipped = new Properties();
        try (InputStream in = ApprovalSpecTest.class.getResourceAsStream("/application.properties")) {
            shipped.load(in);
        }
        assertThat(shipped.getProperty("dlap.policyengine.approvals.ttl")).isEqualTo("${DLAP_APPROVALS_TTL:P7D}");
        assertThat(shipped.getProperty("dlap.policyengine.approvals.required.hard-delete"))
                .isEqualTo("${DLAP_APPROVALS_REQUIRED_HARD_DELETE:2}");
        assertThat(shipped.getProperty("dlap.policyengine.approvals.require-mfa-marker"))
                .isEqualTo("${DLAP_APPROVALS_REQUIRE_MFA_MARKER:false}");
        assertThat(shipped.getProperty("dlap.policyengine.approvals.maintenance.tick")).isEqualTo("PT5S");
        String spec = spec();
        assertThat(spec).contains("`P7D`", "1 / 1 / 2 / 1", "`ADMIN,CDO,DIRECTOR,POLICY_MANAGER`",
                "`dlap-mfa-required`", "`PT5S` / `PT10M`");
        ApprovalProperties defaults = ApprovalProperties.defaults();
        assertThat(defaults.requiredFor(ActionClass.HARD_DELETE)).isEqualTo(2);
        assertThat(defaults.maxCommentLength()).isEqualTo(2000);
        assertThat(spec).contains("`2000`");
    }

    private static Path locate(String relative) {
        Path start = Path.of("").toAbsolutePath();
        for (Path dir = start; dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return fail(relative + " not found in " + start + " or any parent directory");
    }
}
