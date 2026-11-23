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

// da-log-fixtures: the golden fixture corpus for the log layer and the gates that
// keep it honest (FIX-01). It is TEST SUPPORT ONLY: other modules may depend on it
// from test configurations, never from implementation, api or runtimeOnly. The
// verifyFixturesTestOnly task fails the build otherwise.
//
// Gates (all wired into `check`, so `:da-log-fixtures:build` runs them):
//   verifyFixtureProvenance  every case has a valid meta.yaml and an allowed licence
//   fixtureCoverage          documented operations and variants covered, per
//                            (family, envelope, version); supported entries must
//                            reach -Pdlap.fixtures.minScore (default 0.99)
//   verifyFixturesTestOnly   nothing depends on this module outside test scope
//   aggregateFixtureNotice   joins the per-family NOTICE.md files into the jar

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.tasks.Internal
import org.gradle.process.ExecOperations
import javax.inject.Inject

plugins {
    `java-library`
    // Version from the root build (BOOT-01, ADR-0065).
    id("io.spring.dependency-management")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

dependencyManagement {
    imports {
        // Same Spring Boot BOM the other modules use, so Jackson and JUnit versions line up.
        mavenBom(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES)
    }
}

// The root build applies the microservice conventions (Spring Boot, web, JPA, Kafka,
// Lombok ...) to every subproject that is not on its exclusion list. This module is a
// plain test-support library, so drop what it inherited and declare only what it
// needs. This also keeps those starters off the test classpath of consumers such as
// da-discovery. It is a no-op once the root build excludes this module by name.
listOf("implementation", "runtimeOnly", "compileOnly", "annotationProcessor", "testImplementation")
    .forEach { configurations.findByName(it)?.dependencies?.clear() }
// Applied after the clean-up above, which would otherwise remove the dependency this plugin adds to
// testImplementation. It lets a consumer write testImplementation(testFixtures(project(":da-log-fixtures")))
// as well as the plain project dependency; the empty test-fixtures variant passes the corpus through.
apply(plugin = "java-test-fixtures")
pluginManager.withPlugin("org.springframework.boot") {
    // No executable jar: the Boot plugin would otherwise look for a single main class
    // (this module has two command line tools) and build a fat jar nobody runs.
    tasks.named("resolveMainClassName") { enabled = false }
    tasks.named("bootJar") { enabled = false }
    tasks.named<Jar>("jar") { archiveClassifier.set("") }
}

dependencies {
    // FixtureCase#expectedTree() exposes Jackson types, hence api. Apache-2.0.
    api("com.fasterxml.jackson.core:jackson-databind")
    // meta.yaml and catalog.yaml. Apache-2.0 (brings snakeyaml, Apache-2.0).
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")
    // GCP-01: the gcpaudit case generator encodes BigQuery v1 AuditData into the api-proto form
    // ({typeUrl, base64}) and the proto round-trip check parses fixtures strictly against the
    // vendored descriptors (DynamicMessage, JsonFormat). Version from the Spring Boot BOM, the one
    // da-protos already ships to every module. BSD-3-Clause (protobuf).
    implementation("com.google.protobuf:protobuf-java")
    implementation("com.google.protobuf:protobuf-java-util")

    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// ---------------------------------------------------------------------------
// Vendored Google API protos: a descriptor set, no generated Java (GCP-01)
// ---------------------------------------------------------------------------
// protoc compiles src/main/proto (unmodified googleapis files, see src/main/proto/README.md) into
// one FileDescriptorSet with its imports, shipped in the jar as dlap-fixtures-proto/googleapis.desc.
// No Java classes are generated, so nothing here can clash with the com.google.cloud.audit or
// com.google.logging.v2 classes of the Google client libraries on a consumer's test class path.
//
// protoc runs from a plain task instead of the com.google.protobuf plugin: with this module's
// inherited conventions, the plugin's generateProto task cannot be stored in the configuration
// cache (Gradle reports DefaultSourceDirectorySet and ProtobufExtension references), which would
// fail every build that includes this module. The protoc binary is the one da-protos uses.
val protocClassifier: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val family = when {
        os.contains("win") -> "windows"
        os.contains("mac") || os.contains("darwin") -> "osx"
        else -> "linux"
    }
    val cpu = when (arch) {
        "amd64", "x86_64", "x64" -> "x86_64"
        "aarch64", "arm64" -> "aarch_64"
        else -> arch
    }
    "$family-$cpu"
}
val protocTool by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}
val protobufWellKnownTypes by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}
dependencies {
    // protoc 3.25.3 (BSD-3-Clause), the compiler da-protos uses; only its descriptor output is kept.
    protocTool("com.google.protobuf:protoc:3.25.3:$protocClassifier@exe")
    // The well-known type protos (google/protobuf) for the imports, from the protobuf-java release that matches protoc.
    protobufWellKnownTypes("com.google.protobuf:protobuf-java:4.36.2")
}

