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

package com.dlap.logingest.providers.aws.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dlap.logingest.parse.cloudtrail.CloudTrailFormat;
import com.dlap.logingest.transport.StreamContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;
import software.amazon.awssdk.services.sts.model.AssumeRoleResponse;
import software.amazon.awssdk.services.sts.model.Credentials;

/**
 * Client settings of the S3 log transports come from the stream's configuration only: the bucket
 * region, the endpoint and path-style addressing, the default credentials chain or a named profile,
 * or the IAM role a {@code credentials-ref} names (assumed with STS, stubbed here), never a value in code.
 */
class SdkS3LogStoreTest {

    private static StreamContext ctx(String region, Map<String, String> location, String credentialsRef) {
        return AwsS3ForTests.context(CloudTrailTrailFileTransport.ID, CloudTrailFormat.SOURCE_KIND,
                CloudTrailFormat.S3_FILE, region, location, new AwsS3ForTests.DoneObjects(), 1024, credentialsRef);
    }

    @Test
    void theRegionIsTheLocationKeyElseTheScopeRegion() {
        assertThat(SdkS3LogStore.region(ctx("us-east-1", Map.of("region", "eu-west-1"), null)))
                .isEqualTo(Region.EU_WEST_1);
        assertThat(SdkS3LogStore.region(ctx("ap-southeast-2", Map.of(), null))).isEqualTo(Region.AP_SOUTHEAST_2);
    }

    @Test
    void credentialsAreTheDefaultChainOrANamedProfile() {
        assertThat(SdkS3LogStore.credentials(ctx("us-east-1", Map.of(), null)))
                .isInstanceOf(DefaultCredentialsProvider.class);
        assertThat(SdkS3LogStore.credentials(ctx("us-east-1", Map.of("profile", "dlap-audit-reader"), null)))
                .isInstanceOf(ProfileCredentialsProvider.class);
    }

    private static final String ROLE = "arn:aws:iam::123456789012:role/dlap-log-reader";

    private static StsClient stubSts(AtomicReference<AssumeRoleRequest> seen) {
        StsClient sts = mock(StsClient.class);
        when(sts.assumeRole(any(AssumeRoleRequest.class))).thenAnswer(inv -> {
            seen.set(inv.getArgument(0));
            return AssumeRoleResponse.builder().credentials(Credentials.builder().accessKeyId("ASIATESTKEY")
                    .secretAccessKey("test-secret").sessionToken("test-token")
                    .expiration(Instant.parse("2099-01-01T00:00:00Z")).build()).build();
        });
        return sts;
    }

    @Test
    void aRoleArnCredentialsRefBuildsAnStsAssumedProviderWithoutCallingAws() {
        AtomicReference<AssumeRoleRequest> seen = new AtomicReference<>();
        AtomicReference<AwsCredentialsProvider> callerSeen = new AtomicReference<>();
        List<AutoCloseable> owned = new ArrayList<>();

        AwsCredentialsProvider provider = SdkS3LogStore.credentials(
                ctx("us-east-1", Map.of("external-id", "dlap-ext-1"), ROLE), k -> null,
                (c, caller) -> {
                    callerSeen.set(caller);
                    return stubSts(seen);
                }, owned);

        assertThat(provider).isInstanceOf(StsAssumeRoleCredentialsProvider.class);
        assertThat(callerSeen.get()).as("the caller is the default chain").isInstanceOf(DefaultCredentialsProvider.class);
        assertThat(seen).as("building the provider calls nothing").hasValue(null);
        assertThat(owned).hasSize(2);

        // The first signed request resolves the role, against the stub.
        AwsCredentials resolved = provider.resolveCredentials();
        assertThat(resolved.accessKeyId()).isEqualTo("ASIATESTKEY");
        assertThat(seen.get().roleArn()).isEqualTo(ROLE);
        assertThat(seen.get().externalId()).isEqualTo("dlap-ext-1");
        assertThat(seen.get().roleSessionName()).isEqualTo(SdkS3LogStore.ROLE_SESSION_NAME);
    }

    @Test
    void theExternalIdIsOptionalAndTheCallerIsTheNamedProfileWhenOneIsSet() {
        AtomicReference<AssumeRoleRequest> seen = new AtomicReference<>();
        AtomicReference<AwsCredentialsProvider> callerSeen = new AtomicReference<>();

        AwsCredentialsProvider provider = SdkS3LogStore.credentials(
                ctx("us-east-1", Map.of("profile", "dlap-audit-reader"), ROLE), k -> null,
                (c, caller) -> {
                    callerSeen.set(caller);
                    return stubSts(seen);
                }, new ArrayList<>());
        provider.resolveCredentials();

        assertThat(callerSeen.get()).isInstanceOf(ProfileCredentialsProvider.class);
        assertThat(seen.get().externalId()).isNull();
    }

