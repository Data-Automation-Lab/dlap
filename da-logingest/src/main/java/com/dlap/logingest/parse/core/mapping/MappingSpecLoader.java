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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * Reads mapping YAMLs: from text, or every {@code dlap/log-formats/<family>/mapping.yaml} on the
 * classpath (each family ships its file next to its parser classes).
 *
 * <p>YAML is read with SnakeYAML's safe constructor (no custom tags, no object creation), with
 * duplicate keys rejected and alias expansion and document size bounded, then turned into a
 * Jackson tree for {@link MappingSpecValidator}. SnakeYAML comes with Spring Boot, so this adds
 * no dependency. A value YAML would read as a date, or a key that is not a string, is refused.
 */
public final class MappingSpecLoader {

    /** Classpath directory of the mapping files. */
    public static final String ROOT = "dlap/log-formats/";

    /** File name of a family's mapping. */
    public static final String MAPPING_FILE = "mapping.yaml";

    /** Pattern that finds every family's mapping. */
    public static final String CLASSPATH_PATTERN = "classpath*:" + ROOT + "*/" + MAPPING_FILE;

    /** Classpath location of the JSON Schema of the mapping YAML. */
    public static final String SCHEMA_RESOURCE = ROOT + "schema/log-format-mapping.schema.json";

    /** Largest mapping file read, in code points. */
    public static final int MAX_CODE_POINTS = 1_000_000;

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private MappingSpecLoader() {
    }

    /**
     * Reads YAML text into a tree.
     *
     * @param text the YAML
     * @param file a label for messages
     * @return the tree
     * @throws MappingSpecException when the text is not one safe YAML document
     */
    public static JsonNode readYaml(String text, String file) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setAllowRecursiveKeys(false);
        options.setMaxAliasesForCollections(20);
        options.setCodePointLimit(MAX_CODE_POINTS);
        Yaml yaml = new Yaml(new SafeConstructor(options));
        Object loaded;
        try {
            loaded = yaml.load(text);
        } catch (YAMLException e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            throw new MappingSpecException(List.of(file + ": not valid YAML: " + message.replace('\n', ' ')));
        }
        try {
            return toNode(loaded, "");
        } catch (IllegalArgumentException e) {
            throw new MappingSpecException(List.of(file + ": " + e.getMessage()));
        }
    }

    /**
     * Reads and validates one mapping.
     *
     * @param text           the YAML
     * @param file           a label for messages
     * @param expectedFamily the family directory, or {@code null}
     * @return the spec
     * @throws MappingSpecException with every problem found
     */
    public static MappingSpec parse(String text, String file, String expectedFamily) {
        MappingSpecValidator.Result r = MappingSpecValidator.validate(readYaml(text, file), file, expectedFamily);
        if (!r.ok()) {
            throw new MappingSpecException(r.problems());
        }
        return r.spec();
    }

    /**
     * Reads every family mapping on the classpath.
     *
     * @param resolver the resource resolver, usually the application context
     * @return the specs, sorted by family
     * @throws MappingSpecException with every problem of every file
     */
    public static List<MappingSpec> loadClasspath(ResourcePatternResolver resolver) {
        Resource[] resources;
        try {
            resources = resolver.getResources(CLASSPATH_PATTERN);
        } catch (IOException e) {
            throw new MappingSpecException(List.of(CLASSPATH_PATTERN + ": cannot list mappings: " + e.getMessage()));
        }
        List<MappingSpec> specs = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (Resource resource : resources) {
            String family = familyOf(resource);
            String file = ROOT + family + "/" + MAPPING_FILE;
            String text;
            try (InputStream in = resource.getInputStream()) {
                text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                problems.add(file + ": cannot read: " + e.getMessage());
                continue;
            }
            try {
                specs.add(parse(text, file, family));
            } catch (MappingSpecException e) {
                problems.addAll(e.problems());
            }
        }
        if (!problems.isEmpty()) {
            throw new MappingSpecException(problems);
        }
        specs.sort(Comparator.comparing(MappingSpec::family));
        return List.copyOf(specs);
    }

    private static String familyOf(Resource resource) {
        String path;
        try {
            path = resource.getURL().getPath();
        } catch (IOException e) {
            path = String.valueOf(resource.getFilename());
        }
        String[] parts = path.split("/");
        return parts.length >= 2 ? parts[parts.length - 2] : "";
    }

    private static JsonNode toNode(Object value, String at) {
        if (value == null) {
            return NODES.nullNode();
        }
        if (value instanceof String s) {
            return NODES.textNode(s);
        }
        if (value instanceof Boolean b) {
            return NODES.booleanNode(b);
        }
        if (value instanceof Integer i) {
            return NODES.numberNode(i);
        }
        if (value instanceof Long l) {
            return NODES.numberNode(l);
        }
        if (value instanceof BigInteger bi) {
            return NODES.numberNode(bi);
        }
        if (value instanceof Double d) {
            return NODES.numberNode(d);
        }
        if (value instanceof Float f) {
            return NODES.numberNode(f);
        }
        if (value instanceof BigDecimal bd) {
            return NODES.numberNode(bd);
        }
        if (value instanceof Map<?, ?> map) {
            ObjectNode object = NODES.objectNode();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!(e.getKey() instanceof String key)) {
                    throw new IllegalArgumentException(where(at) + "keys must be strings (quote yes, no, on, off and numbers)");
                }
                object.set(key, toNode(e.getValue(), at + "." + key));
            }
            return object;
        }
        if (value instanceof List<?> list) {
            ArrayNode array = NODES.arrayNode();
            for (int i = 0; i < list.size(); i++) {
                array.add(toNode(list.get(i), at + "[" + i + "]"));
            }
            return array;
        }
        throw new IllegalArgumentException(where(at) + "unsupported YAML value (" + value.getClass().getSimpleName()
                + "); quote dates and timestamps");
    }

    private static String where(String at) {
        return at.isEmpty() ? "" : at.substring(at.startsWith(".") ? 1 : 0) + ": ";
    }
}
