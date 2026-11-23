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

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Random;
import java.util.function.Function;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Seeded alias generator (decision D7: seeded JUnit generators, no jqwik). For each family it draws
 * random resources, renders every alias form, and checks that all aliases converge, that the
 * canonical URI is a fixed point, that building from parts reproduces it, and that the parent is
 * canonical. A failure message names the family, seed and alias so the case can be replayed.
 */
class AssetIdSeededAliasTest {

    private static final int ITERATIONS = 300;
    private static final long BASE_SEED = 20261018L;

    /** One generated resource: its expected kind and the aliases (with hints) that name it. */
    record Sample(AssetKind kind, List<String> raws, List<AssetIdHints> hints) {
        static Sample of(AssetKind kind) {
            return new Sample(kind, new ArrayList<>(), new ArrayList<>());
        }

        Sample add(String raw) {
            return add(raw, AssetIdHints.none());
        }

        Sample add(String raw, AssetIdHints h) {
            raws.add(raw);
            hints.add(h);
            return this;
        }
    }

    enum Family {
        S3_BUCKET(AssetIdSeededAliasTest::s3Bucket),
        S3_OBJECT(AssetIdSeededAliasTest::s3Object),
        GCS_BUCKET(AssetIdSeededAliasTest::gcsBucket),
        GCS_OBJECT(AssetIdSeededAliasTest::gcsObject),
        BIGQUERY_DATASET(AssetIdSeededAliasTest::bqDataset),
        BIGQUERY_TABLE(AssetIdSeededAliasTest::bqTable),
        BIGQUERY_JOB(AssetIdSeededAliasTest::bqJob),
        AZURE_ACCOUNT(AssetIdSeededAliasTest::azureAccount),
        AZURE_CONTAINER(AssetIdSeededAliasTest::azureContainer),
        AZURE_BLOB(AssetIdSeededAliasTest::azureBlob),
        UNITY_TABLE(AssetIdSeededAliasTest::unityTable),
        UNITY_VOLUME(AssetIdSeededAliasTest::unityVolume),
        DATABRICKS_RUN(AssetIdSeededAliasTest::databricksRun),
        SNOWFLAKE_TABLE(AssetIdSeededAliasTest::snowflakeTable),
        RDS_INSTANCE(AssetIdSeededAliasTest::rdsInstance),
        REDSHIFT_CLUSTER(AssetIdSeededAliasTest::redshiftCluster),
        GLUE_TABLE(AssetIdSeededAliasTest::glueTable);

        final Function<Random, Sample> generator;

        Family(Function<Random, Sample> generator) {
            this.generator = generator;
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Family.class)
    void generatedAliasesConverge(Family family) {
        for (int i = 0; i < ITERATIONS; i++) {
            long seed = BASE_SEED + 7919L * i + family.ordinal();
            Sample sample = family.generator.apply(new Random(seed));
            CanonicalAssetId first = null;
            for (int a = 0; a < sample.raws().size(); a++) {
                String raw = sample.raws().get(a);
                AssetIdParseResult r = AssetIdNormalizer.parse(raw, sample.hints().get(a));
                String where = family + " seed=" + seed + " alias=" + raw;
                assertThat(r.isNormalized()).as("%s rejected: %s", where, r).isTrue();
                CanonicalAssetId id = r.id().orElseThrow();
                assertThat(id.kind()).as(where).isEqualTo(sample.kind());
                if (first == null) {
                    first = id;
                    checkFixedPointAndBuild(id, where);
                } else {
                    assertThat(id.uri()).as(where).isEqualTo(first.uri());
                }
            }
        }
    }

