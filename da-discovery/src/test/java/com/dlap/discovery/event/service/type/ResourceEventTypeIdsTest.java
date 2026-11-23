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
package com.dlap.discovery.event.service.type;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

import com.dlap.discovery.common.model.CloudResource;
import com.dlap.discovery.common.model.CloudService;
import com.dlap.discovery.common.model.ResourceChange.ChangeType;
import com.dlap.discovery.common.model.ResourceType;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.crawler.model.gcp.GcsResource;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.exc.InvalidTypeIdException;

/**
 * SEC-03: the payload of a {@link ResourceEvent} is typed by a name from a closed list, never by a
 * class name. The tests pin the list against the classes in the code base, the round trip through the
 * Kafka serializers the services use, and the refusal of every other type id.
 */
@SuppressWarnings({"removal", "deprecation"})
class ResourceEventTypeIdsTest {

    private static final String PAYLOAD_FIELD = "payload";

    /** The resource type id each class insists on; every other class takes the lower-cased wire name. */
    private static final Map<String, String> RESOURCE_TYPE_IDS = Map.ofEntries(
            Map.entry("AWS_S3", "aws_s3_bucket"),
            Map.entry("AWS_EC2", "aws_ec2_instance"),
            Map.entry("AWS_RDS", "aws_rds_instance"),
            Map.entry("AWS_GLUE", "aws_glue_table"),
            Map.entry("AWS_PROCESSING_JOB", "aws_glue_job"),
            Map.entry("AZURE_VM", "azure_vm"),
            Map.entry("AZURE_PROCESSING_JOB", "azure_data_factory_pipeline"),
            Map.entry("GCP_GCS", "gcp_gcs_bucket"),
            Map.entry("GCP_BIGQUERY", "gcp_bigquery_table"),
            Map.entry("GCP_CLOUDSQL", "gcp_cloudsql_instance"),
            Map.entry("GCP_COMPUTE", "gcp_compute_instance"),
            Map.entry("GCP_BIGQUERY_JOB", "gcp_bigquery_job"),
            Map.entry("DATABRICKS", "databricks_job"));

