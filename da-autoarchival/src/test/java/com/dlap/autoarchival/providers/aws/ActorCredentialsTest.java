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

package com.dlap.autoarchival.providers.aws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.dlap.autoarchival.service.storage.impl.AwsS3StorageService;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;

/**
 * EXEC-01 (Q-722, ADR-0110): the archive executor can assume an Actor role with STS. A credentials-ref that is an IAM
 * role ARN (or a variable holding one) builds a role-assuming provider with the optional external id; with none the
 * default chain, which also carries an EKS web identity token (IRSA), stays the Actor. Nothing is called on AWS.
 */
class ActorCredentialsTest {

    private static final String ROLE = "arn:aws:iam::123456789012:role/dlap-archive-actor";
    private static final String STS_RESPONSE = """
            <AssumeRoleResponse xmlns="https://sts.amazonaws.com/doc/2011-06-15/">
              <AssumeRoleResult>
                <Credentials>
                  <AccessKeyId>ASIASTUBSESSION</AccessKeyId>
                  <SecretAccessKey>stub-secret</SecretAccessKey>
                  <SessionToken>stub-token</SessionToken>
                  <Expiration>2099-01-01T00:00:00Z</Expiration>
                </Credentials>
                <AssumedRoleUser>
                  <AssumedRoleId>AROASTUB:dlap-autoarchival</AssumedRoleId>
                  <Arn>arn:aws:sts::123456789012:assumed-role/dlap-archive-actor/dlap-autoarchival</Arn>
                </AssumedRoleUser>
              </AssumeRoleResult>
              <ResponseMetadata><RequestId>stub</RequestId></ResponseMetadata>
            </AssumeRoleResponse>
            """;

    /** Q-722: a role ARN builds an STS-assumed provider, and building it calls nothing. */
    @Test
    void theRoleArnCredentialsBuildAnStsAssumedProviderAndCallNothing() {
        StsClient sts = mock(StsClient.class);
        ActorCredentials credentials = new ActorCredentials(ROLE, "ext-1", "", "", k -> null, caller -> sts);

        AwsCredentialsProvider provider = credentials.provider();

        assertThat(credentials.assumesRole()).isTrue();
        assertThat(credentials.delegate()).isInstanceOf(StsAssumeRoleCredentialsProvider.class);
        assertThat(credentials.provider()).as("built once and shared").isSameAs(provider);
        verifyNoInteractions(sts);
        AssumeRoleRequest request = credentials.assumeRoleRequest();
        assertThat(request.roleArn()).isEqualTo(ROLE);
        assertThat(request.roleSessionName()).isEqualTo("dlap-autoarchival");
        assertThat(request.externalId()).isEqualTo("ext-1");
    }

    /** Q-722: the external id is optional. */
    @Test
    void withoutAnExternalIdTheRequestSendsNone() {
        ActorCredentials credentials = new ActorCredentials(ROLE, "", "", "", k -> null, caller -> mock(StsClient.class));

        assertThat(credentials.assumeRoleRequest().externalId()).isNull();
    }

    /** Q-722: the reference may name an environment variable that holds the role ARN, as in ADR-0110. */
    @Test
    void aVariableNameOrEnvPrefixResolvesTheRoleFromTheEnvironment() {
        Map<String, String> env = Map.of("DLAP_ARCHIVE_ROLE", ROLE);

        assertThat(new ActorCredentials("DLAP_ARCHIVE_ROLE", "", "", "", env::get, c -> mock(StsClient.class))
                .assumeRoleRequest().roleArn()).isEqualTo(ROLE);
        assertThat(new ActorCredentials("env:DLAP_ARCHIVE_ROLE", "", "", "", env::get, c -> mock(StsClient.class))
                .assumeRoleRequest().roleArn()).isEqualTo(ROLE);
    }

