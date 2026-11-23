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

package com.dlap.common.assetid;

import com.dlap.common.assetid.CanonicalAssetId.Attribute;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AWS rules. Canonical forms: {@code s3://{bucket}[/{key}]} for S3, the ARN for every other AWS
 * kind, {@code athena://{region}/{account}/{queryExecutionId}} for an Athena query.
 *
 * <p>S3 bucket names keep their case: buckets created in us-east-1 before 2018-03-01 may contain
 * upper-case letters, and every AWS-emitted form carries the exact name. RDS DB instance and
 * Redshift cluster identifiers are lower-cased, matching how AWS stores them. Glue database and
 * table names are lower-cased because Glue folds them to lower case when it stores them (Hive
 * compatibility), so an Athena {@code "Sales"."Orders"} names {@code table/sales/orders}. Glue job
 * names, DynamoDB table names and EMR IDs keep their case.
 */
final class AwsAssetIdScheme implements AssetIdScheme {

    static final AwsAssetIdScheme INSTANCE = new AwsAssetIdScheme();

    private static final Pattern ACCOUNT = Pattern.compile("\\d{12}");
    private static final Pattern REGION = Pattern.compile("[a-z]{2}(?:-[a-z]+)+-\\d{1,2}");
    private static final Pattern PARTITION = Pattern.compile("aws(?:-[a-z]+)*");
    private static final Pattern S3_BUCKET = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{1,253}[A-Za-z0-9]");
    private static final Pattern RDS_ID = Pattern.compile("[a-z][a-z0-9-]{0,62}");
    private static final Pattern REDSHIFT_ID = Pattern.compile("[a-z][a-z0-9-]{0,62}");
    private static final Pattern DYNAMODB_TABLE = Pattern.compile("[A-Za-z0-9_.-]{3,255}");
    private static final Pattern ATHENA_QUERY_ID = Pattern.compile("[A-Za-z0-9-]{1,128}");
    private static final Pattern EMR_ID = Pattern.compile("[A-Za-z0-9-]{1,256}");

    private static final String S3_ENDPOINT =
            "s3(?:[.-](?:dualstack\\.)?[a-z0-9-]+)?\\.amazonaws\\.com(?:\\.cn)?";
    private static final Pattern S3_PATH_STYLE_HOST = Pattern.compile(S3_ENDPOINT);
    private static final Pattern S3_VIRTUAL_HOST = Pattern.compile("(.+)\\." + S3_ENDPOINT);
    private static final Set<String> REGIONAL_ASSET_SERVICES =
            Set.of("rds", "redshift", "dynamodb", "glue", "elasticmapreduce");

    private AwsAssetIdScheme() {
    }

    @Override
    public Optional<AssetIdParseResult> parseSelfDescribing(String v, AssetIdHints hints) {
        for (String scheme : new String[] {"s3://", "s3a://", "s3n://"}) {
            String rest = IdText.afterPrefix(v, scheme);
            if (rest != null) {
                return s3Path(rest, Map.of());
            }
        }
        if (IdText.startsWithIgnoreCase(v, "arn:")) {
            return arn(v, hints);
        }
        String rest = IdText.afterPrefix(v, "rds://");
        if (rest != null) {
            return regionalUri(rest, "rds", hints);
        }
        rest = IdText.afterPrefix(v, "dynamodb://");
        if (rest != null) {
            return regionalUri(rest, "dynamodb", hints);
        }
        rest = IdText.afterPrefix(v, "athena://");
        if (rest != null) {
            return athenaUri(rest, hints);
        }
        rest = IdText.afterPrefix(v, "https://");
        if (rest == null) {
            rest = IdText.afterPrefix(v, "http://");
        }
        if (rest != null) {
            return s3Url(rest);
        }
        return Optional.empty();
    }

    @Override
    public Optional<AssetIdParseResult> parseBare(String v, AssetIdHints hints) {
        AssetKind kind = hints.kind();
        if (kind == null) {
            return Optional.empty();
        }
        return switch (kind) {
            case S3_BUCKET -> v.indexOf('/') >= 0
                    ? IdText.malformed("a bare S3 bucket name cannot contain '/'")
                    : s3Path(v, Map.of());
            case S3_OBJECT -> v.indexOf('/') <= 0
                    ? IdText.malformed("a bare S3 object must be bucket/key")
                    : s3Path(v, Map.of());
            case RDS_INSTANCE -> regional(hints, "rds", "db:" + IdText.lower(v));
            case DYNAMODB_TABLE -> regional(hints, "dynamodb", "table/" + v);
            case REDSHIFT_CLUSTER -> regional(hints, "redshift", "cluster:" + IdText.lower(v));
            case GLUE_DATABASE -> regional(hints, "glue", "database/" + v);
            case GLUE_TABLE -> glueTableName(v, hints);
            case REDSHIFT_QUERY -> {
                Optional<AssetIdParseResult> problem = regionAndAccountProblem(hints, "an Athena query execution ID");
                yield problem.isPresent() ? problem : athena(IdText.lower(hints.region()), hints.account(), v);
            }
            default -> Optional.empty();
        };
    }

    @Override
    public Optional<AssetIdParseResult> build(AssetKind kind, List<String> parts, AssetIdHints hints) {
        return switch (kind) {
            case S3_BUCKET -> parts.size() != 1 || parts.get(0).indexOf('/') >= 0
                    ? IdText.malformed("S3_BUCKET takes [bucket]")
                    : s3Path(parts.get(0), Map.of());
            case S3_OBJECT -> parts.size() != 2 || parts.get(0).indexOf('/') >= 0 || parts.get(1).isEmpty()
                    ? IdText.malformed("S3_OBJECT takes [bucket, key]")
                    : s3Path(parts.get(0) + "/" + parts.get(1), Map.of());
            case REDSHIFT_QUERY -> parts.size() != 3
                    ? IdText.malformed("REDSHIFT_QUERY takes [region, account, queryExecutionId]")
                    : athena(IdText.lower(parts.get(0)), parts.get(1), parts.get(2));
            case RDS_INSTANCE, REDSHIFT_CLUSTER, DYNAMODB_TABLE, GLUE_DATABASE, GLUE_TABLE, SPARK_JOB, PYTHON_JOB ->
                    parts.size() != 5
                            ? IdText.malformed(kind + " takes the five ARN fields [partition, service, region, account, resource]")
                            : arn("arn:" + String.join(":", parts), hints.withKind(kind));
            default -> Optional.empty();
        };
    }

    // ---- S3 --------------------------------------------------------------------------------

    private static Optional<AssetIdParseResult> s3Path(String rest, Map<String, String> attrs) {
        int slash = rest.indexOf('/');
        String bucket = slash < 0 ? rest : rest.substring(0, slash);
        String key = slash < 0 ? "" : rest.substring(slash + 1);
        if (!S3_BUCKET.matcher(bucket).matches()) {
            return IdText.malformed("invalid S3 bucket name");
        }
        return IdText.ok(key.isEmpty() ? s3Bucket(bucket, attrs) : s3Object(bucket, key, attrs));
    }

    private static CanonicalAssetId s3Bucket(String bucket, Map<String, String> attrs) {
        return new CanonicalAssetId(AssetProvider.AWS, AssetKind.S3_BUCKET, List.of(bucket),
                "s3://" + bucket, attrs, null);
    }

    private static CanonicalAssetId s3Object(String bucket, String key, Map<String, String> attrs) {
        return new CanonicalAssetId(AssetProvider.AWS, AssetKind.S3_OBJECT, List.of(bucket, key),
                "s3://" + bucket + "/" + key, attrs, s3Bucket(bucket, attrs));
    }

    private static Optional<AssetIdParseResult> s3Url(String afterScheme) {
        String[] hostAndPath = IdText.hostAndPath(afterScheme);
        String host = hostAndPath[0];
        if (!host.endsWith(".amazonaws.com") && !host.endsWith(".amazonaws.com.cn")) {
            return Optional.empty();
        }
        Optional<String> decoded = IdText.percentDecode(hostAndPath[1]);
        if (decoded.isEmpty()) {
            return IdText.malformed("invalid percent-encoding in an S3 URL");
        }
        String path = decoded.get();
        if (S3_PATH_STYLE_HOST.matcher(host).matches()) {
            if (path.length() <= 1) {
                return IdText.malformed("an S3 path-style URL needs a bucket");
            }
            return s3Path(path.substring(1), Map.of());
        }
        Matcher m = S3_VIRTUAL_HOST.matcher(host);
        if (m.matches()) {
            String key = path.startsWith("/") ? path.substring(1) : path;
            return s3Path(key.isEmpty() ? m.group(1) : m.group(1) + "/" + key, Map.of());
        }
        return IdText.unsupported("an AWS URL that is not an S3 endpoint is not a DLAP asset");
    }

    // ---- ARNs ------------------------------------------------------------------------------

    private static Optional<AssetIdParseResult> arn(String v, AssetIdHints hints) {
        String[] f = v.split(":", 6);
        if (f.length < 6) {
            return IdText.malformed("an ARN has six colon-separated fields");
        }
        String partition = IdText.lower(f[1]);
        String service = IdText.lower(f[2]);
        String region = IdText.lower(f[3]);
        String account = f[4];
        String resource = f[5];
        if (!PARTITION.matcher(partition).matches()) {
            return IdText.malformed("invalid ARN partition");
        }
        if ("s3".equals(service)) {
            if (!region.isEmpty() || !account.isEmpty()) {
                return IdText.unsupported("a regional S3 ARN (access point, Outposts) is not a bucket or object");
            }
            Map<String, String> attrs = new HashMap<>();
            attrs.put(Attribute.PARTITION, partition);
            return s3Path(resource, attrs);
        }
        if (!REGIONAL_ASSET_SERVICES.contains(service)) {
            return IdText.unsupported("the ARN service is not a DLAP asset kind");
        }
        if (!REGION.matcher(region).matches()) {
            return IdText.malformed("the ARN region is missing or invalid");
        }
        if (!ACCOUNT.matcher(account).matches()) {
            return IdText.malformed("the ARN account is not a 12-digit account ID");
        }
        return arnResource(partition, service, region, account, resource, hints);
    }

    private static Optional<AssetIdParseResult> arnResource(String partition, String service, String region,
                                                            String account, String resource, AssetIdHints hints) {
        switch (service) {
            case "rds" -> {
                String id = IdText.afterPrefix(resource, "db:");
                if (id == null) {
                    return IdText.unsupported("an RDS ARN that is not a DB instance is not a DLAP asset kind");
                }
                id = IdText.lower(id);
                if (!RDS_ID.matcher(id).matches()) {
                    return IdText.malformed("invalid RDS DB instance identifier");
                }
                return IdText.ok(arnId(AssetKind.RDS_INSTANCE, partition, service, region, account, "db:" + id, null));
            }
            case "redshift" -> {
                String id = IdText.afterPrefix(resource, "cluster:");
                if (id == null) {
                    return IdText.unsupported("a Redshift ARN that is not a cluster is not a DLAP asset kind");
                }
                id = IdText.lower(id);
                if (!REDSHIFT_ID.matcher(id).matches()) {
                    return IdText.malformed("invalid Redshift cluster identifier");
                }
                return IdText.ok(arnId(AssetKind.REDSHIFT_CLUSTER, partition, service, region, account,
                        "cluster:" + id, null));
            }
            case "dynamodb" -> {
                String name = IdText.afterPrefix(resource, "table/");
                if (name == null || name.indexOf('/') >= 0) {
                    return IdText.unsupported("a DynamoDB ARN that is not a table (index, stream) is not a DLAP asset kind");
                }
                if (!DYNAMODB_TABLE.matcher(name).matches()) {
                    return IdText.malformed("invalid DynamoDB table name");
                }
                return IdText.ok(arnId(AssetKind.DYNAMODB_TABLE, partition, service, region, account,
                        "table/" + name, null));
            }
            case "glue" -> {
                return glueResource(partition, region, account, resource, hints);
            }
            case "elasticmapreduce" -> {
                String rest = IdText.afterPrefix(resource, "cluster/");
                List<String> seg = rest == null ? List.of() : IdText.split(rest, '/');
                if (seg.size() != 3 || !"step".equals(seg.get(1))) {
                    return IdText.unsupported("an EMR ARN that is not cluster/{id}/step/{id} is not a DLAP asset kind");
                }
                if (!EMR_ID.matcher(seg.get(0)).matches() || !EMR_ID.matcher(seg.get(2)).matches()) {
                    return IdText.malformed("invalid EMR cluster or step ID");
                }
                return IdText.ok(arnId(AssetKind.SPARK_JOB, partition, service, region, account,
                        "cluster/" + seg.get(0) + "/step/" + seg.get(2), null));
            }
            default -> {
                return IdText.unsupported("the ARN service is not a DLAP asset kind");
            }
        }
    }

    private static Optional<AssetIdParseResult> glueResource(String partition, String region, String account,
                                                             String resource, AssetIdHints hints) {
        List<String> seg = IdText.split(resource, '/');
        if (IdText.anyEmpty(seg)) {
            return IdText.malformed("empty segment in a Glue ARN");
        }
        for (String s : seg) {
            if (IdText.hasControl(s)) {
                return IdText.malformed("control character in a Glue name");
            }
        }
        String type = seg.get(0);
        // Glue folds database and table names to lower case when it stores them (Hive compatibility).
        if ("database".equals(type) && seg.size() == 2) {
            return IdText.ok(glueDatabase(partition, region, account, IdText.lower(seg.get(1))));
        }
        if ("table".equals(type) && seg.size() == 3) {
            String db = IdText.lower(seg.get(1));
            CanonicalAssetId parent = glueDatabase(partition, region, account, db);
            return IdText.ok(arnId(AssetKind.GLUE_TABLE, partition, "glue", region, account,
                    "table/" + db + "/" + IdText.lower(seg.get(2)), parent));
        }
        if ("job".equals(type) && seg.size() == 2) {
            AssetKind kind = hints.kind() == AssetKind.PYTHON_JOB ? AssetKind.PYTHON_JOB : AssetKind.SPARK_JOB;
            return IdText.ok(arnId(kind, partition, "glue", region, account, "job/" + seg.get(1), null));
        }
        return IdText.unsupported("a Glue ARN that is not a database, table or job is not a DLAP asset kind");
    }

    private static CanonicalAssetId glueDatabase(String partition, String region, String account, String db) {
        return arnId(AssetKind.GLUE_DATABASE, partition, "glue", region, account, "database/" + db, null);
    }

    private static CanonicalAssetId arnId(AssetKind kind, String partition, String service, String region,
                                          String account, String resource, CanonicalAssetId parent) {
        Map<String, String> attrs = new HashMap<>();
        attrs.put(Attribute.PARTITION, partition);
        attrs.put(Attribute.REGION, region);
        attrs.put(Attribute.ACCOUNT, account);
        String uri = "arn:" + partition + ":" + service + ":" + region + ":" + account + ":" + resource;
        return new CanonicalAssetId(AssetProvider.AWS, kind, List.of(partition, service, region, account, resource),
                uri, attrs, parent);
    }

    // ---- DLAP short forms ------------------------------------------------------------------

    private static Optional<AssetIdParseResult> regionalUri(String rest, String service, AssetIdHints hints) {
        int slash = rest.indexOf('/');
        if (slash <= 0 || slash == rest.length() - 1) {
            return IdText.malformed(service + ":// takes {region}/{name}");
        }
        String region = IdText.lower(rest.substring(0, slash));
        String name = rest.substring(slash + 1);
        if (!REGION.matcher(region).matches()) {
            return IdText.malformed("invalid AWS region");
        }
        String account = hints.account();
        if (account == null) {
            return IdText.missingHint(service + ":// needs the account hint to form the ARN");
        }
        if (!ACCOUNT.matcher(account).matches()) {
            return IdText.malformed("the account hint is not a 12-digit account ID");
        }
        String resource = "rds".equals(service) ? "db:" + name : "table/" + name;
        return arnResource(IdText.awsPartitionForRegion(region), service, region, account, resource, hints);
    }

    private static Optional<AssetIdParseResult> regional(AssetIdHints hints, String service, String resource) {
        Optional<AssetIdParseResult> problem = regionAndAccountProblem(hints, "a bare " + service + " name");
        if (problem.isPresent()) {
            return problem;
        }
        String region = IdText.lower(hints.region());
        return arnResource(IdText.awsPartitionForRegion(region), service, region, hints.account(), resource, hints);
    }

    /** Empty when the region and account hints are present and valid, else the rejection. */
    private static Optional<AssetIdParseResult> regionAndAccountProblem(AssetIdHints hints, String what) {
        if (hints.region() == null || hints.account() == null) {
            return IdText.missingHint(what + " needs the region and account hints");
        }
        if (!REGION.matcher(IdText.lower(hints.region())).matches()) {
            return IdText.malformed("the region hint is not an AWS region");
        }
        if (!ACCOUNT.matcher(hints.account()).matches()) {
            return IdText.malformed("the account hint is not a 12-digit account ID");
        }
        return Optional.empty();
    }

    private static Optional<AssetIdParseResult> glueTableName(String v, AssetIdHints hints) {
        List<String> seg = IdText.split(IdText.stripBackticks(v).replace("\"", ""), '.');
        if (seg.size() == 3 && "awsdatacatalog".equalsIgnoreCase(seg.get(0))) {
            seg = seg.subList(1, 3);
        }
        if (seg.size() != 2 || IdText.anyEmpty(seg)) {
            return IdText.malformed("a bare Glue table is database.table or AwsDataCatalog.database.table");
        }
        return regional(hints, "glue", "table/" + seg.get(0) + "/" + seg.get(1));
    }

    private static Optional<AssetIdParseResult> athenaUri(String rest, AssetIdHints hints) {
        List<String> seg = IdText.split(rest, '/');
        if (seg.size() != 3 || IdText.anyEmpty(seg)) {
            return IdText.malformed("athena:// takes {region}/{account}/{queryExecutionId}");
        }
        String account;
        if ("query".equals(seg.get(1))) {
            account = hints.account();
            if (account == null) {
                return IdText.missingHint("athena://{region}/query/{id} needs the account hint");
            }
        } else {
            account = seg.get(1);
        }
        return athena(IdText.lower(seg.get(0)), account, seg.get(2));
    }

    private static Optional<AssetIdParseResult> athena(String region, String account, String queryId) {
        if (!REGION.matcher(region).matches()) {
            return IdText.malformed("invalid AWS region");
        }
        if (!ACCOUNT.matcher(account).matches()) {
            return IdText.malformed("the account is not a 12-digit account ID");
        }
        if (!ATHENA_QUERY_ID.matcher(queryId).matches()) {
            return IdText.malformed("invalid Athena query execution ID");
        }
        Map<String, String> attrs = new HashMap<>();
        attrs.put(Attribute.REGION, region);
        attrs.put(Attribute.ACCOUNT, account);
        return IdText.ok(new CanonicalAssetId(AssetProvider.AWS, AssetKind.REDSHIFT_QUERY,
                List.of(region, account, queryId), "athena://" + region + "/" + account + "/" + queryId, attrs, null));
    }
}
