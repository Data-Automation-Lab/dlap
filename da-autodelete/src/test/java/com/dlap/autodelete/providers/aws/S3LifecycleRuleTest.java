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

package com.dlap.autodelete.providers.aws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dlap.autodelete.nativerule.ContainerRuleAge;
import com.dlap.autodelete.nativerule.NativeRule;
import com.dlap.autodelete.nativerule.NativeRuleException;
import com.dlap.autodelete.nativerule.RuleDocuments;
import com.dlap.autodelete.retire.RetireProperties;
import com.dlap.common.assetid.AssetIdNormalizer;
import com.dlap.common.assetid.CanonicalAssetId;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.SdkField;
import software.amazon.awssdk.core.SdkPojo;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortIncompleteMultipartUpload;
import software.amazon.awssdk.services.s3.model.ExpirationStatus;
import software.amazon.awssdk.services.s3.model.GetBucketLifecycleConfigurationRequest;
import software.amazon.awssdk.services.s3.model.GetBucketLifecycleConfigurationResponse;
import software.amazon.awssdk.services.s3.model.LifecycleExpiration;
import software.amazon.awssdk.services.s3.model.LifecycleRule;
import software.amazon.awssdk.services.s3.model.LifecycleRuleFilter;
import software.amazon.awssdk.services.s3.model.NoncurrentVersionExpiration;
import software.amazon.awssdk.services.s3.model.PutBucketLifecycleConfigurationRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * S3 lifecycle rule generation and application, checked against the S3 API shape as the AWS SDK's
 * own service model describes it (each SDK model class lists its members' wire names).
 */
class S3LifecycleRuleTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:15:00Z");
    private static final UUID KEY = UUID.fromString("abcdabcd-0000-4000-8000-0000000000c1");

    private static CanonicalAssetId bucket() {
        return AssetIdNormalizer.normalize("s3://dlap-aws-e2e").orElseThrow();
    }

    private static NativeRule rule() {
        return new S3LifecycleRuleGenerator().generate(bucket(), KEY, NOW);
    }

    private static Set<String> wireNames(SdkPojo pojo) {
        return pojo.sdkFields().stream().map(SdkField::locationName).collect(Collectors.toSet());
    }

    /** Every member of the generated rule is a member of the SDK model at that level, with the same spelling. */
    @Test
    void theRuleUsesOnlyMemberNamesOfTheS3ApiModel() {
        JsonNode r = RuleDocuments.read(rule().document()).path("rules").get(0);
        assertThat(names(r)).isSubsetOf(wireNames(LifecycleRule.builder().build()));
        assertThat(names(r.path("Filter"))).isSubsetOf(wireNames(LifecycleRuleFilter.builder().build()));
        assertThat(names(r.path("Expiration"))).isSubsetOf(wireNames(LifecycleExpiration.builder().build()));
        assertThat(names(r.path("NoncurrentVersionExpiration")))
                .isSubsetOf(wireNames(NoncurrentVersionExpiration.builder().build()));
        assertThat(names(r.path("AbortIncompleteMultipartUpload")))
                .isSubsetOf(wireNames(AbortIncompleteMultipartUpload.builder().build()));
    }

    /**
     * Q-677 (RET-06): the bucket rule is age-based, Expiration.Days of at least 1, never an Expiration.Date, so an
     * object written after the delete is not removed the same day. The first objects can go at the next UTC midnight.
     */
    @Test
    void theBucketRuleIsAgeBasedWithExpirationDaysAndTakesEffectAtTheNextUtcMidnight() {
        NativeRule rule = rule();
        JsonNode doc = RuleDocuments.read(rule.document());
        assertThat(doc.path("api").asText()).isEqualTo("s3:PutBucketLifecycleConfiguration");
        assertThat(doc.path("bucket").asText()).isEqualTo("dlap-aws-e2e");
        assertThat(rule.ruleId()).isEqualTo("dlap-retire-" + KEY);
        assertThat(rule.effectiveAt()).isEqualTo(Instant.parse("2026-11-02T00:00:00Z"));

        LifecycleRule sdk = S3LifecycleRuleApplier.toSdk(doc.path("rules").get(0));
        assertThat(sdk).isEqualTo(LifecycleRule.builder()
                .id("dlap-retire-" + KEY)
                .filter(LifecycleRuleFilter.builder().prefix("").build())
                .status(ExpirationStatus.ENABLED)
                .expiration(LifecycleExpiration.builder().days(1).build())
                .noncurrentVersionExpiration(NoncurrentVersionExpiration.builder().noncurrentDays(1).build())
                .abortIncompleteMultipartUpload(AbortIncompleteMultipartUpload.builder().daysAfterInitiation(1).build())
                .build());
    }

    /** Q-677: the age follows dlap.autodelete.native-rule.container-expiration-days and passes the age check. */
    @Test
    void theAgeFollowsTheSettingAndPassesTheContainerRuleAgeCheck() {
        RetireProperties three = new RetireProperties(true, 30, 7, 2, 100, null, null,
                new RetireProperties.NativeRule(java.time.Duration.ofHours(1), List.of(), 3), null);
        NativeRule rule = new S3LifecycleRuleGenerator(three).generate(bucket(), KEY, NOW);

        JsonNode exp = RuleDocuments.read(rule.document()).path("rules").get(0).path("Expiration");
        assertThat(exp.path("Days").asInt()).isEqualTo(3);
        assertThat(exp.has("Date")).isFalse();
        assertThat(ContainerRuleAge.violation(rule)).isEmpty();
        assertThat(ContainerRuleAge.violation(rule())).isEmpty();
    }

    /** A rule applied before RET-06 (Expiration.Date) can still be rebuilt, so a restore can remove it. */
    @Test
    void aDateBasedRuleAppliedBeforeRet06CanStillBeRebuiltForItsRemoval() {
        JsonNode old = RuleDocuments.read("{\"ID\":\"dlap-retire-" + KEY + "\",\"Filter\":{\"Prefix\":\"\"},"
                + "\"Status\":\"Enabled\",\"Expiration\":{\"Date\":\"2026-11-02T00:00:00Z\"},"
                + "\"NoncurrentVersionExpiration\":{\"NoncurrentDays\":1},"
                + "\"AbortIncompleteMultipartUpload\":{\"DaysAfterInitiation\":1}}");

        assertThat(S3LifecycleRuleApplier.toSdk(old).expiration().date())
                .isEqualTo(Instant.parse("2026-11-02T00:00:00Z"));
    }

    @Test
    void aSingleObjectHasNoExactLifecycleRule() {
        CanonicalAssetId object = AssetIdNormalizer.normalize("s3://dlap-aws-e2e/k/a.parquet").orElseThrow();
        assertThat(new S3LifecycleRuleGenerator().supports(object)).isFalse();
        assertThatThrownBy(() -> new S3LifecycleRuleGenerator().generate(object, KEY, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theApplierKeepsTheOwnersRulesAndReplacesOnlyItsOwn() {
        S3Client s3 = mock(S3Client.class);
        LifecycleRule owners = LifecycleRule.builder().id("owner-archive").status(ExpirationStatus.ENABLED)
                .filter(LifecycleRuleFilter.builder().prefix("logs/").build()).build();
        LifecycleRule oldOurs = LifecycleRule.builder().id("dlap-retire-" + KEY).status(ExpirationStatus.DISABLED)
                .filter(LifecycleRuleFilter.builder().prefix("").build()).build();
        when(s3.getBucketLifecycleConfiguration(any(GetBucketLifecycleConfigurationRequest.class)))
                .thenReturn(GetBucketLifecycleConfigurationResponse.builder().rules(owners, oldOurs).build());

        new S3LifecycleRuleApplier(b -> s3).apply(rule());

        ArgumentCaptor<PutBucketLifecycleConfigurationRequest> put =
                ArgumentCaptor.forClass(PutBucketLifecycleConfigurationRequest.class);
        verify(s3).putBucketLifecycleConfiguration(put.capture());
        assertThat(put.getValue().bucket()).isEqualTo("dlap-aws-e2e");
        List<LifecycleRule> written = put.getValue().lifecycleConfiguration().rules();
        assertThat(written).hasSize(2);
        assertThat(written.get(0)).isEqualTo(owners);
        assertThat(written.get(1).status()).isEqualTo(ExpirationStatus.ENABLED);
    }

    /** Q-631: a restore of a rule in force takes out DLAP's rule only and keeps the owner's rules. */
    @Test
    void removeTakesOutOnlyDlapsRuleAndKeepsTheOwnersRules() {
        S3Client s3 = mock(S3Client.class);
        LifecycleRule owners = LifecycleRule.builder().id("owner-archive").status(ExpirationStatus.ENABLED)
                .filter(LifecycleRuleFilter.builder().prefix("logs/").build()).build();
        LifecycleRule ours = LifecycleRule.builder().id("dlap-retire-" + KEY).status(ExpirationStatus.ENABLED)
                .filter(LifecycleRuleFilter.builder().prefix("").build()).build();
        when(s3.getBucketLifecycleConfiguration(any(GetBucketLifecycleConfigurationRequest.class)))
                .thenReturn(GetBucketLifecycleConfigurationResponse.builder().rules(owners, ours).build());

        assertThat(new S3LifecycleRuleApplier(b -> s3).remove(rule())).isTrue();

        ArgumentCaptor<PutBucketLifecycleConfigurationRequest> put =
                ArgumentCaptor.forClass(PutBucketLifecycleConfigurationRequest.class);
        verify(s3).putBucketLifecycleConfiguration(put.capture());
        assertThat(put.getValue().lifecycleConfiguration().rules()).containsExactly(owners);
        verify(s3, never()).deleteBucketLifecycle(any(software.amazon.awssdk.services.s3.model.DeleteBucketLifecycleRequest.class));
    }

    /** S3 refuses an empty rule list, so a bucket whose only rule was ours loses its lifecycle configuration. */
    @Test
    void removeOfTheOnlyRuleDeletesTheLifecycleConfiguration() {
        S3Client s3 = mock(S3Client.class);
        LifecycleRule ours = LifecycleRule.builder().id("dlap-retire-" + KEY).status(ExpirationStatus.ENABLED).build();
        when(s3.getBucketLifecycleConfiguration(any(GetBucketLifecycleConfigurationRequest.class)))
                .thenReturn(GetBucketLifecycleConfigurationResponse.builder().rules(ours).build());

        new S3LifecycleRuleApplier(b -> s3).remove(rule());

        ArgumentCaptor<software.amazon.awssdk.services.s3.model.DeleteBucketLifecycleRequest> del =
                ArgumentCaptor.forClass(software.amazon.awssdk.services.s3.model.DeleteBucketLifecycleRequest.class);
        verify(s3).deleteBucketLifecycle(del.capture());
        assertThat(del.getValue().bucket()).isEqualTo("dlap-aws-e2e");
        verify(s3, never()).putBucketLifecycleConfiguration(any(PutBucketLifecycleConfigurationRequest.class));
    }

    /** Q-682 (RET-06): a rule that is no longer there is reported as not found, and nothing is written. */
    @Test
    void removeOfARuleThatIsAlreadyGoneChangesNothingAndSaysItWasNotFound() {
        S3Client s3 = mock(S3Client.class);
        LifecycleRule owners = LifecycleRule.builder().id("owner-archive").status(ExpirationStatus.ENABLED).build();
        when(s3.getBucketLifecycleConfiguration(any(GetBucketLifecycleConfigurationRequest.class)))
                .thenReturn(GetBucketLifecycleConfigurationResponse.builder().rules(owners).build());
        assertThat(new S3LifecycleRuleApplier(b -> s3).remove(rule())).isFalse();

        S3Client none = mock(S3Client.class);
        when(none.getBucketLifecycleConfiguration(any(GetBucketLifecycleConfigurationRequest.class)))
                .thenThrow((S3Exception) S3Exception.builder().statusCode(404)
                        .awsErrorDetails(AwsErrorDetails.builder().errorCode("NoSuchLifecycleConfiguration").build())
                        .build());
        assertThat(new S3LifecycleRuleApplier(b -> none).remove(rule())).isFalse();

        verify(s3, never()).putBucketLifecycleConfiguration(any(PutBucketLifecycleConfigurationRequest.class));
        verify(none, never()).putBucketLifecycleConfiguration(any(PutBucketLifecycleConfigurationRequest.class));
    }

    @Test
    void anS3RefusalOfTheRemovalBecomesANativeRuleException() {
        S3Client s3 = mock(S3Client.class);
        when(s3.getBucketLifecycleConfiguration(any(GetBucketLifecycleConfigurationRequest.class)))
                .thenThrow((S3Exception) S3Exception.builder().statusCode(403)
                        .awsErrorDetails(AwsErrorDetails.builder().errorCode("AccessDenied").build()).build());

        assertThatThrownBy(() -> new S3LifecycleRuleApplier(b -> s3).remove(rule()))
                .isInstanceOf(NativeRuleException.class).hasMessageContaining("AccessDenied");
    }

    @Test
    void aBucketWithoutLifecycleGetsJustTheRule() {
        S3Client s3 = mock(S3Client.class);
        when(s3.getBucketLifecycleConfiguration(any(GetBucketLifecycleConfigurationRequest.class)))
                .thenThrow((S3Exception) S3Exception.builder().statusCode(404)
                        .awsErrorDetails(AwsErrorDetails.builder().errorCode("NoSuchLifecycleConfiguration").build())
                        .build());

        new S3LifecycleRuleApplier(b -> s3).apply(rule());

        ArgumentCaptor<PutBucketLifecycleConfigurationRequest> put =
                ArgumentCaptor.forClass(PutBucketLifecycleConfigurationRequest.class);
        verify(s3).putBucketLifecycleConfiguration(put.capture());
        assertThat(put.getValue().lifecycleConfiguration().rules()).hasSize(1);
    }

    @Test
    void anS3RefusalBecomesANativeRuleExceptionAndNothingIsWritten() {
        S3Client s3 = mock(S3Client.class);
        when(s3.getBucketLifecycleConfiguration(any(GetBucketLifecycleConfigurationRequest.class)))
                .thenThrow((S3Exception) S3Exception.builder().statusCode(403)
                        .awsErrorDetails(AwsErrorDetails.builder().errorCode("AccessDenied").build()).build());

        assertThatThrownBy(() -> new S3LifecycleRuleApplier(b -> s3).apply(rule()))
                .isInstanceOf(NativeRuleException.class).hasMessageContaining("AccessDenied");
        verify(s3, never()).putBucketLifecycleConfiguration(any(PutBucketLifecycleConfigurationRequest.class));
    }

    @Test
    void aBucketAtTheRuleLimitIsNotOverwritten() {
        S3Client s3 = mock(S3Client.class);
        List<LifecycleRule> full = new ArrayList<>();
        for (int i = 0; i < S3LifecycleRuleApplier.MAX_RULES; i++) {
            full.add(LifecycleRule.builder().id("r" + i).build());
        }
        when(s3.getBucketLifecycleConfiguration(any(GetBucketLifecycleConfigurationRequest.class)))
                .thenReturn(GetBucketLifecycleConfigurationResponse.builder().rules(full).build());

        assertThatThrownBy(() -> new S3LifecycleRuleApplier(b -> s3).apply(rule()))
                .isInstanceOf(NativeRuleException.class).hasMessageContaining("1001");
        verify(s3, never()).putBucketLifecycleConfiguration(any(PutBucketLifecycleConfigurationRequest.class));
    }

    private static List<String> names(JsonNode node) {
        List<String> out = new ArrayList<>();
        for (Iterator<String> it = node.fieldNames(); it.hasNext();) {
            out.add(it.next());
        }
        return out;
    }
}
