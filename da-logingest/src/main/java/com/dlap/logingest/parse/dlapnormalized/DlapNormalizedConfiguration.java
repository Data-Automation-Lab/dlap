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

package com.dlap.logingest.parse.dlapnormalized;

import com.dlap.proto.log.v1.SourceProvider;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.type.AnnotationMetadata;

/**
 * Registers the {@code DLAP_NORMALIZED} family's parser and detector beans, one pair per provider,
 * so the registry collects them like any family (ADR-0054 decision 2). The beans are inert on the
 * legacy pipeline: nothing calls the registry there.
 */
@Configuration(proxyBeanMethods = false)
@Import(DlapNormalizedConfiguration.Registrar.class)
public class DlapNormalizedConfiguration {

    /** Adds one parser and one detector bean definition per provider. */
    static final class Registrar implements ImportBeanDefinitionRegistrar {

        @Override
        public void registerBeanDefinitions(AnnotationMetadata metadata, BeanDefinitionRegistry registry) {
            for (SourceProvider provider : DlapNormalizedFormat.providers()) {
                String suffix = provider.name().toLowerCase(java.util.Locale.ROOT);
                registry.registerBeanDefinition("dlapNormalizedParser_" + suffix, new RootBeanDefinition(
                        DlapNormalizedParser.class, () -> new DlapNormalizedParser(provider)));
                registry.registerBeanDefinition("dlapNormalizedDetector_" + suffix, new RootBeanDefinition(
                        DlapNormalizedDetector.class, () -> new DlapNormalizedDetector(provider)));
            }
        }
    }
}
