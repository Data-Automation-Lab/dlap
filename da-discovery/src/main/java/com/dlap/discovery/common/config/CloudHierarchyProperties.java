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

package com.dlap.discovery.common.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

import com.dlap.discovery.common.model.enums.CloudProvider;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

@Configuration
@ConfigurationProperties(prefix = "cloud-hierarchy")
@Validated
public class CloudHierarchyProperties {

    @NotEmpty
    @Valid
    private List<ProviderConfig> providers = new ArrayList<>();

    public static class ProviderConfig {
        @NotNull // Ensure the enum name is provided and valid
        private CloudProvider provider;

        @NotEmpty
        @Valid
        private List<ServiceConfig> services = new ArrayList<>();

        // Manual Getters and Setters
        public CloudProvider getProvider() {
            return provider;
        }

        public void setProvider(CloudProvider provider) {
            this.provider = provider;
        }

        public List<ServiceConfig> getServices() {
            return services;
        }

        public void setServices(List<ServiceConfig> services) {
            this.services = services;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;
            ProviderConfig that = (ProviderConfig) o;
            return provider == that.provider && Objects.equals(services, that.services);
        }

        @Override
        public int hashCode() {
            return Objects.hash(provider, services);
        }

        @Override
        public String toString() {
            return "ProviderConfig{" +
                    "provider=" + provider +
                    ", services=" + services +
                    '}';
        }
    }

    public static class ServiceConfig {
        @NotBlank
        private String id;

        @NotBlank
        private String displayName;

        @NotEmpty // A service must define at least one resource type
        @Valid
        private List<ResourceTypeConfig> resourceTypes = new ArrayList<>();

        // Manual Getters and Setters
        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        public List<ResourceTypeConfig> getResourceTypes() {
            return resourceTypes;
        }

        public void setResourceTypes(List<ResourceTypeConfig> resourceTypes) {
            this.resourceTypes = resourceTypes;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;
            ServiceConfig that = (ServiceConfig) o;
            return Objects.equals(id, that.id) && Objects.equals(displayName, that.displayName)
                    && Objects.equals(resourceTypes, that.resourceTypes);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, displayName, resourceTypes);
        }

        @Override
        public String toString() {
            return "ServiceConfig{" +
                    "id='" + id + '\'' +
                    ", displayName='" + displayName + '\'' +
                    ", resourceTypes=" + resourceTypes +
                    '}';
        }
    }

    public static class ResourceTypeConfig {
        @NotBlank
        private String id;

        @NotBlank
        private String displayName;

        // Manual Getters and Setters
        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;
            ResourceTypeConfig that = (ResourceTypeConfig) o;
            return Objects.equals(id, that.id) && Objects.equals(displayName, that.displayName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, displayName);
        }

        @Override
        public String toString() {
            return "ResourceTypeConfig{" +
                    "id='" + id + '\'' +
                    ", displayName='" + displayName + '\'' +
                    '}';
        }
    }

    // Manual Getters and Setters for CloudHierarchyProperties
    public List<ProviderConfig> getProviders() {
        return providers;
    }

    public void setProviders(List<ProviderConfig> providers) {
        this.providers = providers;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        CloudHierarchyProperties that = (CloudHierarchyProperties) o;
        return Objects.equals(providers, that.providers);
    }

    @Override
    public int hashCode() {
        return Objects.hash(providers);
    }

    @Override
    public String toString() {
        return "CloudHierarchyProperties{" +
                "providers=" + providers +
                '}';
    }
}