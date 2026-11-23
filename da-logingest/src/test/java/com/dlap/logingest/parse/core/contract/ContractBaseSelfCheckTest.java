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

package com.dlap.logingest.parse.core.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlap.logingest.parse.core.BindingConfig;
import com.dlap.logingest.parse.core.CanonicalEvent;
import com.dlap.logingest.parse.core.DefaultParserRegistry;
import com.dlap.logingest.parse.core.FormatKey;
import com.dlap.logingest.parse.core.ParseResult;
import com.dlap.logingest.parse.core.ParserRegistry;
import com.dlap.logingest.parse.core.RawRecord;
import com.dlap.logingest.parse.core.VersionRange;
import com.dlap.logingest.parse.core.mapping.MappingDrivenParser;
import com.dlap.logingest.parse.core.mapping.MappingSpec;
import com.dlap.logingest.parse.core.mapping.MappingSpecs;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.opentest4j.TestAbortedException;

/**
 * The contract base must fail a family whose parser breaks the contract. Each test plugs a
 * deliberately broken parser into the contract-demo run and expects the matching check to fail.
 */
class ContractBaseSelfCheckTest {

    private static final MappingSpec SPEC = AbstractParserContractTest.mappingResource(
            ContractDemoParserContractTest.MAPPING, "contract-demo");
    private static final VersionRange RANGE = SPEC.envelope("generic-json").orElseThrow().ranges().get(0);

    /** A contract-demo run whose registry holds the given parser. */
    private static final class Run extends ContractDemoParserContractTest {
        private final Function<MappingSpec, ParserRegistry> registry;

        Run(Function<MappingSpec, ParserRegistry> registry) {
            this.registry = registry;
        }

        @Override
        protected ParserRegistry registry() {
            return registry.apply(SPEC);
        }
    }

    private static ParserRegistry with(MappingDrivenParser parser) {
        return AbstractParserContractTest.registryOf(SPEC, List.of(parser));
    }

    /** Runs the dynamic tests and returns how many failed (aborted ones do not count). */
    private static int failures(Stream<DynamicTest> tests) {
        List<Throwable> failed = new ArrayList<>();
        tests.forEach(t -> {
            try {
                t.getExecutable().execute();
            } catch (TestAbortedException aborted) {
                // an unsupported version, not a failure
            } catch (Throwable e) {
                failed.add(e);
            }
        });
        return failed.size();
    }

    @Test
    void theRealParserPassesEveryCheck() {
        Run run = new Run(spec -> with(new MappingDrivenParser(spec, "generic-json", RANGE, "contract-demo-parser",
                "1.0.0")));
        assertThat(failures(run.positiveCases())).isZero();
        assertThat(failures(run.negativeCases())).isZero();
        assertThat(failures(run.eventsHonourTheWireContract())).isZero();
        assertThat(failures(run.unknownFieldsAreReportedNotFatal())).isZero();
        assertThat(failures(run.parsersAreTotal())).isZero();
    }

    @Test
    void aWrongFieldFailsTheExpectedEvents() {
        Run run = new Run(spec -> with(new MappingDrivenParser(spec, "generic-json", RANGE, "contract-demo-parser",
                "1.0.0") {
            @Override
            protected CanonicalEvent.Resource target(Context ctx) {
                // forgets the AssetIdNormalizer step
                return CanonicalEvent.Resource.builder().id(ctx.fields().text(
                        com.dlap.logingest.parse.core.CanonicalField.TARGET_ID).orElse("")).build();
            }
        }));
        assertThat(failures(run.positiveCases())).isPositive();
    }

    @Test
    void aWrongParserVersionTagFailsTheExpectedEvents() {
        Run run = new Run(spec -> with(new MappingDrivenParser(spec, "generic-json", RANGE, "contract-demo-parser",
                "9.9.9")));
        assertThat(failures(run.positiveCases())).isPositive();
    }

    @Test
    void aParserThatThrowsFailsTotality() {
        Run run = new Run(spec -> with(new MappingDrivenParser(spec, "generic-json", RANGE, "contract-demo-parser",
                "1.0.0") {
            @Override
            public ParseResult parse(RawRecord record, FormatKey effective, BindingConfig binding) {
                if (record.body().isObject() && !record.body().has("who")) {
                    throw new IllegalStateException("bug: assumes the principal is always present");
                }
                return super.parse(record, effective, binding);
            }
        }));
        assertThat(failures(run.parsersAreTotal())).isPositive();
    }

    @Test
    void aNondeterministicParserFailsTheWireContract() {
        AtomicInteger counter = new AtomicInteger();
        Run run = new Run(spec -> with(new MappingDrivenParser(spec, "generic-json", RANGE, "contract-demo-parser",
                "1.0.0") {
            @Override
            public ParseResult parse(RawRecord record, FormatKey effective, BindingConfig binding) {
                ParseResult r = super.parse(record, effective, binding);
                if (r instanceof ParseResult.Parsed p) {
                    CanonicalEvent e = p.events().get(0);
                    return ParseResult.Parsed.of(e.toBuilder().nativeKey(e.nativeKey() + "-" + counter.incrementAndGet())
                            .build());
                }
                return r;
            }
        }));
        assertThat(failures(run.eventsHonourTheWireContract())).isPositive();
    }

    @Test
    void aSupportedVersionWithoutParserFailsTheCatalogCheck() {
        Run run = new Run(spec -> new DefaultParserRegistry(List.of(), new MappingSpecs(List.of(spec)).detectors()));
        assertThatThrownBy(run::everySupportedCatalogVersionHasAParser).isInstanceOf(AssertionError.class);
    }
}
