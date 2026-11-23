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

package com.dlap.autoarchival;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Array;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.jackson2.autoconfigure.Jackson2AutoConfiguration;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.ScannedGenericBeanDefinition;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.core.type.classreading.SimpleMetadataReaderFactory;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.objenesis.SpringObjenesis;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.dlap.autoarchival.DaAutoarchivalApplication;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * API-01: exports the OpenAPI document of da-autoarchival from its controllers and compares it with the
 * committed contract {@code docs/api/openapi/autoarchival.json}.
 *
 * <p>The document is built from the real controllers, controller advice and OpenAPI beans of this
 * module's main source set by springdoc, in a small web context: no database, Kafka or Keycloak is
 * started, so the test needs no Docker and runs in a few seconds. The controllers' collaborators are
 * inert stand-ins that are never called, so a new controller or constructor argument needs no test
 * change. Spring Security, actuator and the springdoc documentation endpoints are not part of the
 * context: the contract is the business API only.
 *
 * <p>The committed file is canonical (sorted keys, two-space indent, LF line endings, ASCII only, no
 * {@code servers} entry because the host is a deployment detail), so a diff shows exactly what changed in the API.
 * When a controller changes, regenerate the file and commit it:
 *
 * <pre>
 * DLAP_OPENAPI_UPDATE=1 ./gradlew :da-autoarchival:test --tests '*OpenApiSpecExportTest*' --rerun
 * </pre>
 *
 * <p>{@code --rerun} matters: Gradle's build cache would otherwise restore the last passing result and never run
 * the test, so nothing would be rewritten.
 *
 * <p>{@code scripts/ci-guards/post-test/openapi-drift.sh} regenerates every service the same way and
 * fails on any difference, and it checks that the support code below is identical in all services.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class OpenApiSpecExportTest {

    /** The Gradle module. */
    private static final String MODULE = "da-autoarchival";

    /** The service key: the contract is {@code docs/api/openapi/<key>.json}. */
    private static final String SERVICE = "autoarchival";

    /** A class of this module's main source set: it anchors the package to scan and the class folder. */
    private static final Class<?> ANCHOR = DaAutoarchivalApplication.class;

    /** The package tree to scan for controllers: this module's root package. */
    private static final String BASE_PACKAGE = "com.dlap.autoarchival";

    private static OpenApiSupport.Rendered cached;

    private static synchronized OpenApiSupport.Rendered rendered() {
        if (cached == null) {
            cached = OpenApiSupport.render(ANCHOR, BASE_PACKAGE);
        }
        return cached;
    }

    /** The committed contract equals what the controllers produce now, or the build tells how to refresh it. */
    @Test
    void committedSpecMatchesTheControllers() throws IOException {
        OpenApiSupport.verifyCommitted(MODULE, SERVICE, rendered().json());
    }

    /** Two independent contexts give the same text, so the committed file never flaps. */
    @Test
    void renderingIsDeterministic() {
        assertThat(OpenApiSupport.render(ANCHOR, BASE_PACKAGE).json()).isEqualTo(rendered().json());
    }

    /** Every mapped, not hidden handler method of this module is in the contract, and nothing else is. */
    @Test
    void everyMappedHandlerIsInTheSpec() {
        OpenApiSupport.Rendered rendered = rendered();
        Set<String> inSpec = OpenApiSupport.operations(OpenApiSupport.parse(rendered.json())).keySet();

        Set<String> missing = new TreeSet<>(rendered.mappedOperations());
        missing.removeAll(inSpec);
        Set<String> unexpected = new TreeSet<>(inSpec);
        unexpected.removeAll(rendered.mappedOperations());
        unexpected.removeIf(operation -> rendered.anyMethodPaths().contains(operation.substring(operation.indexOf(' ') + 1)));

        assertThat(missing).as("mapped handler methods missing from the spec").isEmpty();
        assertThat(unexpected).as("spec operations without a mapped handler method").isEmpty();
        assertThat(inSpec).as("the module maps at least one operation").isNotEmpty();
    }

    /** The document is a usable OpenAPI 3 contract: version, info, resolvable references, unique operation ids. */
    @Test
    void specIsStructurallyValid() {
        JsonNode spec = OpenApiSupport.parse(rendered().json());

        assertThat(spec.path("openapi").asText()).startsWith("3.");
        assertThat(spec.path("info").path("title").asText()).as("info.title").isNotBlank();
        assertThat(spec.path("info").path("version").asText()).as("info.version").isNotBlank();
        assertThat(spec.has("servers")).as("servers are a deployment detail, not part of the contract").isFalse();

        List<String> unresolved = new ArrayList<>();
        OpenApiSupport.references(spec, spec, unresolved);
        assertThat(unresolved).as("references that do not resolve").isEmpty();

        List<String> operationIds = new ArrayList<>();
        OpenApiSupport.operations(spec).forEach((key, operation) -> operationIds.add(operation.path("operationId").asText()));
        assertThat(operationIds).as("operation ids").doesNotContain("").doesNotHaveDuplicates();
    }

    // BEGIN SHARED OPENAPI SUPPORT
    // Identical in every service's OpenApiSpecExportTest: scripts/ci-guards/post-test/openapi-drift.sh compares
    // the text between these two markers. Edit it in all services at once.

    /**
     * Builds, canonicalizes and verifies the OpenAPI document of one service.
     *
     * <p>Environment: {@code DLAP_OPENAPI_UPDATE=1} (or {@code true}) rewrites the committed contract
     * instead of failing on a difference.
     */
    static final class OpenApiSupport {

        private static final String UPDATE_ENV = "DLAP_OPENAPI_UPDATE";

        private static final ObjectMapper MAPPER = new ObjectMapper();

        /** Prints scalars; every non-ASCII character becomes a unicode escape, so the file is plain ASCII. */
        private static final ObjectMapper ASCII = JsonMapper.builder().enable(JsonWriteFeature.ESCAPE_NON_ASCII).build();

        private static final List<String> HTTP_METHODS =
                List.of("get", "put", "post", "delete", "options", "head", "patch", "trace");

        /** Types Spring injects without a bean definition. */
        private static final Set<Class<?>> RESOLVABLE = Set.of(BeanFactory.class, ApplicationContext.class,
                ApplicationEventPublisher.class, ResourceLoader.class, Environment.class, ServletRequest.class,
                ServletResponse.class, HttpSession.class, WebRequest.class);

        private OpenApiSupport() {
        }

        /**
         * The canonical document and what the handler mappings say it must contain.
         *
         * @param json the canonical document text
         * @param mappedOperations {@code "get /path"} for every explicit mapping of this module that is not hidden
         * @param anyMethodPaths paths mapped for every HTTP method
         */
        record Rendered(String json, Set<String> mappedOperations, Set<String> anyMethodPaths) {
        }

        /**
         * Starts the small web context for the module of {@code anchor} and reads {@code /v3/api-docs}.
         *
         * @param anchor a class of the module's main source set
         * @param basePackage the package tree of the module's main source set
         * @return the canonical document and the mapped operations
         */
        static Rendered render(Class<?> anchor, String basePackage) {
            return build(anchor, basePackage);
        }

        private static Rendered build(Class<?> anchor, String basePackage) {
            URI mainClasses = locationOf(anchor);
            AtomicReference<Rendered> result = new AtomicReference<>();
            new WebApplicationContextRunner()
                    .withInitializer(new ConfigDataApplicationContextInitializer())
                    .withInitializer(context -> registerConfigurations((BeanDefinitionRegistry) context, context.getEnvironment()))
                    .withPropertyValues(
                            "springdoc.api-docs.enabled=true",
                            "springdoc.api-docs.path=/v3/api-docs",
                            "springdoc.swagger-ui.enabled=false",
                            "springdoc.show-actuator=false",
                            "springdoc.cache.disabled=true")
                    .withBean("openApiExportRegistrar", Registrar.class,
                            () -> new Registrar(basePackage, mainClasses))
                    .run(context -> result.set(extract(context, mainClasses)));
            return result.get();
        }

        /**
         * Registers the web and springdoc auto-configurations by class name, in a fixed order. Their class-level
         * conditions are read from the class files and evaluated here: a {@code @ConditionalOnClass} naming a class
         * that is not on the classpath (hateoas, Kotlin, data-rest) then leaves the configuration out, as it does in
         * the running service, instead of failing the context.
         */
        private static void registerConfigurations(BeanDefinitionRegistry registry, Environment environment) {
            List<String> names = new ArrayList<>(List.of(ValidationAutoConfiguration.class.getName(),
                    // Jackson 2 stays the JSON library of the services (BOOT-01, ADR-0066).
                    JacksonAutoConfiguration.class.getName(), Jackson2AutoConfiguration.class.getName(),
                    HttpMessageConvertersAutoConfiguration.class.getName(),
                    DispatcherServletAutoConfiguration.class.getName(), WebMvcAutoConfiguration.class.getName()));
            for (String name : ImportCandidates.load(AutoConfiguration.class, OpenApiSupport.class.getClassLoader())
                    .getCandidates()) {
                if (name.startsWith("org.springdoc.")) {
                    names.add(name);
                }
            }
            ConditionProbe probe = new ConditionProbe(environment);
            try {
                for (String name : names) {
                    MetadataReader reader = probe.getMetadataReaderFactory().getMetadataReader(name);
                    if (probe.passes(reader)) {
                        registry.registerBeanDefinition(name, new ScannedGenericBeanDefinition(reader));
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        /** Exposes the class-level condition check of Spring's component scanner. */
        private static final class ConditionProbe extends ClassPathScanningCandidateComponentProvider {

            ConditionProbe(Environment environment) {
                super(false, environment);
                addIncludeFilter((reader, factory) -> true);
            }

            boolean passes(MetadataReader reader) throws IOException {
                return isCandidateComponent(reader);
            }
        }

        private static Rendered extract(AssertableWebApplicationContext context, URI mainClasses) throws Exception {
            if (context.getStartupFailure() != null) {
                throw new IllegalStateException("The context for the OpenAPI export did not start: "
                        + rootCause(context.getStartupFailure()), context.getStartupFailure());
            }
            WebApplicationContext web = (WebApplicationContext) context.getSourceApplicationContext();
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(web).build();
            MockHttpServletResponse response = mvc.perform(get("/v3/api-docs")).andReturn().getResponse();
            String body = response.getContentAsString(StandardCharsets.UTF_8);
            assertThat(response.getStatus()).as("GET /v3/api-docs answered %s", body).isEqualTo(200);

            Set<String> mapped = new TreeSet<>();
            Set<String> anyMethod = new TreeSet<>();
            RequestMappingHandlerMapping mapping = web.getBean("requestMappingHandlerMapping",
                    RequestMappingHandlerMapping.class);
            for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : mapping.getHandlerMethods().entrySet()) {
                HandlerMethod handler = entry.getValue();
                if (!isFrom(handler.getBeanType(), mainClasses) || isHidden(handler)) {
                    continue;
                }
                Set<RequestMethod> methods = entry.getKey().getMethodsCondition().getMethods();
                for (String pattern : entry.getKey().getPatternValues()) {
                    String path = pattern.replaceAll("\\{([^}/:]+):[^}]*}", "{$1}");
                    if (methods.isEmpty()) {
                        anyMethod.add(path);
                    }
                    methods.forEach(method -> mapped.add(method.name().toLowerCase(Locale.ROOT) + " " + path));
                }
            }
            return new Rendered(canonicalize(body), mapped, anyMethod);
        }

        private static boolean isHidden(HandlerMethod handler) {
            Operation operation = AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), Operation.class);
            return AnnotatedElementUtils.hasAnnotation(handler.getMethod(), Hidden.class)
                    || AnnotatedElementUtils.hasAnnotation(handler.getBeanType(), Hidden.class)
                    || (operation != null && operation.hidden());
        }

        // ---- canonical text ------------------------------------------------------------------------------

        /**
         * Sorts every object by key, sorts the arrays whose order is incidental ({@code required}, {@code tags}),
         * drops {@code servers} (the host is a deployment detail) and prints two-space JSON with LF endings and
         * unicode escapes for every non-ASCII character.
         *
         * @param rawJson the document as springdoc served it
         * @return the canonical text, ending with a newline
         */
        static String canonicalize(String rawJson) {
            JsonNode tree = parse(rawJson);
            if (tree instanceof ObjectNode root) {
                root.remove("servers");
            }
            StringBuilder out = new StringBuilder();
            print(canonical(tree, ""), out, 0);
            return out.append('\n').toString();
        }

        private static JsonNode canonical(JsonNode node, String key) {
            if (node.isObject()) {
                ObjectNode sorted = MAPPER.createObjectNode();
                new TreeSet<String>(fieldNames(node)).forEach(name -> sorted.set(name, canonical(node.get(name), name)));
                return sorted;
            }
            if (node.isArray()) {
                List<JsonNode> items = new ArrayList<>();
                node.forEach(item -> items.add(canonical(item, key)));
                if ("required".equals(key) || "tags".equals(key)) {
                    items.sort(Comparator.comparing(item -> item.isTextual() ? item.asText() : item.path("name").asText()));
                }
                ArrayNode array = MAPPER.createArrayNode();
                items.forEach(array::add);
                return array;
            }
            return node;
        }

        private static List<String> fieldNames(JsonNode node) {
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            return names;
        }

        private static void print(JsonNode node, StringBuilder out, int depth) {
            if (node.isObject() && !node.isEmpty()) {
                out.append("{\n");
                Iterator<String> names = node.fieldNames();
                while (names.hasNext()) {
                    String name = names.next();
                    out.append("  ".repeat(depth + 1)).append(scalar(MAPPER.getNodeFactory().textNode(name))).append(": ");
                    print(node.get(name), out, depth + 1);
                    out.append(names.hasNext() ? ",\n" : "\n");
                }
                out.append("  ".repeat(depth)).append('}');
            } else if (node.isArray() && !node.isEmpty()) {
                out.append("[\n");
                for (int i = 0; i < node.size(); i++) {
                    out.append("  ".repeat(depth + 1));
                    print(node.get(i), out, depth + 1);
                    out.append(i < node.size() - 1 ? ",\n" : "\n");
                }
                out.append("  ".repeat(depth)).append(']');
            } else if (node.isObject()) {
                out.append("{}");
            } else if (node.isArray()) {
                out.append("[]");
            } else {
                out.append(scalar(node));
            }
        }

        private static String scalar(JsonNode node) {
            try {
                return ASCII.writeValueAsString(node);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        static JsonNode parse(String json) {
            try {
                return MAPPER.readTree(json);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        // ---- the contract on disk ------------------------------------------------------------------------

        /**
         * Writes {@code build/openapi/<service>.json} and compares the text with the committed contract.
         *
         * @param module the Gradle module, for the regenerate command in the failure message
         * @param service the service key
         * @param json the freshly built canonical text
         * @throws IOException when a file cannot be read or written
         */
        static void verifyCommitted(String module, String service, String json) throws IOException {
            Path built = Path.of("build", "openapi", service + ".json");
            Files.createDirectories(built.getParent());
            Files.writeString(built, json, StandardCharsets.UTF_8);

            Path committed = repositoryRoot().resolve("docs").resolve("api").resolve("openapi").resolve(service + ".json");
            String regenerate = "DLAP_OPENAPI_UPDATE=1 ./gradlew :" + module + ":test --tests '*OpenApiSpecExportTest*' --rerun";
            boolean exists = Files.isRegularFile(committed);
            String current = exists ? Files.readString(committed, StandardCharsets.UTF_8).replace("\r\n", "\n") : "";
            if (updateRequested()) {
                if (!current.equals(json)) {
                    Files.createDirectories(committed.getParent());
                    Files.writeString(committed, json, StandardCharsets.UTF_8);
                }
                return;
            }
            if (!exists) {
                fail("The OpenAPI contract docs/api/openapi/" + service + ".json is not committed. Create it with:\n  "
                        + regenerate);
            }
            if (!current.equals(json)) {
                fail("The OpenAPI contract docs/api/openapi/" + service + ".json is out of date:\n"
                        + describeDifference(current, json) + "\nThe fresh text is in " + built.toAbsolutePath()
                        + ". If the API change is intended, refresh the contract and commit it:\n  " + regenerate);
            }
        }

        private static boolean updateRequested() {
            String value = System.getenv(UPDATE_ENV);
            return "1".equals(value) || "true".equalsIgnoreCase(value);
        }

        private static Path repositoryRoot() {
            Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
            while (dir != null) {
                if (Files.isRegularFile(dir.resolve("settings.gradle.kts"))) {
                    return dir;
                }
                dir = dir.getParent();
            }
            throw new IllegalStateException("settings.gradle.kts not found above " + System.getProperty("user.dir"));
        }

        private static String describeDifference(String committedJson, String freshJson) {
            JsonNode before = parse(committedJson);
            JsonNode after = parse(freshJson);
            List<String> lines = new ArrayList<>();
            compare("operation", operations(before), operations(after), lines);
            compare("schema", schemas(before), schemas(after), lines);
            if (lines.isEmpty()) {
                lines.add("no operation or schema differs (info, tags, security or components other than schemas changed)");
            }
            int shown = Math.min(lines.size(), 25);
            StringBuilder text = new StringBuilder();
            lines.subList(0, shown).forEach(line -> text.append("  ").append(line).append('\n'));
            if (lines.size() > shown) {
                text.append("  ... and ").append(lines.size() - shown).append(" more\n");
            }
            return text.toString();
        }

        private static void compare(String kind, Map<String, JsonNode> before, Map<String, JsonNode> after, List<String> lines) {
            after.keySet().stream().filter(key -> !before.containsKey(key)).forEach(key -> lines.add("added   " + kind + " " + key));
            before.keySet().stream().filter(key -> !after.containsKey(key)).forEach(key -> lines.add("removed " + kind + " " + key));
            after.forEach((key, value) -> {
                if (before.containsKey(key) && !before.get(key).equals(value)) {
                    lines.add("changed " + kind + " " + key);
                }
            });
        }

        /**
         * Reads every operation of a document.
         *
         * @param spec a parsed OpenAPI document
         * @return {@code "get /path"} mapped to the operation object, in key order
         */
        static Map<String, JsonNode> operations(JsonNode spec) {
            Map<String, JsonNode> operations = new TreeMap<>();
            spec.path("paths").fields().forEachRemaining(path -> {
                for (String method : HTTP_METHODS) {
                    if (path.getValue().has(method)) {
                        operations.put(method + " " + path.getKey(), path.getValue().get(method));
                    }
                }
            });
            return operations;
        }

        private static Map<String, JsonNode> schemas(JsonNode spec) {
            Map<String, JsonNode> schemas = new TreeMap<>();
            spec.path("components").path("schemas").fields().forEachRemaining(schema -> schemas.put(schema.getKey(), schema.getValue()));
            return schemas;
        }

        /**
         * Collects every {@code $ref} of {@code node} that does not point at an existing node of {@code root}.
         *
         * @param root the document
         * @param node the node to walk
         * @param unresolved receives the broken references
         */
        static void references(JsonNode root, JsonNode node, List<String> unresolved) {
            if (node.isObject()) {
                JsonNode ref = node.get("$ref");
                if (ref != null && ref.isTextual() && ref.asText().startsWith("#/")
                        && root.at(ref.asText().substring(1)).isMissingNode()) {
                    unresolved.add(ref.asText());
                }
                node.elements().forEachRemaining(child -> references(root, child, unresolved));
            } else if (node.isArray()) {
                node.elements().forEachRemaining(child -> references(root, child, unresolved));
            }
        }

        // ---- the web context -----------------------------------------------------------------------------

        private static URI locationOf(Class<?> type) {
            try {
                return type.getProtectionDomain().getCodeSource().getLocation().toURI();
            } catch (URISyntaxException e) {
                throw new IllegalStateException("cannot locate " + type.getName(), e);
            }
        }

        /** True when {@code type} was loaded from the module's main class folder (or jar), not from its tests. */
        private static boolean isFrom(Class<?> type, URI mainClasses) {
            return locationOf(type).equals(mainClasses);
        }

        private static String rootCause(Throwable failure) {
            Throwable cause = failure;
            while (cause.getCause() != null && cause.getCause() != cause) {
                cause = cause.getCause();
            }
            return cause.getClass().getSimpleName() + ": " + cause.getMessage();
        }

        private static boolean isUnder(URI candidate, URI folder) {
            try {
                return Path.of(candidate).startsWith(Path.of(folder));
            } catch (RuntimeException e) {
                return candidate.toString().startsWith("jar:" + folder) || candidate.toString().startsWith(folder.toString());
            }
        }

        /**
         * Registers the controllers, controller advice and OpenAPI beans of the module, then an inert stand-in for
         * every collaborator the context does not provide.
         */
        static final class Registrar implements BeanDefinitionRegistryPostProcessor, EnvironmentAware {

            private final String basePackage;
            private final URI mainClasses;
            private Environment environment;

            Registrar(String basePackage, URI mainClasses) {
                this.basePackage = basePackage;
                this.mainClasses = mainClasses;
            }

            @Override
            public void setEnvironment(Environment environment) {
                this.environment = environment;
            }

            @Override
            public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
                DefaultListableBeanFactory beans = (DefaultListableBeanFactory) registry;
                List<Need> needs = new ArrayList<>();
                for (Class<?> type : registerWebBeans(registry)) {
                    collectDependencies(type, needs);
                }
                for (Method factory : registerOpenApiBeans(registry)) {
                    collectDependencies(factory.getDeclaringClass(), needs);
                    for (int i = 0; i < factory.getParameterCount(); i++) {
                        Parameter parameter = factory.getParameters()[i];
                        if (!parameter.isAnnotationPresent(Value.class)) {
                            needs.add(new Need(ResolvableType.forMethodParameter(factory, i), qualifierOf(parameter)));
                        }
                    }
                }
                registerStandIns(beans, needs);
            }

            /** One injection point that needs a bean: its type and, when {@code @Qualifier} names one, the bean name. */
            private record Need(ResolvableType type, String qualifier) {
            }

            private static String qualifierOf(AnnotatedElement element) {
                Qualifier qualifier = element.getAnnotation(Qualifier.class);
                return qualifier == null || qualifier.value().isEmpty() ? null : qualifier.value();
            }

            @Override
            public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
                // nothing to do after the definitions are registered
            }

            /**
             * Reads the class files of the module's main source set under the base package, sorted by name. No
             * {@code @Profile} or {@code @Conditional} is evaluated for them: the contract is the whole API the code
             * declares, whatever the deployment switches off.
             */
            private List<MetadataReader> mainClassFiles() {
                ClassLoader loader = OpenApiSupport.class.getClassLoader();
                try {
                    Resource[] resources = new PathMatchingResourcePatternResolver(loader)
                            .getResources("classpath*:" + basePackage.replace('.', '/') + "/**/*.class");
                    MetadataReaderFactory factory = new SimpleMetadataReaderFactory(loader);
                    List<MetadataReader> readers = new ArrayList<>();
                    for (Resource resource : resources) {
                        if (isUnder(resource.getURI(), mainClasses)) {
                            readers.add(factory.getMetadataReader(resource));
                        }
                    }
                    readers.sort(Comparator.comparing(reader -> reader.getClassMetadata().getClassName()));
                    return readers;
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }

            private List<Class<?>> registerWebBeans(BeanDefinitionRegistry registry) {
                List<Class<?>> types = new ArrayList<>();
                for (MetadataReader reader : mainClassFiles()) {
                    AnnotationMetadata metadata = reader.getAnnotationMetadata();
                    if (metadata.isIndependent() && metadata.isConcrete()
                            && (metadata.isAnnotated(Controller.class.getName())
                                    || metadata.isAnnotated(ControllerAdvice.class.getName()))) {
                        registry.registerBeanDefinition(metadata.getClassName(), new ScannedGenericBeanDefinition(reader));
                        types.add(ClassUtils.resolveClassName(metadata.getClassName(), OpenApiSupport.class.getClassLoader()));
                    }
                }
                return types;
            }

            /** Registers each {@code @Bean} method of a main {@code @Configuration} class that returns an OpenAPI model. */
            private List<Method> registerOpenApiBeans(BeanDefinitionRegistry registry) {
                List<Method> factories = new ArrayList<>();
                for (MetadataReader reader : mainClassFiles()) {
                    AnnotationMetadata metadata = reader.getAnnotationMetadata();
                    if (!metadata.isConcrete() || !metadata.isAnnotated(Configuration.class.getName())) {
                        continue;
                    }
                    Class<?> host = ClassUtils.resolveClassName(metadata.getClassName(), OpenApiSupport.class.getClassLoader());
                    for (Method method : host.getDeclaredMethods()) {
                        if (method.isAnnotationPresent(Bean.class)
                                && method.getReturnType().getName().startsWith("io.swagger.v3.oas.models.OpenAPI")) {
                            String hostName = "openApiHost." + host.getName();
                            if (!registry.containsBeanDefinition(hostName)) {
                                registry.registerBeanDefinition(hostName, new RootBeanDefinition(host));
                            }
                            RootBeanDefinition factory = new RootBeanDefinition();
                            factory.setFactoryBeanName(hostName);
                            factory.setFactoryMethodName(method.getName());
                            factory.setAutowireMode(AutowireCapableBeanFactory.AUTOWIRE_CONSTRUCTOR);
                            registry.registerBeanDefinition("openApiBean." + host.getName() + "." + method.getName(), factory);
                            factories.add(method);
                        }
                    }
                }
                return factories;
            }

            private static void collectDependencies(Class<?> type, List<Need> out) {
                Constructor<?> constructor = null;
                for (Constructor<?> candidate : type.getDeclaredConstructors()) {
                    if (candidate.isAnnotationPresent(Autowired.class)) {
                        constructor = candidate;
                    }
                }
                if (constructor == null) {
                    try {
                        constructor = BeanUtils.getResolvableConstructor(type);
                    } catch (IllegalStateException e) {
                        constructor = null;
                    }
                }
                if (constructor != null) {
                    for (int i = 0; i < constructor.getParameterCount(); i++) {
                        Parameter parameter = constructor.getParameters()[i];
                        if (!parameter.isAnnotationPresent(Value.class)) {
                            out.add(new Need(ResolvableType.forConstructorParameter(constructor, i), qualifierOf(parameter)));
                        }
                    }
                }
                ReflectionUtils.doWithFields(type, field -> {
                    if (isInjected(field)) {
                        out.add(new Need(ResolvableType.forField(field), qualifierOf(field)));
                    }
                });
                ReflectionUtils.doWithMethods(type, method -> {
                    if (isInjected(method)) {
                        for (int i = 0; i < method.getParameterCount(); i++) {
                            out.add(new Need(ResolvableType.forMethodParameter(method, i),
                                    qualifierOf(method.getParameters()[i])));
                        }
                    }
                });
            }

            private static boolean isInjected(AnnotatedElement element) {
                Autowired autowired = element.getAnnotation(Autowired.class);
                boolean required = autowired != null && autowired.required();
                boolean jakarta = false;
                for (java.lang.annotation.Annotation annotation : element.getAnnotations()) {
                    String name = annotation.annotationType().getName();
                    jakarta |= "jakarta.inject.Inject".equals(name) || "jakarta.annotation.Resource".equals(name);
                }
                return (required || jakarta) && !element.isAnnotationPresent(Value.class) && !element.isAnnotationPresent(Lazy.class);
            }

            private static void registerStandIns(DefaultListableBeanFactory beans, List<Need> needs) {
                Map<Class<?>, ResolvableType> wanted = new LinkedHashMap<>();
                Map<String, Class<?>> named = new TreeMap<>();
                for (Need need : needs) {
                    ResolvableType bean = beanType(need.type());
                    Class<?> raw = bean == null ? null : bean.resolve();
                    if (raw == null || isIgnored(raw)) {
                        continue;
                    }
                    if (need.qualifier() != null) {
                        named.putIfAbsent(need.qualifier(), raw);
                    } else {
                        wanted.putIfAbsent(raw, bean);
                    }
                }
                // A qualified injection point is satisfied by a stand-in with that bean name.
                named.forEach((name, raw) -> {
                    if (!beans.containsBeanDefinition(name)) {
                        RootBeanDefinition definition = new RootBeanDefinition(raw);
                        definition.setInstanceSupplier(() -> standIn(raw));
                        beans.registerBeanDefinition(name, definition);
                    }
                });
                // Classes before interfaces, the most specific first, so a stand-in class also serves its interfaces.
                List<Class<?>> order = new ArrayList<>(wanted.keySet());
                order.sort(Comparator.comparing((Class<?> type) -> type.isInterface()).thenComparing(type -> -depth(type))
                        .thenComparing(Class::getName));
                for (Class<?> raw : order) {
                    if (beans.getBeanNamesForType(wanted.get(raw), true, false).length == 0) {
                        RootBeanDefinition definition = new RootBeanDefinition(raw);
                        definition.setInstanceSupplier(() -> standIn(raw));
                        beans.registerBeanDefinition("standIn." + raw.getName(), definition);
                    }
                }
            }

            /** The bean type an injection point needs, or null when the point is optional or lazy. */
            private static ResolvableType beanType(ResolvableType dependency) {
                Class<?> raw = dependency.resolve();
                if (raw == null || Optional.class.equals(raw) || ObjectProvider.class.equals(raw)
                        || ObjectFactory.class.equals(raw) || "jakarta.inject.Provider".equals(raw.getName())) {
                    return null;
                }
                if (raw.isArray()) {
                    return dependency.getComponentType();
                }
                if (Collection.class.isAssignableFrom(raw)) {
                    return dependency.asCollection().getGeneric(0);
                }
                if (Map.class.isAssignableFrom(raw)) {
                    return dependency.asMap().getGeneric(1);
                }
                return dependency;
            }

            private static boolean isIgnored(Class<?> raw) {
                return raw.isPrimitive() || raw.isEnum() || raw.equals(String.class) || raw.equals(Class.class)
                        || Number.class.isAssignableFrom(raw) || raw.equals(Boolean.class) || RESOLVABLE.contains(raw);
            }

            private static int depth(Class<?> type) {
                int depth = 0;
                for (Class<?> parent = type.getSuperclass(); parent != null; parent = parent.getSuperclass()) {
                    depth++;
                }
                return depth + type.getInterfaces().length;
            }

            /** An instance that is never called: a proxy for an interface, an uninitialised object for a class. */
            private static Object standIn(Class<?> type) {
                if (type.isInterface()) {
                    return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
                        if (method.getDeclaringClass().equals(Object.class)) {
                            return switch (method.getName()) {
                                case "equals" -> proxy == args[0];
                                case "hashCode" -> System.identityHashCode(proxy);
                                default -> "stand-in for " + type.getName();
                            };
                        }
                        Class<?> returned = method.getReturnType();
                        if (Optional.class.equals(returned)) {
                            return Optional.empty();
                        }
                        return returned.isPrimitive() && !returned.equals(void.class)
                                ? Array.get(Array.newInstance(returned, 1), 0) : null;
                    });
                }
                if (Modifier.isAbstract(type.getModifiers())) {
                    throw new IllegalStateException("The OpenAPI export cannot stand in for the abstract class "
                            + type.getName() + "; add support to OpenApiSupport in every service");
                }
                return new SpringObjenesis().newInstance(type);
            }
        }
    }

    // END SHARED OPENAPI SUPPORT
}