    private static void checkFixedPointAndBuild(CanonicalAssetId id, String where) {
        Optional<CanonicalAssetId> again = AssetIdNormalizer.normalize(id.uri());
        assertThat(again).as("fixed point %s", where).contains(id);
        assertThat(again.orElseThrow().kind()).as(where).isEqualTo(id.kind());
        assertThat(AssetIdNormalizer.build(id.provider(), id.kind(), id.parts(), null).id()).as("build %s", where).contains(id);
        id.parent().ifPresent(p -> assertThat(AssetIdNormalizer.normalize(p.uri())).as("parent %s", where).contains(p));
        assertThat(id.rollUp().kind().isObjectLevel()).as(where).isFalse();
    }

    // ---- random building blocks -------------------------------------------------------------

    private static final String LOWER = "abcdefghijklmnopqrstuvwxyz";
    private static final String DIGITS = "0123456789";
    private static final String KEY_CHARS = LOWER + LOWER.toUpperCase(Locale.ROOT) + DIGITS
            + "/ -_.%+#?=&()!~*'\u00e9\u00fc\u65e5\u672c";

    private static String pick(Random r, String chars, int min, int max) {
        int n = min + r.nextInt(max - min + 1);
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(chars.charAt(r.nextInt(chars.length())));
        }
        return sb.toString();
    }

    /** Lower-case alphanumeric ends, inner chars from {@code inner}, no doubled separators. */
    private static String dnsName(Random r, String inner, int min, int max) {
        String alnum = LOWER + DIGITS;
        int n = min + r.nextInt(max - min + 1);
        StringBuilder sb = new StringBuilder(n);
        sb.append(alnum.charAt(r.nextInt(alnum.length())));
        for (int i = 1; i < n - 1; i++) {
            char prev = sb.charAt(sb.length() - 1);
            String pool = alnum.indexOf(prev) >= 0 ? alnum + inner : alnum;
            sb.append(pool.charAt(r.nextInt(pool.length())));
        }
        sb.append(alnum.charAt(r.nextInt(alnum.length())));
        return sb.toString();
    }

    private static String key(Random r) {
        String k = pick(r, KEY_CHARS, 1, 40);
        if (Character.isWhitespace(k.charAt(0))) {
            k = "k" + k;
        }
        return k;
    }