    @Test
    void everyConcreteCloudResourceHasAWireName() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(CloudResource.class));
        Set<Class<?>> concrete = new HashSet<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents("com.dlap.discovery")) {
            concrete.add(Class.forName(candidate.getBeanClassName()));
        }
        Set<Class<?>> named = new HashSet<>();
        for (JsonSubTypes.Type type : listedTypes()) {
            named.add(type.value());
        }

        assertThat(concrete).as("concrete CloudResource subclasses found by scanning").hasSizeGreaterThan(15);
        assertThat(named).as("classes named on ResourceEvent.payload (add a name for a new subclass)")
                .containsAll(concrete);
        assertThat(concrete).as("every named class is concrete and exists").containsAll(named);
    }

    @Test
    void namesAreUniqueAndNeverClassNames() {
        List<String> names = new ArrayList<>();
        for (JsonSubTypes.Type type : listedTypes()) {
            names.add(type.name());
        }

        assertThat(names).doesNotHaveDuplicates().allSatisfy(name -> assertThat(name).matches("[A-Z][A-Z0-9_]*"));
    }

    @Test
    void theDiscriminatorIsANameNotAClass() throws Exception {
        JsonTypeInfo info = ResourceEvent.class.getDeclaredField(PAYLOAD_FIELD).getAnnotation(JsonTypeInfo.class);

        assertThat(info.use()).isEqualTo(JsonTypeInfo.Id.NAME);
        assertThat(info.property()).isEqualTo("@type");
    }

    @Test
    void everyListedTypeSurvivesTheKafkaRoundTripUnderItsName() throws Exception {
        JsonSerializer<ResourceEvent> serializer = new JsonSerializer<>();
        JsonDeserializer<ResourceEvent> deserializer = deserializer();

        for (JsonSubTypes.Type type : listedTypes()) {
            CloudResource resource = instance(type);
            ResourceEvent sent = new ResourceEvent(resource.getCloudProviderEnum().name(), "acct", ChangeType.CREATE, resource);

            String json = new String(serializer.serialize("t", sent), StandardCharsets.UTF_8);
            ResourceEvent received = deserializer.deserialize("t", json.getBytes(StandardCharsets.UTF_8));

            assertThat(json).as(type.name()).contains("\"@type\":\"" + type.name() + "\"").doesNotContain("@class");
            assertThat(received.getPayload()).as(type.name()).isExactlyInstanceOf(type.value());
            assertThat(received.getPayload().getResourceId()).as(type.name()).isNotBlank();
        }
    }

    @Test
    void aClassNameIsNotATypeId() {
        JsonDeserializer<ResourceEvent> deserializer = deserializer();
        String gcs = GcsResource.class.getName();

        for (String hostile : List.of(gcs, "java.lang.ProcessBuilder", "javax.script.ScriptEngineManager",
                "org.springframework.context.support.ClassPathXmlApplicationContext", "gcp_gcs", "")) {
            byte[] json = event("{\"@type\":\"" + hostile + "\",\"resourceId\":\"r\"}");
            assertThatThrownBy(() -> deserializer.deserialize("t", json)).as("type id " + hostile)
                    .hasRootCauseInstanceOf(InvalidTypeIdException.class);
        }
    }

    @Test
    void aPayloadWithoutATypeIdIsRefused() {
        JsonDeserializer<ResourceEvent> deserializer = deserializer();

        // The pre-SEC-03 wire shape: a class name under @class and no @type.
        byte[] legacy = event("{\"@class\":\"" + GcsResource.class.getName() + "\",\"resourceId\":\"r\"}");

        assertThatThrownBy(() -> deserializer.deserialize("t", legacy)).hasRootCauseInstanceOf(InvalidTypeIdException.class)
                .rootCause().hasMessageContaining("missing type id");
    }

    private static JsonSubTypes.Type[] listedTypes() {
        try {
            return ResourceEvent.class.getDeclaredField(PAYLOAD_FIELD).getAnnotation(JsonSubTypes.class).value();
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonDeserializer<ResourceEvent> deserializer() {
        JsonDeserializer<ResourceEvent> deserializer = new JsonDeserializer<>(ResourceEvent.class, false);
        deserializer.addTrustedPackages("com.dlap.discovery.event.service.type");
        deserializer.setUseTypeHeaders(false);
        return deserializer;
    }

    private static byte[] event(String payload) {
        return ("{\"eventId\":\"e1\",\"changeType\":\"CREATE\",\"payload\":" + payload + "}")
                .getBytes(StandardCharsets.UTF_8);
    }

    /** Builds a resource of the listed class; the provider is the part of its name before the first underscore. */
    private static CloudResource instance(JsonSubTypes.Type type) throws Exception {
        String name = type.name();
        CloudProvider provider = CloudProvider.valueOf(name.contains("_") ? name.substring(0, name.indexOf('_')) : name);
        ResourceType resourceType = new ResourceType(RESOURCE_TYPE_IDS.getOrDefault(name, name.toLowerCase()), name,
                new CloudService("svc", "Svc", provider));
        if (Modifier.isAbstract(type.value().getModifiers())) {
            throw new IllegalStateException(name + " is abstract");
        }
        for (Constructor<?> constructor : type.value().getConstructors()) {
            Class<?>[] parameters = constructor.getParameterTypes();
            if (parameters.length == 3 && parameters[0] == ResourceType.class && parameters[1] == String.class
                    && parameters[2] == String.class) {
                return (CloudResource) constructor.newInstance(resourceType, "rid-1", "name-1");
            }
            if (parameters.length == 4 && parameters[0] == ResourceType.class) {
                // Unity Catalog: catalog, schema, table. The resource id is the three-level name.
                return (CloudResource) constructor.newInstance(resourceType, "rid", "s", "t");
            }
        }
        throw new IllegalStateException("no usable constructor on " + type.value());
    }
}
