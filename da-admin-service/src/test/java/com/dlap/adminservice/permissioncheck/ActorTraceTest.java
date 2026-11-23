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

package com.dlap.adminservice.permissioncheck;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Parsing an Actor trace and the sets the self-check derives from it. */
class ActorTraceTest {

    private static final String ACTOR = """
            cloud: azure
            scope_parents:
              child: parent
              grandchild: child
            permissions:
              - permission: p.read
                scopes: [parent]
                executors: [delete]
              - permission: c.delete
                scopes: [child]
                executors: [delete, archive]
              - permission: g.list
                scopes: [grandchild]
                executors: [archive]
            never_grant:
              - permission: c.write
                scopes: [child]
                reason: no
            """;

    private static final String READER = """
            cloud: azure
            permissions:
              - permission: Microsoft.Storage/storageAccounts/read
                scopes: [subscription]
            """;

    @Test
    void derivesExpectedInheritedCandidatesAndReaderSets() {
        ActorTrace t = ActorTrace.parse(ACTOR, READER);
        assertThat(t.cloud()).isEqualTo("azure");
        assertThat(t.expected("delete", "child")).containsExactly("c.delete");
        assertThat(t.expected("archive", "parent")).isEmpty();
        assertThat(t.inherited("delete", "grandchild")).containsExactly("c.delete", "p.read");
        assertThat(t.inherited("delete", "parent")).isEmpty();
        assertThat(t.candidates("child")).containsExactly("c.delete", "c.write");
        assertThat(t.neverGrant("child")).containsExactly("c.write");
        assertThat(t.neverGrantEntries()).hasSize(1);
        assertThat(t.isReaderPermission("microsoft.storage/storageaccounts/READ")).isTrue();
        assertThat(t.isReaderPermission("c.delete")).isFalse();
        assertThat(ActorTrace.parse(ACTOR, null).readerPermissions()).isEmpty();
    }

    @Test
    void aCyclicParentListEnds() {
        ActorTrace t = ActorTrace.parse("""
                cloud: x
                scope_parents: {a: b, b: a}
                permissions:
                  - {permission: p, scopes: [a], executors: [delete]}
                  - {permission: q, scopes: [b], executors: [delete]}
                """, null);
        assertThat(t.inherited("delete", "a")).containsExactly("p", "q");
    }

    @Test
    void refusesATraceThatIsNotTheExpectedShape() {
        assertThatThrownBy(() -> ActorTrace.parse("- a\n- b\n", null)).hasMessageContaining("YAML mapping");
        assertThatThrownBy(() -> ActorTrace.parse("permissions: []\n", null)).hasMessageContaining("names no cloud");
        assertThatThrownBy(() -> ActorTrace.parse("cloud: x\npermissions: {a: b}\n", null))
                .hasMessageContaining("YAML sequence");
        assertThatThrownBy(() -> ActorTrace.parse("cloud: x\npermissions: [{scopes: [a]}]\n", null))
                .hasMessageContaining("names a permission");
        assertThatThrownBy(() -> ActorTrace.parse("cloud: x\npermissions: [{permission: p, scopes: [a]}]\n", null))
                .hasMessageContaining("no executor");
        assertThatThrownBy(() -> ActorTrace.parse("cloud: x\nboom: !!java.io.File [/tmp]\n", null))
                .as("the safe loader builds no Java object from a tag").isInstanceOf(RuntimeException.class);
    }
}
