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

package com.dlap.catalog.lifecycle.okf;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.dlap.catalog.lifecycle.LifecycleState;
import com.dlap.catalog.lifecycle.LifecycleStateJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Offline OKF v0.1 export: turns Lifecycle State objects saved from the catalog API into the zip of
 * concept files the ADMIN endpoint {@code GET /api/v1/catalog/lifecycle-states/okf} returns. It
 * needs no database, broker or network, so an operator can export from a saved list response.
 *
 * <pre>
 * curl -H "Authorization: Bearer $TOKEN" "$CATALOG/api/v1/catalog/lifecycle-states?size=500" -o states.json
 * ./gradlew :da-catalog:exportLifecycleStateOkf -Pinput=states.json -Poutput=lifecycle-state-okf.zip
 * </pre>
 *
 * <p>The input is one object, a JSON array of objects, or a list response (its {@code items}).
 * Every object is read strictly ({@link LifecycleStateJson#parse}) and must share one {@code asOf}.
 * Exit codes: 0 written, 1 unreadable or invalid input, 2 usage.
 */
public final class OkfExportCli {

    /** Exit code of a written export. */
    public static final int OK = 0;
    /** Exit code of an unreadable or invalid input. */
    public static final int INVALID = 1;
    /** Exit code of a wrong command line. */
    public static final int USAGE = 2;

    private static final ObjectMapper JSON = new ObjectMapper();

    private OkfExportCli() {
    }

    /**
     * Runs the export and exits with its code.
     *
     * @param args {@code <input.json> <output.zip>}
     */
    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /**
     * Runs the export.
     *
     * @param args {@code <input.json> <output.zip>}
     * @param out  where the summary goes
     * @param err  where errors go
     * @return the exit code
     */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        if (args == null || args.length != 2 || args[0].isBlank() || args[1].isBlank()) {
            err.println("usage: OkfExportCli <input.json> <output.zip>"
                    + " (gradle: -Pinput=<input.json> -Poutput=<output.zip>)");
            return USAGE;
        }
        Path input = Path.of(args[0]);
        Path output = Path.of(args[1]);
        List<LifecycleState> states;
        try {
            states = read(JSON.readTree(input.toFile()));
        } catch (IOException e) {
            err.println("cannot read " + input + ": " + e.getMessage());
            return INVALID;
        } catch (IllegalArgumentException e) {
            err.println("not a Lifecycle State 0.1 input: " + e.getMessage());
            return INVALID;
        }
        if (states.isEmpty()) {
            err.println("the input holds no Lifecycle State object");
            return INVALID;
        }
        Instant asOf = states.get(0).asOf();
        if (states.stream().anyMatch(s -> !s.asOf().equals(asOf))) {
            err.println("every object of one export must have the same asOf");
            return INVALID;
        }
        try {
            if (output.getParent() != null) {
                Files.createDirectories(output.getParent());
            }
            int files;
            try (OutputStream zip = Files.newOutputStream(output)) {
                files = OkfExporter.writeZip(states, asOf, zip);
            }
            out.println("wrote " + files + " concept files as of " + asOf + " to " + output);
            return OK;
        } catch (IllegalArgumentException e) {
            err.println("not a Lifecycle State 0.1 input: " + e.getMessage());
            return INVALID;
        } catch (IOException e) {
            err.println("cannot write " + output + ": " + e.getMessage());
            return INVALID;
        }
    }

    /**
     * The objects of an input document.
     *
     * @param root one object, an array, or a list response with {@code items}
     * @return the objects, in input order
     * @throws IllegalArgumentException when an object is not a Lifecycle State 0.1 object
     */
    static List<LifecycleState> read(JsonNode root) {
        JsonNode items = root;
        if (root.isObject() && root.has("items")) {
            items = root.get("items");
        }
        List<LifecycleState> states = new ArrayList<>();
        if (items.isArray()) {
            for (JsonNode item : items) {
                states.add(LifecycleStateJson.parse(item));
            }
        } else {
            states.add(LifecycleStateJson.parse(items));
        }
        return states;
    }
}
