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

package com.dlap.catalog.lifecycle;

import java.util.Locale;
import java.util.regex.Pattern;

import com.dlap.proto.log.v1.DlapLogEvent;
import com.dlap.proto.log.v1.PrincipalType;
import com.dlap.proto.log.v1.SourceProvider;

/**
 * The lifecycle signature of a provider's own lifecycle action (D36, Q-654). A disposition is
 * {@code executedBy: native-rule} only when its event comes from a {@code SYSTEM} principal and also
 * carries the signature of a provider lifecycle rule, the same signature da-autodelete's
 * {@code DeletionProofMatcher} requires before it accepts a native rule as executed (and, for an
 * archive, the transition operations da-autoarchival's {@code ArchiveProofService} reads). Any other
 * provider service acting as {@code SYSTEM} (S3 replication, Batch Operations, a backup service) is
 * not a lifecycle rule, so its disposition is {@code engine} when its principal is configured as one,
 * else it has no {@code executedBy} value (ADR-0099 point 4, spec section 3.7).
 *
 * <table>
 *   <caption>Lifecycle signatures by provider</caption>
 *   <tr><th>Provider</th><th>Signature</th></tr>
 *   <tr><td>AWS (S3)</td><td>{@code source_service} {@code s3lifecycle.amazonaws.com}; or the
 *       principal or caller {@code lifecycle.s3.amazonaws.com}; or a server access log lifecycle
 *       operation {@code S3.EXPIRE.OBJECT}, {@code S3.CREATE.DELETEMARKER} or
 *       {@code S3.TRANSITION*.OBJECT}</td></tr>
 *   <tr><td>GCP (BigQuery)</td><td>operation {@code InternalTableExpired}, or {@code source_service}
 *       {@code bigquery.googleapis.com}</td></tr>
 *   <tr><td>GCP (Cloud Storage)</td><td>user agent containing {@code GCS Lifecycle Management}
 *       [unverified, Q-610]</td></tr>
 *   <tr><td>AZURE (Blob)</td><td>any {@code SYSTEM} principal, or a user agent containing
 *       {@code ObjectLifecycleManagement} [unverified, Q-611]</td></tr>
 * </table>
 *
 * The signatures are copied from those two classes because the services share no code for them;
 * {@code LifecycleSignatureTest} pins each row, and the shared fixture tests
 * ({@code ProviderLifecycleExecutedByTest} here, {@code ProviderLifecycleProofContractTest} in
 * da-autodelete) hold both sides to the same parsed events.
 */
public final class LifecycleSignature {

    /** CloudTrail event source of the actions S3 lifecycle rules perform. */
    public static final String S3_LIFECYCLE_SERVICE = "s3lifecycle.amazonaws.com";

    /** The service principal and source address of S3 lifecycle actions. */
    public static final String S3_LIFECYCLE_PRINCIPAL = "lifecycle.s3.amazonaws.com";

    /** BigQuery's service name. */
    public static final String BIGQUERY_SERVICE = "bigquery.googleapis.com";

    /** The S3 server access log lifecycle operations: expiration, delete marker, transitions. */
    private static final Pattern S3_LIFECYCLE_OPERATION = Pattern.compile(
            "^S3\\.(EXPIRE\\.OBJECT|CREATE\\.DELETEMARKER|TRANSITION[A-Z_]*\\.OBJECT)$", Pattern.CASE_INSENSITIVE);

    private LifecycleSignature() {
    }

    /**
     * Whether an event is a provider lifecycle rule's own action: a {@code SYSTEM} principal and the
     * provider's lifecycle signature.
     *
     * @param event the event; may be {@code null}
     * @return true when the provider acted on its own rule
     */
    public static boolean isNativeRule(DlapLogEvent event) {
        if (event == null || !event.hasPrincipal() || event.getPrincipal().getType() != PrincipalType.SYSTEM) {
            return false;
        }
        return hasSignature(event);
    }

    /**
     * Whether an event carries its provider's lifecycle signature (see the class table).
     *
     * @param event the event
     * @return true when it does
     */
    static boolean hasSignature(DlapLogEvent event) {
        String service = lower(event.getSourceService());
        String operation = event.getOperation();
        String agent = event.hasRequestMetadata() ? lower(event.getRequestMetadata().getUserAgent()) : "";
        String caller = event.hasRequestMetadata() ? lower(event.getRequestMetadata().getCallerIp()) : "";
        String principalId = event.hasPrincipal() ? lower(event.getPrincipal().getId()) : "";
        SourceProvider provider = event.getSourceProvider();
        return switch (provider) {
            case AWS -> S3_LIFECYCLE_SERVICE.equals(service)
                    || principalId.contains(S3_LIFECYCLE_PRINCIPAL)
                    || caller.contains(S3_LIFECYCLE_PRINCIPAL)
                    || (operation != null && S3_LIFECYCLE_OPERATION.matcher(operation).matches());
            case GCP -> "InternalTableExpired".equalsIgnoreCase(operation)
                    || BIGQUERY_SERVICE.equals(service)
                    || agent.contains("gcs lifecycle management");
            case AZURE -> true;
            default -> false;
        };
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
