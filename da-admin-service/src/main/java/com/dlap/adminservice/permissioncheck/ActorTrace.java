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

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * One cloud's committed Actor trace ({@code deployment/iam/<cloud>/actor/permissions.yaml}) with
 * the permission names of its Reader trace. The self-check compares a provider's answer with it:
 * a permission of the executor on the scope is expected, every other permission of the scope and
 * every {@code never_grant} entry is probed and is extra when held.
 */
public final class ActorTrace {

    /**
     * One granted permission.
     *
     * @param permission the provider's permission name
     * @param scopes     the scopes it is granted on
     * @param executors  the executors that need it ({@code delete}, {@code archive})
     */
    public record Grant(String permission, Set<String> scopes, Set<String> executors) {
    }

    private final String cloud;
    private final List<Grant> permissions;
    private final List<Grant> neverGrant;
    private final Map<String, String> scopeParents;
    private final Set<String> readerPermissions;

    private ActorTrace(String cloud, List<Grant> permissions, List<Grant> neverGrant, Map<String, String> scopeParents,
            Set<String> readerPermissions) {
        this.cloud = cloud;
        this.permissions = List.copyOf(permissions);
        this.neverGrant = List.copyOf(neverGrant);
        this.scopeParents = Map.copyOf(scopeParents);
        this.readerPermissions = Set.copyOf(readerPermissions);
    }

    /**
     * Parses an Actor trace and the matching Reader trace.
     *
     * @param actorYaml  the text of {@code actor/permissions.yaml}
     * @param readerYaml the text of {@code reader/permissions.yaml}, or {@code null} when there is none
     * @return the trace
     * @throws IllegalArgumentException when the Actor trace is not the expected shape
     */
    public static ActorTrace parse(String actorYaml, String readerYaml) {
        Map<String, Object> actor = load(actorYaml);
        Object cloud = actor.get("cloud");
        if (!(cloud instanceof String c) || c.isBlank()) {
            throw new IllegalArgumentException("the Actor trace names no cloud");
        }
        List<Grant> granted = grants(actor.get("permissions"), true);
        List<Grant> never = grants(actor.get("never_grant"), false);
        Map<String, String> parents = new LinkedHashMap<>();
        if (actor.get("scope_parents") instanceof Map<?, ?> m) {
            m.forEach((k, v) -> parents.put(String.valueOf(k), String.valueOf(v)));
        }
        Set<String> reader = new TreeSet<>();
        if (readerYaml != null) {
            for (Grant g : grants(load(readerYaml).get("permissions"), false)) {
                reader.add(g.permission());
            }
        }
        return new ActorTrace(c, granted, never, parents, reader);
    }

    /** @return the cloud the trace is for, for example {@code aws} */
    public String cloud() {
        return cloud;
    }

    /** @return every granted permission */
    public List<Grant> permissions() {
        return permissions;
    }

    /**
     * The permissions an executor holds on a scope.
     *
     * @param executor the executor
     * @param scope    the scope
     * @return the permission names, sorted
     */
    public Set<String> expected(String executor, String scope) {
        Set<String> out = new TreeSet<>();
        for (Grant g : permissions) {
            if (g.scopes().contains(scope) && g.executors().contains(executor)) {
                out.add(g.permission());
            }
        }
        return out;
    }

    /**
     * The permissions an executor holds on the scopes above a scope: a role assigned on an Azure
     * storage account is inherited by its containers.
     *
     * @param executor the executor
     * @param scope    the scope
     * @return the inherited permission names, sorted
     */
    public Set<String> inherited(String executor, String scope) {
        Set<String> out = new TreeSet<>();
        String parent = scopeParents.get(scope);
        int guard = 0;
        while (parent != null && guard++ < scopeParents.size()) {
            out.addAll(expected(executor, parent));
            parent = scopeParents.get(parent);
        }
        return out;
    }

    /**
     * Every permission the trace grants on a scope, for any executor, plus the scope's
     * {@code never_grant} entries: the candidates the self-check probes on a resource of that scope.
     *
     * @param scope the scope
     * @return the permission names, sorted
     */
    public Set<String> candidates(String scope) {
        Set<String> out = new TreeSet<>();
        for (Grant g : permissions) {
            if (g.scopes().contains(scope)) {
                out.add(g.permission());
            }
        }
        out.addAll(neverGrant(scope));
        return out;
    }

    /**
     * The permissions that must never be granted on a scope.
     *
     * @param scope the scope
     * @return the permission names, sorted
     */
    public Set<String> neverGrant(String scope) {
        Set<String> out = new TreeSet<>();
        for (Grant g : neverGrant) {
            if (g.scopes().contains(scope)) {
                out.add(g.permission());
            }
        }
        return out;
    }

    /** @return every {@code never_grant} entry */
    public List<Grant> neverGrantEntries() {
        return neverGrant;
    }

    /** @return the permission names of the same cloud's Reader trace */
    public Set<String> readerPermissions() {
        return readerPermissions;
    }

    /**
     * Whether a permission name is one of the Reader's, compared without case (Azure action names
     * are case-insensitive).
     *
     * @param permission the permission
     * @return true when the Reader trace holds it
     */
    public boolean isReaderPermission(String permission) {
        return containsIgnoreCase(readerPermissions, permission);
    }

    static boolean containsIgnoreCase(Collection<String> names, String name) {
        for (String n : names) {
            if (n.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> load(String yaml) {
        Object doc = new Yaml(new SafeConstructor(new LoaderOptions())).load(Objects.requireNonNull(yaml, "yaml"));
        if (!(doc instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("a permission trace must be a YAML mapping");
        }
        return (Map<String, Object>) doc;
    }

    private static List<Grant> grants(Object value, boolean requireExecutors) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("a permission list must be a YAML sequence");
        }
        return list.stream().map(o -> grant(o, requireExecutors)).toList();
    }

    private static Grant grant(Object o, boolean requireExecutors) {
        if (!(o instanceof Map<?, ?> m) || !(m.get("permission") instanceof String name) || name.isBlank()) {
            throw new IllegalArgumentException("every entry names a permission");
        }
        Set<String> scopes = strings(m.get("scopes"));
        Set<String> executors = strings(m.get("executors"));
        if (requireExecutors && (scopes.isEmpty() || executors.isEmpty())) {
            throw new IllegalArgumentException(name + " names no scope or no executor");
        }
        return new Grant(name, scopes, executors);
    }

    private static Set<String> strings(Object value) {
        Set<String> out = new TreeSet<>();
        if (value instanceof List<?> list) {
            list.forEach(v -> out.add(String.valueOf(v)));
        }
        return out;
    }
}