    @Test
    void aCredentialsRefMayNameAnEnvironmentVariableThatHoldsTheRoleArn() {
        AtomicReference<AssumeRoleRequest> seen = new AtomicReference<>();
        for (String ref : List.of("DLAP_AUDIT_ROLE_ARN", "env:DLAP_AUDIT_ROLE_ARN")) {
            AwsCredentialsProvider provider = SdkS3LogStore.credentials(ctx("us-east-1", Map.of(), ref),
                    name -> "DLAP_AUDIT_ROLE_ARN".equals(name) ? ROLE : null, (c, caller) -> stubSts(seen),
                    new ArrayList<>());
            provider.resolveCredentials();
            assertThat(seen.get().roleArn()).isEqualTo(ROLE);
        }
    }

    @Test
    void aCredentialsRefThatIsNoRoleIsRefusedWithoutEchoingTheValue() {
        assertThatThrownBy(() -> SdkS3LogStore.credentials(ctx("us-east-1", Map.of(), "arn:aws:s3:::secret-bucket"),
                k -> null, (c, caller) -> null, new ArrayList<>()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IAM role ARN")
                .hasMessageNotContaining("secret-bucket");
        assertThatThrownBy(() -> SdkS3LogStore.credentials(ctx("us-east-1", Map.of(), "AKIAFAKEKEYVALUE12345:wJalr/xyz"),
                k -> null, (c, caller) -> null, new ArrayList<>()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("AKIAFAKEKEYVALUE12345")
                .hasMessageNotContaining("wJalr");
        assertThatThrownBy(() -> SdkS3LogStore.credentials(ctx("us-east-1", Map.of(), "UNSET_ROLE_VAR"),
                k -> null, (c, caller) -> null, new ArrayList<>()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("UNSET_ROLE_VAR");
        assertThatThrownBy(() -> SdkS3LogStore.credentials(ctx("us-east-1", Map.of(), "ROLE_VAR"),
                k -> "not-an-arn-but-a-secret", (c, caller) -> null, new ArrayList<>()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("not-an-arn-but-a-secret");
    }

    @Test
    void theClientKeyChangesWithTheRoleExternalIdAndStsEndpoint() {
        String base = SdkS3LogStore.clientKey(ctx("us-east-1", Map.of(), null));
        assertThat(SdkS3LogStore.clientKey(ctx("us-east-1", Map.of(), ROLE))).isNotEqualTo(base);
        assertThat(SdkS3LogStore.clientKey(ctx("us-east-1", Map.of("external-id", "x"), ROLE)))
                .isNotEqualTo(SdkS3LogStore.clientKey(ctx("us-east-1", Map.of(), ROLE)));
        assertThat(SdkS3LogStore.clientKey(ctx("us-east-1", Map.of("sts-endpoint", "http://127.0.0.1:1"), ROLE)))
                .isNotEqualTo(SdkS3LogStore.clientKey(ctx("us-east-1", Map.of(), ROLE)));
    }

    /**
     * EKS IRSA: the default chain's web-identity provider builds its STS client from the sts module,
     * which the SDK looks up by name at runtime. Without the module on the classpath the provider
     * fails on its first use; this test pins that the module ships with the service.
     */
    @Test
    void theStsModuleWebIdentityProvidersLoadThroughTheDefaultChain() throws Exception {
        assertThat(Class.forName("software.amazon.awssdk.services.sts.internal.StsWebIdentityCredentialsProviderFactory"))
                .isNotNull();
        assertThat(Class.forName("software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider"))
                .isNotNull();
        assertThat(SdkS3LogStore.credentials(ctx("us-east-1", Map.of(), null)))
                .isInstanceOf(DefaultCredentialsProvider.class);
    }

    @Test
    void aClientIsBuiltWithTheEndpointAndPathStyleAndCachedPerStreamSettings() {
        StreamContext local = ctx("us-east-1", Map.of("endpoint", "http://127.0.0.1:4566", "path-style", "TRUE"), null);
        try (S3Client c = SdkS3LogStore.client(local)) {
            assertThat(c.serviceClientConfiguration().endpointOverride()).hasValueSatisfying(u ->
                    assertThat(u.toString()).isEqualTo("http://127.0.0.1:4566"));
            assertThat(c.serviceClientConfiguration().region()).isEqualTo(Region.US_EAST_1);
        }

        AtomicInteger built = new AtomicInteger();
        SdkS3LogStore store = new SdkS3LogStore(c -> {
            built.incrementAndGet();
            return SdkS3LogStore.client(c);
        });
        assertThat(SdkS3LogStore.clientKey(local)).isEqualTo(SdkS3LogStore.clientKey(
                ctx("us-east-1", Map.of("endpoint", "http://127.0.0.1:4566", "path-style", "TRUE"), null)));
        assertThat(SdkS3LogStore.clientKey(local)).isNotEqualTo(SdkS3LogStore.clientKey(
                ctx("us-east-1", Map.of("endpoint", "http://127.0.0.1:4567"), null)));
        store.destroy();
        assertThat(built).hasValue(0);
    }

    @Test
    void etagsAreStoredWithoutQuotes() {
        assertThat(new S3LogObject("k", "\"abc\"", 1, null).etag()).isEqualTo("abc");
        assertThat(new S3LogObject("k", null, 1, null).etag()).isEmpty();
        assertThat(new S3LogObject("k", " abc ", 1, null).etag()).isEqualTo("abc");
    }
}
