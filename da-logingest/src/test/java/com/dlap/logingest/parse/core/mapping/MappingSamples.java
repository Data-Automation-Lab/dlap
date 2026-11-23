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

package com.dlap.logingest.parse.core.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;

/**
 * The sample family files and a catalogue of malformed variants, shared by the Java validator test
 * and the JSON Schema test so both judge the same documents.
 */
final class MappingSamples {

    /** The sample family file of the acceptance criterion. */
    static final String SELFTEST = "com/dlap/logingest/parse/core/contract/selftest-mapping.yaml";

    /** A second valid file that uses most optional keys. */
    static final String CONTRACT_DEMO = "com/dlap/logingest/parse/core/contract/contract-demo-mapping.yaml";

    private static final ObjectMapper JSON = new ObjectMapper();

    private MappingSamples() {
    }

    /** A named change to a valid document. */
    record Mutation(String name, Consumer<ObjectNode> change) {

        ObjectNode apply(JsonNode valid) {
            ObjectNode copy = valid.deepCopy();
            change.accept(copy);
            return copy;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    static String text(String resource) {
        try (InputStream in = MappingSamples.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static JsonNode tree(String resource) {
        return MappingSpecLoader.readYaml(text(resource), resource);
    }

    static JsonNode json(String s) {
        try {
            return JSON.readTree(s);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static ObjectNode env(ObjectNode root) {
        return (ObjectNode) root.get("envelopes").get(0);
    }

    private static Mutation m(String name, Consumer<ObjectNode> change) {
        return new Mutation(name, change);
    }

    /** Defects that both the JSON Schema and the Java validator reject. */
    static List<Mutation> structural() {
        return List.of(
                m("no spec-version", r -> r.remove("spec-version")),
                m("spec-version 2", r -> r.put("spec-version", 2)),
                m("spec-version as text", r -> r.put("spec-version", "1")),
                m("family upper case", r -> r.put("family", "SelfTest")),
                m("family 'schema' is reserved", r -> r.put("family", "schema")),
                m("source-kind lower case", r -> r.put("source-kind", "dlap_selftest")),
                m("unknown provider", r -> r.put("source-provider", "IBM")),
                m("unknown top-level key", r -> r.putObject("parsers")),
                m("blank description", r -> r.put("description", "   ")),
                m("no envelopes", r -> r.remove("envelopes")),
                m("empty envelopes", r -> r.putArray("envelopes")),
                m("extensions is a list", r -> r.putArray("extensions")),
                m("unknown settings key", r -> ((ObjectNode) r.get("settings")).put("retries", 3)),
                m("overlap not ISO-8601", r -> ((ObjectNode) r.get("settings")).put("overlap", "20m")),
                m("negative overlap", r -> ((ObjectNode) r.get("settings")).put("overlap", "-PT20M")),
                m("max-record-bytes 0", r -> ((ObjectNode) r.get("settings")).put("max-record-bytes", 0)),
                m("max-record-bytes above 16 MiB", r -> ((ObjectNode) r.get("settings")).put("max-record-bytes", 16777217)),
                m("persist-raw NONE", r -> ((ObjectNode) r.get("settings")).put("persist-raw", "NONE")),
                m("on-mismatch lower case", r -> ((ObjectNode) r.get("settings")).put("on-mismatch", "quarantine")),
                m("envelope without native-key", r -> env(r).remove("native-key")),
                m("envelope without versions", r -> env(r).remove("versions")),
                m("envelope id with underscore", r -> env(r).put("id", "generic_json")),
                m("unknown envelope key", r -> env(r).put("parser", "x")),
                m("unknown framing", r -> env(r).putArray("framing").add("xml")),
                m("repeated framing", r -> env(r).putArray("framing").add("record").add("record")),
                m("json-object-array without records-pointer", r -> env(r).putArray("framing").add("json-object-array")),
                m("records-pointer without json-object-array", r -> env(r).put("records-pointer", "/Records")),
                m("unknown scheme", r -> ((ObjectNode) env(r).get("version-detection")).put("scheme", "semver")),
                m("major-minor without pointer", r -> ((ObjectNode) env(r).get("version-detection")).remove("pointer")),
                m("major-minor with rules", r -> ((ObjectNode) env(r).get("version-detection")).set("rules",
                        json("[{\"version\":\"1.0\",\"when\":[{\"pointer\":\"/v\",\"present\":true}]}]"))),
                m("named without rules", r -> ((ObjectNode) env(r).get("version-detection")).put("scheme", "named")),
                m("pointer without slash", r -> ((ObjectNode) env(r).get("version-detection")).put("pointer", "formatVersion")),
                m("pointer with a bad escape", r -> ((ObjectNode) env(r).get("version-detection")).put("pointer", "/a~2b")),
                m("fields without operation", r -> ((ObjectNode) env(r).get("fields")).remove("operation")),
                m("fields without event-time", r -> ((ObjectNode) env(r).get("fields")).remove("event-time")),
                m("unknown canonical field", r -> ((ObjectNode) env(r).get("fields")).put("actor", "/actor")),
                m("empty pointer list", r -> ((ObjectNode) env(r).get("fields")).putArray("principal.id")),
                m("blank constant", r -> ((ObjectNode) env(r).get("fields")).putObject("service").put("const", "")),
                m("constant with an extra key", r -> ((ObjectNode) env(r).get("fields")).set("service",
                        json("{\"const\":\"demo\",\"pointer\":\"/x\"}"))),
                m("field source is a number", r -> ((ObjectNode) env(r).get("fields")).put("principal.id", 7)),
                m("unknown required field", r -> env(r).putArray("required").add("actor")),
                m("repeated required field", r -> env(r).putArray("required").add("target.id").add("target.id")),
                m("condition with two tests", r -> ((ObjectNode) env(r).get("result").get("failure-when").get(0))
                        .put("present", true)),
                m("condition without a test", r -> ((ObjectNode) env(r).get("result").get("failure-when").get(0))
                        .remove("not-equals")),
                m("condition with an empty in", r -> {
                    ObjectNode c = (ObjectNode) env(r).get("result").get("failure-when").get(0);
                    c.remove("not-equals");
                    c.putArray("in");
                }),
                m("condition equals an object", r -> ((ObjectNode) env(r).get("result").get("failure-when").get(0))
                        .putObject("not-equals")),
                m("present is not a boolean", r -> {
                    ObjectNode c = (ObjectNode) env(r).get("result").get("failure-when").get(0);
                    c.remove("not-equals");
                    c.put("present", "yes");
                }),
                m("unknown result key", r -> ((ObjectNode) env(r).get("result")).putArray("success-when")),
                m("skip reason MISSING_EVENT_TIME", r -> ((ObjectNode) env(r).get("skip-when").get(0))
                        .put("reason", "MISSING_EVENT_TIME")),
                m("skip rule without conditions", r -> ((ObjectNode) env(r).get("skip-when").get(0)).putArray("when")),
                m("unknown principal type", r -> env(r).putObject("principal-types").put("AssumedRole", "ADMIN")),
                m("principal-types key without letters", r -> env(r).putObject("principal-types").put("--", "USER")),
                m("rule with operation and operation-prefix", r -> rule(r).put("operation-prefix", "re")),
                m("rule without operation", r -> rule(r).remove("operation")),
                m("rule without category", r -> rule(r).remove("category")),
                m("change-type READ", r -> rule(r).put("change-type", "READ")),
                m("prefixed category", r -> rule(r).put("category", "EVENT_CATEGORY_DATA_READ")),
                m("bad extractor name", r -> rule(r).put("extractor", "S3_Request")),
                m("asset-kind lower case", r -> rule(r).put("asset-kind", "s3_object")),
                m("unknown classification key", r -> ((ObjectNode) env(r).get("classification")).putArray("defaults")),
                m("empty versions", r -> env(r).putArray("versions")),
                m("version id as a number", r -> ((ObjectNode) env(r).get("versions").get(0)).put("id", 1.1)),
                m("major-minor version id v1", r -> ((ObjectNode) env(r).get("versions").get(0)).put("id", "v1")),
                m("unknown version key", r -> ((ObjectNode) env(r).get("versions").get(0)).put("supported", true)),
                m("native-key without parts", r -> ((ObjectNode) env(r).get("native-key")).putArray("parts")),
                m("native-key part format epoch", r -> ((ObjectNode) env(r).get("native-key").get("parts").get(0))
                        .put("as", "epoch")),
                m("namespaced is text", r -> ((ObjectNode) env(r).get("native-key")).put("namespaced", "true")));
    }

    /** Defects only the Java validator can see; the JSON Schema accepts these documents. */
    static List<Mutation> semanticOnly() {
        return List.of(
                m("repeated envelope id", r -> ((ArrayNode) r.get("envelopes")).add(env(r).deepCopy())),
                m("versions 1.0 and 1.00 are one version", r -> ((ArrayNode) env(r).get("versions"))
                        .add(json("{\"id\":\"1.00\"}"))),
                m("required field without a source", r -> ((ArrayNode) env(r).get("required")).add("principal.display-name")),
                m("repeated classification row", r -> ((ArrayNode) env(r).get("classification").get("rules"))
                        .add(rule(r).deepCopy())),
                m("asset-kind that is no AssetKind", r -> rule(r).put("asset-kind", "NOT_A_KIND")),
                m("principal-types keys that collide", r -> env(r).putObject("principal-types")
                        .put("AssumedRole", "ROLE").put("assumed_role", "USER")),
                m("zero overlap", r -> ((ObjectNode) r.get("settings")).put("overlap", "P0D")),
                m("named rule for an unlisted version", r -> {
                    ObjectNode d = (ObjectNode) env(r).get("version-detection");
                    d.put("scheme", "named");
                    d.remove("pointer");
                    d.set("rules", json("[{\"version\":\"v-other\",\"when\":[{\"pointer\":\"/format\",\"present\":true}]}]"));
                    ((ObjectNode) env(r).get("versions").get(0)).put("id", "v-main");
                }));
    }

    private static ObjectNode rule(ObjectNode root) {
        return (ObjectNode) env(root).get("classification").get("rules").get(0);
    }
}