    /** Q-722: a reference that is no role ARN fails at start-up, and the message never holds the value. */
    @Test
    void aReferenceThatIsNoRoleArnIsRefusedWithoutEchoingIt() {
        assertThatThrownBy(() -> new ActorCredentials("arn:aws:iam::123456789012:user/alice", "", "", "", k -> null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IAM role ARN")
                .hasMessageNotContaining("alice");
        assertThatThrownBy(() -> new ActorCredentials("not a name!", "", "", "", k -> null, null))
                .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("not a name");
        assertThatThrownBy(() -> new ActorCredentials("MISSING_VAR", "", "", "", k -> null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MISSING_VAR does not hold an IAM role ARN");
        assertThatThrownBy(() -> new ActorCredentials("HOLDS_USER", "", "", "", k -> "arn:aws:iam::1:user/bob", null))
                .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("bob");
    }

    /** Q-722: with no reference the default chain is the Actor, so IRSA web identity works without configuration. */
    @Test
    void withoutAReferenceTheDefaultChainStaysTheActorAndStsIsNeverBuilt() {
        AtomicInteger stsBuilt = new AtomicInteger();
        ActorCredentials credentials = new ActorCredentials("", "", "", "", k -> null, caller -> {
            stsBuilt.incrementAndGet();
            return mock(StsClient.class);
        });

        assertThat(credentials.assumesRole()).isFalse();
        assertThat(credentials.delegate()).isSameAs(DefaultCredentialsProvider.create());
        assertThat(ActorCredentials.defaultChain().assumesRole()).isFalse();
        assertThat(stsBuilt).hasValue(0);
    }

    /**
     * Q-722: the AssumeRole call that reaches STS carries the role, the session name and the external id, and the
     * session credentials it answers are what the provider hands the S3 client. A local stub stands in for STS.
     */
    @Test
    void theAssumeRoleCallCarriesTheRoleSessionNameAndExternalId() throws Exception {
        CopyOnWriteArrayList<String> bodies = new CopyOnWriteArrayList<>();
        HttpServer stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = STS_RESPONSE.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/xml");
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        stub.start();
        try {
            URI endpoint = URI.create("http://127.0.0.1:" + stub.getAddress().getPort());
            // The STS client points at the stub; the caller signs with fixed keys.
            ActorCredentials viaStub = new ActorCredentials(ROLE, "ext-9", "", "", k -> null, caller ->
                    StsClient.builder().region(Region.US_EAST_1).endpointOverride(endpoint)
                            .credentialsProvider(StaticCredentialsProvider.create(
                                    AwsBasicCredentials.create("AKIACALLER", "caller-secret")))
                            .build());

            AwsCredentials resolved = viaStub.provider().resolveCredentials();

            assertThat(resolved.accessKeyId()).isEqualTo("ASIASTUBSESSION");
            assertThat(((AwsSessionCredentials) resolved).sessionToken()).isEqualTo("stub-token");
            assertThat(bodies).hasSize(1);
            String body = java.net.URLDecoder.decode(bodies.get(0), StandardCharsets.UTF_8);
            assertThat(body).contains("Action=AssumeRole").contains("RoleArn=" + ROLE)
                    .contains("RoleSessionName=dlap-autoarchival").contains("ExternalId=ext-9");
            viaStub.destroy();
        } finally {
            stub.stop(0);
        }
    }

    /** Q-722: the real STS client honours the optional endpoint and region settings; building it calls nothing. */
    @Test
    void theRealStsClientIsBuiltFromTheEndpointAndRegionSettingsWithoutACall() {
        ActorCredentials credentials = new ActorCredentials(ROLE, "", "http://127.0.0.1:9", "eu-west-1");

        assertThat(credentials.delegate()).isInstanceOf(StsAssumeRoleCredentialsProvider.class);
        credentials.destroy();
    }

    /**
     * Q-722: executors build a client per call inside try-with-resources, and the SDK closes a closeable provider
     * together with its client. The shared provider is therefore handed out behind a view that cannot be closed.
     */
    @Test
    void closingAClientDoesNotCloseTheSharedProvider() {
        class Counting implements AwsCredentialsProvider, AutoCloseable {
            final AtomicInteger closed = new AtomicInteger();

            @Override
            public AwsCredentials resolveCredentials() {
                return AwsBasicCredentials.create("AKIATEST", "secret");
            }

            @Override
            public void close() {
                closed.incrementAndGet();
            }
        }
        // The premise: a client closes a closeable provider that it was given.
        Counting raw = new Counting();
        try (S3Client client = rawClient(raw)) {
            assertThat(client).isNotNull();
        }
        assertThat(raw.closed.get()).as("the SDK closes a closeable provider with its client").isPositive();

        // The executor's provider is not closeable, so any number of clients can come and go.
        ActorCredentials credentials = new ActorCredentials(ROLE, "", "", "", k -> null, caller -> mock(StsClient.class));
        AwsCredentialsProvider shared = credentials.provider();
        assertThat(shared).isNotInstanceOf(AutoCloseable.class);
        for (int i = 0; i < 2; i++) {
            try (S3Client client = rawClient(shared)) {
                assertThat(client.serviceClientConfiguration().credentialsProvider()).isSameAs(shared);
            }
        }
        assertThat(credentials.provider()).isSameAs(shared);
        assertThat(ActorCredentials.defaultChain().provider()).isNotInstanceOf(AutoCloseable.class);
    }

    /** Q-722: the lifecycle rule provider's client signs with the Actor role (the region-following client). */
    @Test
    void theRuleProviderClientSignsWithTheActorRole() {
        ActorCredentials credentials = new ActorCredentials(ROLE, "", "", "", k -> null, caller -> mock(StsClient.class));

        try (S3Client s3 = S3LifecycleRuleProvider.build("us-east-1", "", "", "", true, credentials)) {
            assertThat(s3.serviceClientConfiguration().credentialsProvider()).isSameAs(credentials.provider());
        }
    }

    /** Q-722: the test endpoint client signs with the role when one is configured, with path-style by default. */
    @Test
    void theTestEndpointClientSignsWithTheRoleWhenOneIsConfigured() {
        ActorCredentials credentials = new ActorCredentials(ROLE, "", "", "", k -> null, caller -> mock(StsClient.class));

        try (S3Client s3 = S3LifecycleRuleProvider.build("us-east-1", "http://127.0.0.1:9", "", "", true, credentials)) {
            assertThat(s3.serviceClientConfiguration().credentialsProvider()).isSameAs(credentials.provider());
        }
    }

    /** Q-740: without a role the test endpoint client signs with the default chain, else anonymously. */
    @Test
    void theTestEndpointClientWithoutARoleUsesTheDefaultChainThenAnonymous() {
        try (S3Client s3 = S3LifecycleRuleProvider.build("us-east-1", "http://127.0.0.1:9", "", "", true,
                ActorCredentials.defaultChain())) {
            assertThat(s3.serviceClientConfiguration().credentialsProvider().resolveIdentity()).isNotNull();
        }
    }

    /** Q-722: the storage service (copy, restore) signs with the Actor role too. */
    @Test
    void theStorageServiceSignsWithTheActorRole() {
        ActorCredentials credentials = new ActorCredentials(ROLE, "", "", "", k -> null, caller -> mock(StsClient.class));
        AwsS3StorageService service = new AwsS3StorageService("us-east-1", "", "", "", true, credentials);

        S3Client s3 = (S3Client) org.springframework.test.util.ReflectionTestUtils.getField(service, "s3Client");
        assertThat(s3.serviceClientConfiguration().credentialsProvider()).isSameAs(credentials.provider());
        s3.close();
    }

    /** Q-722: Spring wires the credentials from dlap.autoarchival.aws.*, and a bad reference stops the start. */
    @Test
    void springBindsTheSettingsAndAMalformedReferenceStopsTheStart() {
        ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(ActorCredentials.class);

        runner.withPropertyValues("dlap.autoarchival.aws.credentials-ref=" + ROLE,
                        "dlap.autoarchival.aws.external-id=ext-2")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    ActorCredentials bean = ctx.getBean(ActorCredentials.class);
                    assertThat(bean.assumesRole()).isTrue();
                    assertThat(bean.assumeRoleRequest().externalId()).isEqualTo("ext-2");
                });
        runner.run(ctx -> assertThat(ctx.getBean(ActorCredentials.class).assumesRole()).isFalse());
        runner.withPropertyValues("dlap.autoarchival.aws.credentials-ref=arn:aws:iam::1:role/short")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    /** Q-722: the shipped defaults leave the Actor on the default chain. */
    @Test
    void theShippedDefaultsAssumeNoRole() throws Exception {
        java.util.Properties shipped = new java.util.Properties();
        try (var in = ActorCredentialsTest.class.getResourceAsStream("/application.properties")) {
            shipped.load(in);
        }
        assertThat(shipped.getProperty("dlap.autoarchival.aws.credentials-ref")).contains("DLAP_AUTOARCHIVAL_AWS_CREDENTIALS_REF");
        assertThat(new ActorCredentials("", "", "", "").assumesRole()).isFalse();
    }

    private static S3Client rawClient(AwsCredentialsProvider credentials) {
        return S3Client.builder().region(Region.US_EAST_1).endpointOverride(URI.create("http://127.0.0.1:9"))
                .forcePathStyle(true).credentialsProvider(credentials).build();
    }
}
