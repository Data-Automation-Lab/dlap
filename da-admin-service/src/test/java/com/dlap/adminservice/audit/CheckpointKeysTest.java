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

package com.dlap.adminservice.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.security.KeyPair;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.dlap.adminservice.audit.export.SignedCheckpoint;
import com.dlap.common.audit.CheckpointSignatures;

/** Loading the keys, fail-closed on a bad pair, and the trust check of stored checkpoints. */
class CheckpointKeysTest {

    @TempDir
    Path dir;

    @Test
    void noKeysConfiguredMeansNoSignerAndNoTrust() {
        CheckpointKeys keys = CheckpointKeys.load(new AuditProperties.Signing());
        assertThat(keys.signing()).isEmpty();
        assertThat(keys.trusted()).isEmpty();
    }

    @Test
    void aPairIsLoadedAndTrustedTogetherWithExtraKeys() {
        KeyPair current = AuditTestKeys.newPair();
        KeyPair previous = AuditTestKeys.newPair();
        AuditProperties.Signing s = AuditTestKeys.write(current, dir, "current");
        AuditProperties.Signing old = AuditTestKeys.write(previous, dir, "previous");
        s.setTrustedPublicKeyFiles(List.of(old.getPublicKeyFile(), " "));
        CheckpointKeys keys = CheckpointKeys.load(s);
        String currentId = CheckpointSignatures.keyId(current.getPublic());
        assertThat(keys.signing()).hasValueSatisfying(k -> assertThat(k.keyId()).isEqualTo(currentId));
        assertThat(keys.signing().get().toString()).doesNotContain("PRIVATE").contains(currentId);
        assertThat(keys.trusted()).containsOnlyKeys(currentId, CheckpointSignatures.keyId(previous.getPublic()));
    }

    @Test
    void aMismatchedHalfOrUnreadableConfigurationStopsTheService() {
        AuditProperties.Signing a = AuditTestKeys.write(AuditTestKeys.newPair(), dir, "a");
        AuditProperties.Signing b = AuditTestKeys.write(AuditTestKeys.newPair(), dir, "b");
        AuditProperties.Signing mixed = new AuditProperties.Signing();
        mixed.setPrivateKeyFile(a.getPrivateKeyFile());
        mixed.setPublicKeyFile(b.getPublicKeyFile());
        assertThatThrownBy(() -> CheckpointKeys.load(mixed)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not match");
        AuditProperties.Signing half = new AuditProperties.Signing();
        half.setPrivateKeyFile(a.getPrivateKeyFile());
        assertThatThrownBy(() -> CheckpointKeys.load(half)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("needs both");
        AuditProperties.Signing missing = new AuditProperties.Signing();
        missing.setPrivateKeyFile(dir.resolve("none.pem").toString());
        missing.setPublicKeyFile(dir.resolve("none.pub").toString());
        assertThatThrownBy(() -> CheckpointKeys.load(missing)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot read");
    }

    @Test
    void storedCheckpointsAreTrustedOnlyWhenPayloadRowAndSignatureAgree() {
        KeyPair pair = AuditTestKeys.newPair();
        CheckpointKeys keys = CheckpointKeys.load(AuditTestKeys.write(pair, dir, "k"));
        SignedCheckpoint cp = AuditTestKeys.signed(pair, "retire.delete", 5);
        StoredCheckpoint good = stored(cp, cp.checkpoint().seq(), cp.signature());
        assertThat(keys.problemWith(good)).isNull();
        assertThat(keys.problemWith(stored(cp, 6, cp.signature()))).isEqualTo("the payload does not match the row");
        assertThat(keys.problemWith(stored(cp, 5, AuditTestKeys.signed(pair, "retire.delete", 6).signature())))
                .isEqualTo("the signature does not verify");
        StoredCheckpoint garbled = new StoredCheckpoint("retire.delete", 5, good.entryHash(), good.keyId(), good.signedAt(),
                good.payload() + " ", good.signature(), null, null, null, 0, null);
        assertThat(keys.problemWith(garbled)).isEqualTo("the payload is not a canonical checkpoint");
        SignedCheckpoint foreign = AuditTestKeys.signed(AuditTestKeys.newPair(), "retire.delete", 5);
        assertThat(keys.problemWith(stored(foreign, 5, foreign.signature()))).contains("which is not trusted");
    }

    static StoredCheckpoint stored(SignedCheckpoint cp, long seq, String signature) {
        return new StoredCheckpoint(cp.checkpoint().streamId(), seq, cp.checkpoint().entryHash(), cp.checkpoint().keyId(),
                cp.checkpoint().signedAt(), cp.checkpoint().payload(), signature, null, null, null, 0, null);
    }
}