abstract class ProtoDescriptorSet : DefaultTask() {
    /** The vendored proto files. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val protoFiles: ConfigurableFileCollection

    /** The directory protoc resolves imports against (src/main/proto). */
    @get:Internal
    abstract val protoRoot: DirectoryProperty

    /** The protoc executable. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val protoc: ConfigurableFileCollection

    /** The protobuf-java jar that carries the well-known type protos. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val wellKnownTypes: ConfigurableFileCollection

    /** The descriptor set written. */
    @get:OutputFile
    abstract val descriptorSet: RegularFileProperty

    @get:Inject
    abstract val exec: ExecOperations

    @get:Inject
    abstract val archives: ArchiveOperations

    @get:Inject
    abstract val files: FileSystemOperations

    @TaskAction
    fun compile() {
        val work = temporaryDir
        val includes = File(work, "include")
        files.delete { delete(includes) }
        files.copy {
            from(archives.zipTree(wellKnownTypes.singleFile)) { include("google/protobuf/*.proto") }
            into(includes)
        }
        val exe = File(work, "protoc" + (if (protoc.singleFile.name.endsWith(".exe")) ".exe" else ""))
        protoc.singleFile.copyTo(exe, overwrite = true)
        exe.setExecutable(true)
        val root = protoRoot.get().asFile
        val sources = protoFiles.files.map { it.relativeTo(root).invariantSeparatorsPath }.sorted()
        val out = descriptorSet.get().asFile
        out.parentFile.mkdirs()
        exec.exec {
            executable = exe.absolutePath
            args("--include_imports", "--descriptor_set_out=" + out.absolutePath,
                "--proto_path=" + root.absolutePath, "--proto_path=" + includes.absolutePath)
            args(sources)
        }
    }
}

val protoRootDir = layout.projectDirectory.dir("src/main/proto")
val googleapisDescriptorSet by tasks.registering(ProtoDescriptorSet::class) {
    description = "Compiles the vendored googleapis protos into dlap-fixtures-proto/googleapis.desc (GCP-01)."
    protoFiles.from(fileTree(protoRootDir) { include("**/*.proto") })
    protoRoot.set(protoRootDir)
    protoc.from(protocTool)
    wellKnownTypes.from(protobufWellKnownTypes)
    descriptorSet.set(layout.buildDirectory.file("generated/proto-descriptor/googleapis.desc"))
}
tasks.processResources {
    from(googleapisDescriptorSet) {
        into("dlap-fixtures-proto")
    }
}

val corpusDir = layout.projectDirectory.dir("src/main/resources/dlap-fixtures")
val reportsDir = layout.buildDirectory.dir("reports/fixtures")
// Read at configuration time so the configuration cache records the property as a build input.
val minScore: String = providers.gradleProperty("dlap.fixtures.minScore").orElse("0.99").get()

// ---------------------------------------------------------------------------
// Provenance gate
// ---------------------------------------------------------------------------
val verifyFixtureProvenance by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Fails on any fixture case without valid meta.yaml, with a licence outside the allowlist, or without attribution."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.dlap.fixtures.ProvenanceVerifier")
    val report = reportsDir.map { it.file("provenance.txt") }
    args("--root", corpusDir.asFile.absolutePath, "--report", report.get().asFile.absolutePath)
    inputs.dir(corpusDir).withPropertyName("corpus")
    outputs.file(report).withPropertyName("report")
}

