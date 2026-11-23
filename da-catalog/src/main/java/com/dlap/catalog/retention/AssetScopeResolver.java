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

package com.dlap.catalog.retention;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.dlap.catalog.retention.RetentionAssetStore.AssetRow;
import com.dlap.catalog.retention.RetentionAssetStore.TechnicalKeys;

/**
 * Finds the scope id (GCP project, AWS account, Azure subscription, Databricks workspace,
 * Snowflake account) an asset belongs to, so the evaluator can find the log binding that covers
 * it. The id comes from the asset's canonical URI when the URI carries it, and otherwise from
 * the identifiers crawlers record in {@code technical_metadata}. When neither names the scope the
 * result is empty and the asset has no read coverage: the safe answer, because an asset that
 * cannot be placed in a binding cannot be shown to be unread.
 */
final class AssetScopeResolver {

    private static final Pattern ARN_ACCOUNT = Pattern.compile("^arn:[^:]*:[^:]*:[^:]*:(\\d{12}):.*");
    private static final Pattern AZURE_SUBSCRIPTION =
            Pattern.compile("(?i)/subscriptions/([^/]+)");
    private static final Pattern GCP_PROJECT_PATH = Pattern.compile("/projects/([^/]+)/.*");

    private AssetScopeResolver() {
    }

    /**
     * The scope id of an asset.
     *
     * @param row the asset
     * @return the lower-cased id, or empty when the asset cannot be placed in a scope
     */
    static Optional<String> scopeId(AssetRow row) {
        if (row.cloudProvider() == null) {
            return Optional.empty();
        }
        TechnicalKeys keys = row.metadata();
        String uri = row.assetUri() == null ? "" : row.assetUri();
        String id = switch (row.cloudProvider().toUpperCase(Locale.ROOT)) {
            case "GCP" -> firstNonBlank(gcpProjectFromUri(uri), keys.projectId());
            case "AWS" -> firstNonBlank(awsAccountFromUri(uri), keys.accountId());
            case "AZURE" -> firstNonBlank(azureSubscriptionFromUri(uri), keys.subscriptionId());
            case "DATABRICKS" -> keys.workspaceId();
            case "SNOWFLAKE" -> keys.accountId();
            default -> null;
        };
        return id == null || id.isBlank() ? Optional.empty() : Optional.of(id.trim().toLowerCase(Locale.ROOT));
    }

    private static String gcpProjectFromUri(String uri) {
        for (String scheme : new String[] {"bq://", "bqjob://"}) {
            if (uri.startsWith(scheme)) {
                String rest = uri.substring(scheme.length());
                int slash = rest.indexOf('/');
                return slash < 0 ? rest : rest.substring(0, slash);
            }
        }
        if (uri.startsWith("//cloudsql.googleapis.com")) {
            Matcher m = GCP_PROJECT_PATH.matcher(uri.substring("//cloudsql.googleapis.com".length()));
            return m.matches() ? m.group(1) : null;
        }
        return null;
    }

    private static String awsAccountFromUri(String uri) {
        Matcher m = ARN_ACCOUNT.matcher(uri);
        return m.matches() ? m.group(1) : null;
    }

    private static String azureSubscriptionFromUri(String uri) {
        Matcher m = AZURE_SUBSCRIPTION.matcher(uri);
        return m.find() ? m.group(1) : null;
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }
}