    /** Percent-encodes everything except unreserved characters and, when {@code keepSlash}, '/'. */
    private static String encode(String s, boolean keepSlash) {
        StringBuilder sb = new StringBuilder();
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            boolean unreserved = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~' || (keepSlash && c == '/');
            if (unreserved) {
                sb.append((char) c);
            } else {
                sb.append('%').append(String.format("%02X", c));
            }
        }
        return sb.toString();
    }

    private static String randomCase(Random r, String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            sb.append(r.nextBoolean() ? Character.toUpperCase(c) : c);
        }
        return sb.toString();
    }

    private static String project(Random r) {
        String p = LOWER.charAt(r.nextInt(26)) + dnsName(r, "-", 5, 20);
        return r.nextInt(5) == 0 ? "example.com:" + p : p;
    }

    private static String account12(Random r) {
        return pick(r, DIGITS, 12, 12);
    }

    private static String region(Random r) {
        String[] regions = {"us-east-1", "us-west-2", "eu-west-1", "ap-southeast-2", "cn-north-1", "us-gov-west-1"};
        return regions[r.nextInt(regions.length)];
    }

    // ---- families ----------------------------------------------------------------------------

    private static Sample s3Bucket(Random r) {
        String b = dnsName(r, "-.", 3, 40);
        String region = region(r);
        return Sample.of(AssetKind.S3_BUCKET)
                .add("s3://" + b)
                .add("S3://" + b + "/")
                .add("s3a://" + b)
                .add("arn:aws:s3:::" + b)
                .add(b, AssetIdHints.of(AssetKind.S3_BUCKET))
                .add("https://" + b + ".s3." + region + ".amazonaws.com/")
                .add("https://s3." + region + ".amazonaws.com/" + b);
    }

    private static Sample s3Object(Random r) {
        String b = dnsName(r, "-.", 3, 40);
        String k = key(r);
        String region = region(r);
        return Sample.of(AssetKind.S3_OBJECT)
                .add("s3://" + b + "/" + k)
                .add("arn:aws:s3:::" + b + "/" + k)
                .add("s3n://" + b + "/" + k)
                .add(b + "/" + k, AssetIdHints.of(AssetKind.S3_OBJECT))
                .add("https://" + b + ".s3." + region + ".amazonaws.com/" + encode(k, true))
                .add("https://s3." + region + ".amazonaws.com/" + b + "/" + encode(k, r.nextBoolean()));
    }

    private static Sample gcsBucket(Random r) {
        String b = dnsName(r, "-_.", 3, 40);
        return Sample.of(AssetKind.GCS_BUCKET)
                .add("gs://" + b)
                .add("gs://" + b.toUpperCase(Locale.ROOT))
                .add("projects/_/buckets/" + b)
                .add("//storage.googleapis.com/" + b)
                .add(b, AssetIdHints.of(AssetKind.GCS_BUCKET))
                .add("https://storage.googleapis.com/storage/v1/b/" + b);
    }

    private static Sample gcsObject(Random r) {
        String b = dnsName(r, "-_.", 3, 40);
        String o = key(r);
        return Sample.of(AssetKind.GCS_OBJECT)
                .add("gs://" + b + "/" + o)
                .add("projects/_/buckets/" + b + "/objects/" + o)
                .add("//storage.googleapis.com/projects/_/buckets/" + b + "/objects/" + o)
                .add(b + "/" + o, AssetIdHints.of(AssetKind.GCS_OBJECT))
                .add("https://storage.googleapis.com/" + b + "/" + encode(o, true))
                .add("https://" + b + ".storage.googleapis.com/" + encode(o, true))
                .add("https://www.googleapis.com/storage/v1/b/" + b + "/o/" + encode(o, false));
    }

    private static String dataset(Random r) {
        return pick(r, LOWER + LOWER.toUpperCase(Locale.ROOT) + DIGITS + "_", 1, 20);
    }

    private static String table(Random r) {
        return pick(r, LOWER.toUpperCase(Locale.ROOT) + DIGITS + "_", 1, 1)
                + pick(r, LOWER + LOWER.toUpperCase(Locale.ROOT) + DIGITS + "_- ", 0, 20)
                + pick(r, LOWER + DIGITS, 1, 1);
    }

    private static Sample bqDataset(Random r) {
        String p = project(r);
        String d = dataset(r);
        Sample s = Sample.of(AssetKind.BIGQUERY_DATASET)
                .add("bq://" + p + "/" + d)
                .add("projects/" + p + "/datasets/" + d)
                .add("//bigquery.googleapis.com/projects/" + p + "/datasets/" + d)
                .add("GenericData{classInfo=[datasetId, projectId], {datasetId=" + d + ", projectId=" + p + "}}")
                .add(p + ":" + d, AssetIdHints.of(AssetProvider.GCP))
                .add(d, AssetIdHints.builder().kind(AssetKind.BIGQUERY_DATASET).project(p).build());
        return s.add("bq://" + p + "." + d);
    }

    private static Sample bqTable(Random r) {
        String p = project(r);
        String d = dataset(r);
        String t = table(r);
        Sample s = Sample.of(AssetKind.BIGQUERY_TABLE)
                .add("bq://" + p + "/" + d + "/" + t)
                .add(p + ":" + d + "." + t)
                .add(randomCase(r, p) + ":" + d + "." + t)
                .add(p + "." + d + "." + t, AssetIdHints.of(AssetProvider.GCP))
                .add("`" + p + "." + d + "." + t + "`", AssetIdHints.of(AssetKind.BIGQUERY_TABLE))
                .add("projects/" + p + "/datasets/" + d + "/tables/" + t)
                .add("projects/" + p + "/datasets/" + d + "/tables/" + t + "$2026" + pick(r, DIGITS, 4, 4))
                .add("//bigquery.googleapis.com/projects/" + p + "/datasets/" + d + "/tables/" + t)
                .add("GenericData{classInfo=[datasetId, projectId, tableId], {datasetId=" + d + ", projectId=" + p
                        + ", tableId=" + t + "}}")
                .add(d + "." + t, AssetIdHints.builder().provider(AssetProvider.GCP).project(p).build())
                .add("bq://" + p + "." + d + "." + t);
        return s;
    }

    private static Sample bqJob(Random r) {
        String p = project(r);
        String j = pick(r, LOWER + DIGITS + "_-", 1, 30);
        String loc = r.nextBoolean() ? "US" : "europe-west1";
        return Sample.of(AssetKind.BIGQUERY_JOB)
                .add("bqjob://" + p + "/" + j)
                .add("bqjob://" + p + "/" + loc + "/" + j)
                .add("bigquery://projects/" + p + "/jobs/" + j)
                .add("projects/" + p + "/jobs/" + j)
                .add("projects/" + p + "/locations/" + loc + "/jobs/" + j, AssetIdHints.of(AssetKind.BIGQUERY_JOB))
                .add(p + ":" + loc + "." + j, AssetIdHints.of(AssetKind.BIGQUERY_JOB))
                .add(j, AssetIdHints.builder().kind(AssetKind.BIGQUERY_JOB).project(p).build());
    }

    private static String storageAccount(Random r) {
        return pick(r, LOWER + DIGITS, 3, 24);
    }

    private static String container(Random r) {
        return dnsName(r, "-", 3, 30);
    }

    private static Sample azureAccount(Random r) {
        String a = storageAccount(r);
        String sub = "11111111-2222-3333-4444-" + pick(r, "0123456789abcdef", 12, 12);
        return Sample.of(AssetKind.BLOB_STORAGE)
                .add("az://" + a)
                .add("azure://" + a.toUpperCase(Locale.ROOT))
                .add("https://" + a + ".blob.core.windows.net/")
                .add("/subscriptions/" + sub + "/resourceGroups/RG-" + pick(r, LOWER, 3, 8)
                        + "/providers/Microsoft.Storage/storageAccounts/" + randomCase(r, a))
                .add(a, AssetIdHints.of(AssetKind.BLOB_STORAGE));
    }

    private static Sample azureContainer(Random r) {
        String a = storageAccount(r);
        String c = container(r);
        String sub = "11111111-2222-3333-4444-" + pick(r, "0123456789abcdef", 12, 12);
        return Sample.of(AssetKind.BLOB_CONTAINER)
                .add("az://" + a + "/" + c)
                .add("azure://" + a + "/" + c + "/")
                .add("abfss://" + c + "@" + a + ".dfs.core.windows.net")
                .add("wasbs://" + c.toUpperCase(Locale.ROOT) + "@" + a + ".blob.core.windows.net/")
                .add("https://" + a + ".dfs.core.windows.net/" + c)
                .add("/subscriptions/" + sub + "/resourceGroups/rg1/providers/Microsoft.Storage/storageAccounts/" + a
                        + "/blobServices/default/containers/" + c)
                .add(c, AssetIdHints.builder().kind(AssetKind.BLOB_CONTAINER).account(a).build())
                .add("/" + a + "/" + c, AssetIdHints.of(AssetProvider.AZURE));
    }

    private static Sample azureBlob(Random r) {
        String a = storageAccount(r);
        String c = container(r);
        String p = key(r);
        return Sample.of(AssetKind.BLOB_OBJECT)
                .add("az://" + a + "/" + c + "/" + p)
                .add("azure://" + a + "/" + c + "/" + p)
                .add("abfss://" + c + "@" + a + ".dfs.core.windows.net/" + p)
                .add("wasbs://" + c + "@" + a + ".blob.core.windows.net/" + p)
                .add("https://" + a + ".blob.core.windows.net/" + c + "/" + encode(p, true) + "?sv=2024&sig=x")
                .add("https://" + a + ".dfs.core.windows.net/" + c + "/" + encode(p, r.nextBoolean()))
                .add("/" + a + "/" + c + "/" + p, AssetIdHints.of(AssetProvider.AZURE))
                .add(a + "/" + c + "/" + p, AssetIdHints.of(AssetKind.BLOB_OBJECT));
    }

    private static String ucName(Random r) {
        return pick(r, LOWER + DIGITS + "_-", 1, 20);
    }

    private static String metastore(Random r) {
        String hex = "0123456789abcdef";
        return pick(r, hex, 8, 8) + "-" + pick(r, hex, 4, 4) + "-" + pick(r, hex, 4, 4) + "-" + pick(r, hex, 4, 4)
                + "-" + pick(r, hex, 12, 12);
    }

    private static Sample unityTable(Random r) {
        String m = metastore(r);
        String c = ucName(r);
        String s = ucName(r);
        String t = ucName(r);
        AssetIdHints uc = AssetIdHints.builder().provider(AssetProvider.UNITY).metastoreId(m).build();
        AssetIdHints dbx = AssetIdHints.builder().provider(AssetProvider.DATABRICKS).metastoreId(m).build();
        return Sample.of(AssetKind.UNITY_TABLE)
                .add("uc://" + m + "/" + c + "/" + s + "/" + t)
                .add("uc://" + m.toUpperCase(Locale.ROOT) + "/" + randomCase(r, c) + "/" + s + "/" + randomCase(r, t))
                .add(c + "." + s + "." + t, uc)
                .add("`" + randomCase(r, c) + "`.`" + s + "`.`" + t + "`", dbx)
                .add(c + "." + s + "." + t, AssetIdHints.builder().kind(AssetKind.UNITY_TABLE).metastoreId(m).build());
    }

    private static Sample unityVolume(Random r) {
        String m = metastore(r);
        String c = ucName(r);
        String s = ucName(r);
        String v = ucName(r);
        AssetIdHints uc = AssetIdHints.builder().provider(AssetProvider.UNITY).metastoreId(m).build();
        return Sample.of(AssetKind.UNITY_VOLUME)
                .add("uc://" + m + "/" + c + "/" + s + "/volumes/" + v)
                .add("/Volumes/" + c + "/" + s + "/" + v, uc)
                .add("dbfs:/Volumes/" + c + "/" + s + "/" + v + "/", uc)
                .add(c + "." + s + "." + v, AssetIdHints.builder().kind(AssetKind.UNITY_VOLUME).metastoreId(m).build());
    }

    private static Sample databricksRun(Random r) {
        String ws = (1 + r.nextInt(9)) + pick(r, DIGITS, 15, 15);
        String run = (1 + r.nextInt(9)) + pick(r, DIGITS, 0, 12);
        String job = (1 + r.nextInt(9)) + pick(r, DIGITS, 0, 8);
        return Sample.of(AssetKind.DATABRICKS_JOB)
                .add("dbx://" + ws + "/runs/" + run)
                .add("dbx://" + ws + "/jobs/" + job + "/runs/" + run)
                .add("databricks://" + ws + "/jobs/runs/" + run)
                .add("databricks://adb-" + ws + "." + r.nextInt(20) + ".azuredatabricks.net/jobs/runs/" + run)
                .add("databricks://dbc-" + pick(r, LOWER + DIGITS, 8, 8) + ".cloud.databricks.com/jobs/runs/" + run,
                        AssetIdHints.builder().workspaceId(ws).build());
    }

    private static Sample snowflakeTable(Random r) {
        String acct = dnsName(r, "-_", 3, 20);
        String db = pick(r, LOWER.toUpperCase(Locale.ROOT), 1, 1) + pick(r, LOWER.toUpperCase(Locale.ROOT) + DIGITS + "_", 0, 15);
        String s = pick(r, LOWER.toUpperCase(Locale.ROOT), 1, 1) + pick(r, LOWER.toUpperCase(Locale.ROOT) + DIGITS + "_", 0, 15);
        String t = pick(r, LOWER.toUpperCase(Locale.ROOT), 1, 1) + pick(r, LOWER.toUpperCase(Locale.ROOT) + DIGITS + "_", 0, 15);
        AssetIdHints sf = AssetIdHints.builder().provider(AssetProvider.SNOWFLAKE).account(acct).build();
        return Sample.of(AssetKind.SNOWFLAKE_TABLE)
                .add("snowflake://" + acct + "/" + db + "/" + s + "/" + t)
                .add("snowflake://" + acct.toUpperCase(Locale.ROOT) + "/" + db + "/" + s + "/" + t)
                .add(db.toLowerCase(Locale.ROOT) + "." + s + "." + randomCase(r, t), sf)
                .add("\"" + db + "\".\"" + s + "\".\"" + t + "\"", sf)
                .add(db + "." + s + "." + t, AssetIdHints.builder().kind(AssetKind.SNOWFLAKE_TABLE).account(acct).build());
    }

    private static Sample rdsInstance(Random r) {
        String id = LOWER.charAt(r.nextInt(26)) + pick(r, LOWER + DIGITS + "-", 0, 30);
        String region = region(r);
        String acct = account12(r);
        String partition = region.startsWith("cn-") ? "aws-cn" : region.startsWith("us-gov-") ? "aws-us-gov" : "aws";
        return Sample.of(AssetKind.RDS_INSTANCE)
                .add("arn:" + partition + ":rds:" + region + ":" + acct + ":db:" + id)
                .add("arn:" + partition + ":rds:" + region + ":" + acct + ":db:" + id.toUpperCase(Locale.ROOT))
                .add("rds://" + region + "/" + id, AssetIdHints.builder().account(acct).build())
                .add(randomCase(r, id), AssetIdHints.builder().kind(AssetKind.RDS_INSTANCE).region(region).account(acct).build());
    }

    private static Sample glueTable(Random r) {
        String db = pick(r, LOWER + DIGITS + "_", 1, 20);
        String t = pick(r, LOWER + DIGITS + "_", 1, 20);
        String region = region(r);
        String acct = account12(r);
        String partition = region.startsWith("cn-") ? "aws-cn" : region.startsWith("us-gov-") ? "aws-us-gov" : "aws";
        AssetIdHints h = AssetIdHints.builder().kind(AssetKind.GLUE_TABLE).region(region).account(acct).build();
        // Glue folds database and table names to lower case, so any-case spellings converge.
        return Sample.of(AssetKind.GLUE_TABLE)
                .add("arn:" + partition + ":glue:" + region + ":" + acct + ":table/" + db + "/" + t)
                .add("arn:" + partition + ":glue:" + region + ":" + acct + ":table/" + randomCase(r, db) + "/" + randomCase(r, t))
                .add(db + "." + t, h)
                .add(randomCase(r, db) + "." + randomCase(r, t), h)
                .add("AwsDataCatalog." + db + "." + t, h)
                .add("\"" + db + "\".\"" + t + "\"", h)
                .add("\"" + randomCase(r, db) + "\".\"" + randomCase(r, t) + "\"", h);
    }

    private static Sample redshiftCluster(Random r) {
        String id = LOWER.charAt(r.nextInt(26)) + pick(r, LOWER + DIGITS + "-", 0, 30);
        String region = region(r);
        String acct = account12(r);
        String partition = region.startsWith("cn-") ? "aws-cn" : region.startsWith("us-gov-") ? "aws-us-gov" : "aws";
        return Sample.of(AssetKind.REDSHIFT_CLUSTER)
                .add("arn:" + partition + ":redshift:" + region + ":" + acct + ":cluster:" + id)
                .add("arn:" + partition + ":redshift:" + region + ":" + acct + ":cluster:" + id.toUpperCase(Locale.ROOT))
                .add(randomCase(r, id),
                        AssetIdHints.builder().kind(AssetKind.REDSHIFT_CLUSTER).region(region).account(acct).build());
    }
}