// ---------------------------------------------------------------------------
// Coverage score
// ---------------------------------------------------------------------------
val fixtureCoverage by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Scores documented operations and variants covered per (family, envelope, version); supported entries must reach -Pdlap.fixtures.minScore."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.dlap.fixtures.CoverageReport")
    val out = layout.buildDirectory.dir("reports/fixture-coverage")
    args("--root", corpusDir.asFile.absolutePath, "--out", out.get().asFile.absolutePath, "--min-score", minScore)
    inputs.dir(corpusDir).withPropertyName("corpus")
    inputs.property("minScore", minScore)
    outputs.dir(out).withPropertyName("coverageReport")
}

// ---------------------------------------------------------------------------
// Test-only guard: no module may use this one outside test configurations
// ---------------------------------------------------------------------------
abstract class VerifyTestOnly : DefaultTask() {
    /** One entry per offending declaration, computed when the build is configured. */
    @get:Input
    abstract val offenders: ListProperty<String>

    @get:OutputFile
    abstract val marker: RegularFileProperty

    @TaskAction
    fun verify() {
        val bad = offenders.get()
        if (bad.isNotEmpty()) {
            throw GradleException(
                "da-log-fixtures is test support only. Depend on it from a test configuration " +
                    "(testImplementation, testRuntimeOnly):\n  " + bad.joinToString("\n  ")
            )
        }
        marker.get().asFile.apply {
            parentFile.mkdirs()
            writeText("ok\n")
        }
    }
}

val verifyFixturesTestOnly by tasks.registering(VerifyTestOnly::class) {
    group = "verification"
    description = "Fails if any module depends on da-log-fixtures from a non-test configuration."
    val self = project.path
    val root = project.rootProject
    offenders.set(provider {
        root.allprojects
            .filter { it.path != self }
            .flatMap { p ->
                p.configurations
                    .filter { c -> !(c.name.startsWith("test") || c.name.startsWith("integrationTest")) }
                    .flatMap { c ->
                        c.dependencies.withType(ProjectDependency::class.java)
                            .filter { d -> d.dependencyProject.path == self }
                            .map { _ -> "${p.path}: configuration '${c.name}' depends on $self" }
                    }
            }
            .sorted()
    })
    marker.set(reportsDir.map { it.file("test-only.ok") })
}

// ---------------------------------------------------------------------------
// Per-family NOTICE files aggregate at build time, so no shared file is edited
// ---------------------------------------------------------------------------
abstract class AggregateFixtureNotice : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val familyNotices: ConfigurableFileCollection

    @get:OutputFile
    abstract val aggregate: RegularFileProperty

    @TaskAction
    fun aggregateNotices() {
        val text = StringBuilder()
        text.append("DLAP fixture corpus: third-party notices\n")
        text.append("========================================\n\n")
        text.append("Generated from <family>/NOTICE.md at build time. Do not edit.\n")
        familyNotices.files.sortedBy { it.parentFile.name }.forEach { f ->
            text.append("\n--- ").append(f.parentFile.name).append(" ---\n\n")
            text.append(f.readText().trim()).append("\n")
        }
        aggregate.get().asFile.apply {
            parentFile.mkdirs()
            writeText(text.toString())
        }
    }
}

val aggregateFixtureNotice by tasks.registering(AggregateFixtureNotice::class) {
    description = "Joins every <family>/NOTICE.md into META-INF/dlap-fixtures-NOTICE.md of the jar."
    familyNotices.from(fileTree(corpusDir) { include("*/NOTICE.md") })
    aggregate.set(layout.buildDirectory.file("generated/fixture-notice/dlap-fixtures-NOTICE.md"))
}

tasks.processResources {
    from(aggregateFixtureNotice) {
        into("META-INF")
    }
}

tasks.check {
    dependsOn(verifyFixtureProvenance, fixtureCoverage, verifyFixturesTestOnly)
}
