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

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Every identifier form in use today (design table A4 in research/hardening/06, plus the e2e fixture
 * forms and the retire-path parsers) converges to one canonical ID per resource, and the canonical
 * URI is a fixed point.
 */
class AssetIdAliasConvergenceTest {

    static final String ACCT = "123456789012";
    static final String SUB = "11111111-2222-3333-4444-555566667777";
    static final String METASTORE = "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0";
    static final String WS = "1234567890123456";

    record Alias(String raw, AssetIdHints hints) {
        @Override
        public String toString() {
            return raw + (hints.equals(AssetIdHints.none()) ? "" : " " + hints);
        }
    }

    record Family(String name, String uri, AssetKind kind, AssetProvider provider, List<Alias> aliases) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Alias a(String raw) {
        return new Alias(raw, AssetIdHints.none());
    }

    static Alias a(String raw, AssetIdHints hints) {
        return new Alias(raw, hints);
    }

    static AssetIdHints kind(AssetKind k) {
        return AssetIdHints.of(k);
    }

    static AssetIdHints aws(AssetKind k) {
        return AssetIdHints.builder().kind(k).region("us-east-1").account(ACCT).build();
    }

    static Family f(String name, String uri, AssetKind kind, AssetProvider provider, Alias... aliases) {
        return new Family(name, uri, kind, provider, List.of(aliases));
    }

    static List<Family> families() {
        List<Family> out = new ArrayList<>();
        // ---- AWS ----
        out.add(f("S3 bucket", "s3://finance-reports", AssetKind.S3_BUCKET, AssetProvider.AWS,
                a("s3://finance-reports"),
                a("S3://finance-reports"),
                a("s3://finance-reports/"),
                a("s3a://finance-reports"),
                a("arn:aws:s3:::finance-reports"),
                a("finance-reports", kind(AssetKind.S3_BUCKET)),
                a("https://finance-reports.s3.amazonaws.com/"),
                a("https://finance-reports.s3.eu-west-1.amazonaws.com"),
                a("https://s3.eu-west-1.amazonaws.com/finance-reports"),
                a("  s3://finance-reports\r\n")));
        out.add(f("S3 object", "s3://finance-reports/q1/old-report.csv", AssetKind.S3_OBJECT, AssetProvider.AWS,
                a("s3://finance-reports/q1/old-report.csv"),
                a("arn:aws:s3:::finance-reports/q1/old-report.csv"),
                a("s3a://finance-reports/q1/old-report.csv"),
                a("https://finance-reports.s3.us-east-1.amazonaws.com/q1/old-report.csv"),
                a("https://finance-reports.s3.amazonaws.com/q1/old-report.csv?versionId=abc123"),
                a("https://s3.amazonaws.com/finance-reports/q1%2Fold-report.csv"),
                a("finance-reports/q1/old-report.csv", kind(AssetKind.S3_OBJECT))));
        out.add(f("S3 object with spaces, plus and UTF-8", "s3://finance-reports/Q1 2026/caf\u00e9+x.csv",
                AssetKind.S3_OBJECT, AssetProvider.AWS,
                a("s3://finance-reports/Q1 2026/caf\u00e9+x.csv"),
                a("arn:aws:s3:::finance-reports/Q1 2026/caf\u00e9+x.csv"),
                a("https://finance-reports.s3.amazonaws.com/Q1%202026/caf%C3%A9+x.csv"),
                a("https://s3.us-west-2.amazonaws.com/finance-reports/Q1%202026/caf%C3%A9%2Bx.csv")));
        out.add(f("S3 legacy bucket keeps its case", "s3://Legacy_Bucket", AssetKind.S3_BUCKET, AssetProvider.AWS,
                a("s3://Legacy_Bucket"),
                a("arn:aws:s3:::Legacy_Bucket"),
                a("https://s3.amazonaws.com/Legacy_Bucket")));
        out.add(f("RDS instance", "arn:aws:rds:eu-west-1:" + ACCT + ":db:prod-postgres", AssetKind.RDS_INSTANCE, AssetProvider.AWS,
                a("arn:aws:rds:eu-west-1:" + ACCT + ":db:prod-postgres"),
                a("arn:aws:rds:eu-west-1:" + ACCT + ":db:PROD-Postgres"),
                a("rds://eu-west-1/prod-postgres", AssetIdHints.builder().account(ACCT).build()),
                a("rds://EU-WEST-1/prod-postgres", AssetIdHints.builder().account(ACCT).build()),
                a("prod-postgres", AssetIdHints.builder().kind(AssetKind.RDS_INSTANCE).region("eu-west-1").account(ACCT).build())));
        out.add(f("RDS instance, China partition", "arn:aws-cn:rds:cn-north-1:" + ACCT + ":db:orders", AssetKind.RDS_INSTANCE,
                AssetProvider.AWS,
                a("arn:aws-cn:rds:cn-north-1:" + ACCT + ":db:orders"),
                a("rds://cn-north-1/orders", AssetIdHints.builder().account(ACCT).build())));
        out.add(f("DynamoDB table", "arn:aws:dynamodb:us-east-1:" + ACCT + ":table/Orders", AssetKind.DYNAMODB_TABLE, AssetProvider.AWS,
                a("arn:aws:dynamodb:us-east-1:" + ACCT + ":table/Orders"),
                a("dynamodb://us-east-1/Orders", AssetIdHints.builder().account(ACCT).build()),
                a("Orders", aws(AssetKind.DYNAMODB_TABLE))));
        out.add(f("Redshift cluster", "arn:aws:redshift:us-east-1:" + ACCT + ":cluster:analytics", AssetKind.REDSHIFT_CLUSTER,
                AssetProvider.AWS,
                a("arn:aws:redshift:us-east-1:" + ACCT + ":cluster:analytics"),
                a("arn:aws:redshift:us-east-1:" + ACCT + ":cluster:Analytics"),
                a("analytics", aws(AssetKind.REDSHIFT_CLUSTER)),
                a("ANALYTICS", aws(AssetKind.REDSHIFT_CLUSTER))));
        out.add(f("Glue database", "arn:aws:glue:us-east-1:" + ACCT + ":database/sales", AssetKind.GLUE_DATABASE, AssetProvider.AWS,
                a("arn:aws:glue:us-east-1:" + ACCT + ":database/sales"),
                // Glue folds database and table names to lower case when it stores them (Hive compatibility).
                a("arn:aws:glue:us-east-1:" + ACCT + ":database/Sales"),
                a("sales", aws(AssetKind.GLUE_DATABASE)),
                a("SALES", aws(AssetKind.GLUE_DATABASE))));
        out.add(f("Glue table", "arn:aws:glue:us-east-1:" + ACCT + ":table/sales/orders", AssetKind.GLUE_TABLE, AssetProvider.AWS,
                a("arn:aws:glue:us-east-1:" + ACCT + ":table/sales/orders"),
                a("sales.orders", aws(AssetKind.GLUE_TABLE)),
                a("AwsDataCatalog.sales.orders", aws(AssetKind.GLUE_TABLE)),
                a("\"sales\".\"orders\"", aws(AssetKind.GLUE_TABLE)),
                a("arn:aws:glue:us-east-1:" + ACCT + ":table/Sales/Orders"),
                a("\"Sales\".\"Orders\"", aws(AssetKind.GLUE_TABLE)),
                a("awsdatacatalog.SALES.orders", aws(AssetKind.GLUE_TABLE))));
        out.add(f("Glue job", "arn:aws:glue:us-east-1:" + ACCT + ":job/nightly-etl", AssetKind.SPARK_JOB, AssetProvider.AWS,
                a("arn:aws:glue:us-east-1:" + ACCT + ":job/nightly-etl"),
                a("ARN:AWS:GLUE:US-EAST-1:" + ACCT + ":job/nightly-etl")));
        out.add(f("EMR step", "arn:aws:elasticmapreduce:us-east-1:" + ACCT + ":cluster/j-2AXXXXXXGAPLF/step/s-1234ABCD",
                AssetKind.SPARK_JOB, AssetProvider.AWS,
                a("arn:aws:elasticmapreduce:us-east-1:" + ACCT + ":cluster/j-2AXXXXXXGAPLF/step/s-1234ABCD")));
        out.add(f("Athena query", "athena://us-east-1/" + ACCT + "/a1b2c3d4-5678-90ab-cdef-111122223333",
                AssetKind.REDSHIFT_QUERY, AssetProvider.AWS,
                a("athena://us-east-1/query/a1b2c3d4-5678-90ab-cdef-111122223333", AssetIdHints.builder().account(ACCT).build()),
                a("athena://us-east-1/" + ACCT + "/a1b2c3d4-5678-90ab-cdef-111122223333"),
                a("a1b2c3d4-5678-90ab-cdef-111122223333", aws(AssetKind.REDSHIFT_QUERY))));

        // ---- GCP ----
        out.add(f("GCS bucket", "gs://dlap-gcp-e2e-analytics", AssetKind.GCS_BUCKET, AssetProvider.GCP,
                a("gs://dlap-gcp-e2e-analytics"),
                a("GS://DLAP-GCP-E2E-ANALYTICS"),
                a("projects/_/buckets/dlap-gcp-e2e-analytics"),
                a("//storage.googleapis.com/projects/_/buckets/dlap-gcp-e2e-analytics"),
                a("//storage.googleapis.com/dlap-gcp-e2e-analytics"),
                a("dlap-gcp-e2e-analytics", kind(AssetKind.GCS_BUCKET)),
                a("https://storage.googleapis.com/dlap-gcp-e2e-analytics"),
                a("https://www.googleapis.com/storage/v1/b/dlap-gcp-e2e-analytics"),
                a("https://dlap-gcp-e2e-analytics.storage.googleapis.com/")));
        out.add(f("GCS object", "gs://dlap-gcp-e2e-analytics/2026/07/orders.parquet", AssetKind.GCS_OBJECT, AssetProvider.GCP,
                a("gs://dlap-gcp-e2e-analytics/2026/07/orders.parquet"),
                a("projects/_/buckets/dlap-gcp-e2e-analytics/objects/2026/07/orders.parquet"),
                a("//storage.googleapis.com/projects/_/buckets/dlap-gcp-e2e-analytics/objects/2026/07/orders.parquet"),
                a("https://storage.googleapis.com/dlap-gcp-e2e-analytics/2026/07/orders.parquet"),
                a("https://storage.cloud.google.com/dlap-gcp-e2e-analytics/2026/07/orders.parquet"),
                a("https://dlap-gcp-e2e-analytics.storage.googleapis.com/2026/07/orders.parquet"),
                a("https://www.googleapis.com/storage/v1/b/dlap-gcp-e2e-analytics/o/2026%2F07%2Forders.parquet"),
                a("dlap-gcp-e2e-analytics/2026/07/orders.parquet", kind(AssetKind.GCS_OBJECT))));
        out.add(f("BigQuery dataset", "bq://dlap-gcp-e2e/analytics", AssetKind.BIGQUERY_DATASET, AssetProvider.GCP,
                // BigQueryCrawler.java:268 stores DatasetId.toString(); this is its real output (google-cloud-bigquery 2.50.0).
                a("GenericData{classInfo=[datasetId, projectId], {datasetId=analytics, projectId=dlap-gcp-e2e}}"),
                a("projects/dlap-gcp-e2e/datasets/analytics"),
                a("//bigquery.googleapis.com/projects/dlap-gcp-e2e/datasets/analytics"),
                a("bq://dlap-gcp-e2e/analytics"),
                a("bq://dlap-gcp-e2e.analytics"),
                a("dlap-gcp-e2e:analytics", AssetIdHints.of(AssetProvider.GCP)),
                a("analytics", AssetIdHints.builder().kind(AssetKind.BIGQUERY_DATASET).project("dlap-gcp-e2e").build()),
                a("https://bigquery.googleapis.com/bigquery/v2/projects/dlap-gcp-e2e/datasets/analytics")));
        out.add(f("BigQuery table", "bq://dlap-gcp-e2e/analytics/orders", AssetKind.BIGQUERY_TABLE, AssetProvider.GCP,
                a("dlap-gcp-e2e:analytics.orders"),
                a("DLAP-GCP-E2E:analytics.orders"),
                a("projects/dlap-gcp-e2e/datasets/analytics/tables/orders"),
                a("projects/dlap-gcp-e2e/datasets/analytics/tables/orders$20260701"),
                a("projects/dlap-gcp-e2e/datasets/analytics/tables/orders@1700000000000"),
                a("dlap-gcp-e2e.analytics.orders", AssetIdHints.of(AssetProvider.GCP)),
                a("`dlap-gcp-e2e.analytics.orders`", AssetIdHints.of(AssetProvider.GCP)),
                a("`dlap-gcp-e2e`.`analytics`.`orders`", AssetIdHints.of(AssetProvider.GCP)),
                a("analytics.orders", AssetIdHints.builder().provider(AssetProvider.GCP).project("dlap-gcp-e2e").build()),
                a("bq://dlap-gcp-e2e/analytics/orders"),
                a("bq://dlap-gcp-e2e.analytics.orders"),
                a("bq://dlap-gcp-e2e/analytics.orders"),
                a("//bigquery.googleapis.com/projects/dlap-gcp-e2e/datasets/analytics/tables/orders"),
                a("GenericData{classInfo=[datasetId, projectId, tableId], {datasetId=analytics, projectId=dlap-gcp-e2e, tableId=orders}}")));
        out.add(f("BigQuery table, domain-scoped project", "bq://example.com:analytics-prod/sales/orders",
                AssetKind.BIGQUERY_TABLE, AssetProvider.GCP,
                a("example.com:analytics-prod:sales.orders"),
                a("example.com:analytics-prod.sales.orders", AssetIdHints.of(AssetProvider.GCP)),
                a("projects/example.com:analytics-prod/datasets/sales/tables/orders"),
                a("bq://example.com:analytics-prod/sales/orders"),
                a("bq://example.com:analytics-prod.sales.orders")));
        out.add(f("BigQuery table name with space and dash", "bq://dlap-gcp-e2e/analytics/daily orders-v2",
                AssetKind.BIGQUERY_TABLE, AssetProvider.GCP,
                a("dlap-gcp-e2e:analytics.daily orders-v2"),
                a("projects/dlap-gcp-e2e/datasets/analytics/tables/daily orders-v2"),
                a("`dlap-gcp-e2e.analytics.daily orders-v2`", AssetIdHints.of(AssetProvider.GCP))));
        out.add(f("BigQuery job", "bqjob://dlap-gcp-e2e/bquxjob_1a2b3c_18f2", AssetKind.BIGQUERY_JOB, AssetProvider.GCP,
                a("bigquery://projects/dlap-gcp-e2e/jobs/bquxjob_1a2b3c_18f2"),
                a("projects/dlap-gcp-e2e/jobs/bquxjob_1a2b3c_18f2"),
                a("//bigquery.googleapis.com/projects/dlap-gcp-e2e/jobs/bquxjob_1a2b3c_18f2"),
                a("bqjob://dlap-gcp-e2e/US/bquxjob_1a2b3c_18f2"),
                a("bqjob://dlap-gcp-e2e/_/bquxjob_1a2b3c_18f2"),
                a("dlap-gcp-e2e:US.bquxjob_1a2b3c_18f2", kind(AssetKind.BIGQUERY_JOB)),
                a("projects/dlap-gcp-e2e/locations/US/jobs/bquxjob_1a2b3c_18f2", kind(AssetKind.BIGQUERY_JOB)),
                a("bquxjob_1a2b3c_18f2", AssetIdHints.builder().kind(AssetKind.BIGQUERY_JOB).project("dlap-gcp-e2e").build()),
                a("GenericData{classInfo=[jobId, location, projectId], {jobId=bquxjob_1a2b3c_18f2, location=US, projectId=dlap-gcp-e2e}}")));
        out.add(f("Cloud SQL instance", "//cloudsql.googleapis.com/projects/dlap-gcp-e2e/instances/orders-db",
                AssetKind.CLOUD_SQL_INSTANCE, AssetProvider.GCP,
                a("//cloudsql.googleapis.com/projects/dlap-gcp-e2e/instances/orders-db"),
                a("//sqladmin.googleapis.com/projects/dlap-gcp-e2e/instances/orders-db"),
                a("projects/dlap-gcp-e2e/instances/orders-db", kind(AssetKind.CLOUD_SQL_INSTANCE)),
                a("dlap-gcp-e2e:us-central1:orders-db", kind(AssetKind.CLOUD_SQL_INSTANCE)),
                a("orders-db", AssetIdHints.builder().kind(AssetKind.CLOUD_SQL_INSTANCE).project("dlap-gcp-e2e").build())));

        // ---- Azure ----
        String armAccount = "/subscriptions/" + SUB + "/resourceGroups/rg-dlap-e2e/providers/Microsoft.Storage/storageAccounts/dlapazuree2e";
        out.add(f("Azure storage account", "az://dlapazuree2e", AssetKind.BLOB_STORAGE, AssetProvider.AZURE,
                a("az://dlapazuree2e"),
                a("azure://dlapazuree2e"),
                a("https://dlapazuree2e.blob.core.windows.net"),
                a("https://DLAPAZUREE2E.blob.core.windows.net/"),
                a(armAccount),
                a(armAccount + "/blobServices/default"),
                a("dlapazuree2e", kind(AssetKind.BLOB_STORAGE))));
        out.add(f("Azure container", "az://dlapazuree2e/analytics", AssetKind.BLOB_CONTAINER, AssetProvider.AZURE,
                a("az://dlapazuree2e/analytics"),
                a("azure://dlapazuree2e/analytics"),
                a("abfss://analytics@dlapazuree2e.dfs.core.windows.net"),
                a("abfss://analytics@dlapazuree2e.dfs.core.windows.net/"),
                a("wasbs://analytics@dlapazuree2e.blob.core.windows.net"),
                a("https://dlapazuree2e.blob.core.windows.net/analytics"),
                a("https://dlapazuree2e.dfs.core.windows.net/analytics/"),
                a(armAccount + "/blobServices/default/containers/analytics"),
                a("analytics", AssetIdHints.builder().kind(AssetKind.BLOB_CONTAINER).account("dlapazuree2e").build()),
                a("dlapazuree2e/analytics", kind(AssetKind.BLOB_CONTAINER)),
                a("/dlapazuree2e/analytics", AssetIdHints.of(AssetProvider.AZURE))));
        out.add(f("Azure blob", "az://dlapazuree2e/analytics/customers.parquet", AssetKind.BLOB_OBJECT, AssetProvider.AZURE,
                a("https://dlapazuree2e.blob.core.windows.net/analytics/customers.parquet"),
                a("az://dlapazuree2e/analytics/customers.parquet"),
                a("azure://dlapazuree2e/analytics/customers.parquet"),
                a("abfss://analytics@dlapazuree2e.dfs.core.windows.net/customers.parquet"),
                a("abfs://analytics@dlapazuree2e.dfs.core.windows.net/customers.parquet"),
                a("wasbs://analytics@dlapazuree2e.blob.core.windows.net/customers.parquet"),
                a("https://dlapazuree2e.dfs.core.windows.net/analytics/customers.parquet"),
                a("https://dlapazuree2e.blob.core.windows.net/analytics/customers.parquet?sv=2024-01-01&sig=REDACTED"),
                a("/dlapazuree2e/analytics/customers.parquet", AssetIdHints.of(AssetProvider.AZURE)),
                a("\"/dlapazuree2e/analytics/customers.parquet\"", AssetIdHints.of(AssetProvider.AZURE)),
                a("dlapazuree2e/analytics/customers.parquet", kind(AssetKind.BLOB_OBJECT))));
        String sqlArm = "/subscriptions/" + SUB + "/resourcegroups/rg-dlap-e2e/providers/microsoft.sql/servers/sql-dlap/databases/orders";
        out.add(f("Azure SQL database", sqlArm, AssetKind.SQL_DATABASE, AssetProvider.AZURE,
                a(sqlArm),
                a("/subscriptions/" + SUB + "/resourceGroups/RG-DLAP-E2E/providers/Microsoft.Sql/servers/sql-dlap/databases/Orders/"),
                a("azuresql://" + SUB + "/rg-dlap-e2e/sql-dlap/orders")));
        String cosmosArm = "/subscriptions/" + SUB
                + "/resourcegroups/rg-dlap-e2e/providers/microsoft.documentdb/databaseaccounts/dlapcosmos/sqldatabases/app/containers/events";
        out.add(f("Cosmos DB container", cosmosArm, AssetKind.COSMOS_DB, AssetProvider.AZURE,
                a(cosmosArm),
                a("azurecosmos://dlapcosmos/app/events",
                        AssetIdHints.builder().subscription(SUB).resourceGroup("rg-dlap-e2e").build())));
        String adfArm = "/subscriptions/" + SUB + "/resourcegroups/rg-dlap-e2e/providers/microsoft.datafactory/factories/adf-dlap/pipelines/copy-orders";
        out.add(f("Data Factory pipeline", adfArm, AssetKind.DATAFLOW_JOB, AssetProvider.AZURE,
                a(adfArm),
                a("/subscriptions/" + SUB + "/resourceGroups/rg-dlap-e2e/providers/Microsoft.DataFactory/factories/adf-dlap/pipelines/Copy-Orders")));

        String adfSpaced = "/subscriptions/" + SUB
                + "/resourcegroups/rg-dlap-e2e/providers/microsoft.datafactory/factories/adf-dlap/pipelines/copy orders nightly";
        out.add(f("Data Factory pipeline name with spaces", adfSpaced, AssetKind.DATAFLOW_JOB, AssetProvider.AZURE,
                a(adfSpaced),
                a("/subscriptions/" + SUB
                        + "/resourceGroups/rg-dlap-e2e/providers/Microsoft.DataFactory/factories/adf-dlap/pipelines/Copy Orders Nightly")));
        out.add(f("Azure blob in an account named synapse", "az://synapse/landing/sparkjobs/7", AssetKind.BLOB_OBJECT,
                AssetProvider.AZURE,
                a("azure://synapse/landing/sparkjobs/7"),
                a("az://synapse/landing/sparkjobs/7"),
                a("https://synapse.blob.core.windows.net/landing/sparkjobs/7")));
        out.add(f("Azure blob shaped like the synapse job form, read as a blob by hint", "az://synapse/" + SUB + "/sparkjobs/7",
                AssetKind.BLOB_OBJECT, AssetProvider.AZURE,
                a("azure://synapse/" + SUB + "/sparkjobs/7", kind(AssetKind.BLOB_OBJECT)),
                a("az://synapse/" + SUB + "/sparkjobs/7")));

        // ---- Unity Catalog ----
        AssetIdHints uc = AssetIdHints.builder().provider(AssetProvider.UNITY).metastoreId(METASTORE).build();
        AssetIdHints dbx = AssetIdHints.builder().provider(AssetProvider.DATABRICKS).metastoreId(METASTORE).build();
        out.add(f("Unity Catalog table", "uc://" + METASTORE + "/main/sales/orders", AssetKind.UNITY_TABLE, AssetProvider.UNITY,
                a("main.sales.orders", uc),
                a("`main`.`sales`.`orders`", dbx),
                a("MAIN.Sales.ORDERS", AssetIdHints.builder().kind(AssetKind.UNITY_TABLE).metastoreId(METASTORE).build()),
                a("uc://" + METASTORE.toUpperCase() + "/main/sales/orders"),
                a("uc://" + METASTORE + "/main/sales/orders")));
        out.add(f("Unity Catalog schema", "uc://" + METASTORE + "/main/sales", AssetKind.UNITY_SCHEMA, AssetProvider.UNITY,
                a("main.sales", uc),
                a("uc://" + METASTORE + "/Main/Sales")));
        out.add(f("Unity Catalog catalog", "uc://" + METASTORE + "/main", AssetKind.UNITY_CATALOG, AssetProvider.UNITY,
                a("main", uc),
                a("main", AssetIdHints.builder().kind(AssetKind.UNITY_CATALOG).metastoreId(METASTORE).build()),
                a("uc://" + METASTORE + "/main")));
        out.add(f("Unity Catalog volume", "uc://" + METASTORE + "/main/raw/volumes/landing", AssetKind.UNITY_VOLUME, AssetProvider.UNITY,
                a("/Volumes/main/raw/landing", uc),
                a("/Volumes/main/raw/landing/", uc),
                a("dbfs:/Volumes/main/raw/landing", uc),
                a("main.raw.landing", AssetIdHints.builder().kind(AssetKind.UNITY_VOLUME).metastoreId(METASTORE).build()),
                a("uc://" + METASTORE + "/main/raw/volumes/landing")));

        // ---- Databricks ----
        out.add(f("Databricks job run", "dbx://" + WS + "/runs/987654", AssetKind.DATABRICKS_JOB, AssetProvider.DATABRICKS,
                a("databricks://" + WS + "/jobs/runs/987654"),
                a("dbx://" + WS + "/runs/987654"),
                a("dbx://" + WS + "/jobs/42/runs/987654"),
                a("databricks://adb-" + WS + ".7.azuredatabricks.net/jobs/runs/987654"),
                a("databricks://dbc-a1b2c3d4-e5f6.cloud.databricks.com/jobs/runs/987654",
                        AssetIdHints.builder().workspaceId(WS).build())));
        out.add(f("Databricks job", "dbx://" + WS + "/jobs/42", AssetKind.DATABRICKS_JOB, AssetProvider.DATABRICKS,
                a("dbx://" + WS + "/jobs/42"),
                a("databricks://" + WS + "/jobs/42")));
        out.add(f("Databricks SQL query", "dbx://" + WS + "/queries/01ef2a3b-4c5d-6e7f-8091-a2b3c4d5e6f7",
                AssetKind.DATABRICKS_JOB, AssetProvider.DATABRICKS,
                a("databricks://" + WS + "/sql/queries/01ef2a3b-4c5d-6e7f-8091-a2b3c4d5e6f7"),
                a("dbx://" + WS + "/queries/01ef2a3b-4c5d-6e7f-8091-a2b3c4d5e6f7")));

        // ---- Snowflake ----
        AssetIdHints sf = AssetIdHints.builder().provider(AssetProvider.SNOWFLAKE).account("myorg-analytics").build();
        out.add(f("Snowflake table", "snowflake://myorg-analytics/SALES/PUBLIC/ORDERS", AssetKind.SNOWFLAKE_TABLE, AssetProvider.SNOWFLAKE,
                a("sales.public.orders", sf),
                a("\"SALES\".\"PUBLIC\".\"ORDERS\"", sf),
                a("SALES.PUBLIC.ORDERS", AssetIdHints.builder().kind(AssetKind.SNOWFLAKE_TABLE).account("MyOrg-Analytics").build()),
                a("snowflake://MYORG-ANALYTICS/SALES/PUBLIC/ORDERS")));
        out.add(f("Snowflake quoted mixed-case table", "snowflake://myorg-analytics/SALES/PUBLIC/MixedCase",
                AssetKind.SNOWFLAKE_TABLE, AssetProvider.SNOWFLAKE,
                a("sales.public.\"MixedCase\"", sf),
                a("snowflake://myorg-analytics/SALES/PUBLIC/MixedCase")));
        return out;
    }

    static Stream<Arguments> aliases() {
        return families().stream().flatMap(fam -> fam.aliases().stream().map(al -> Arguments.of(fam, al)));
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("aliases")
    void aliasConvergesToTheCanonicalId(Family family, Alias alias) {
        AssetIdParseResult result = AssetIdNormalizer.parse(alias.raw(), alias.hints());

        assertThat(result.isNormalized()).as("rejected: %s", result).isTrue();
        CanonicalAssetId id = result.id().orElseThrow();
        assertThat(id.uri()).isEqualTo(family.uri());
        assertThat(id.kind()).isEqualTo(family.kind());
        assertThat(id.provider()).isEqualTo(family.provider());
        assertThat(id.serviceType()).isEqualTo(family.kind().serviceType());
        assertThat(id.cloudProvider()).isEqualTo(family.provider().name());
        assertThat(result.raw()).isEqualTo(alias.raw());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("families")
    void canonicalUriIsAFixedPoint(Family family) {
        CanonicalAssetId once = AssetIdNormalizer.normalize(family.uri()).orElseThrow();
        CanonicalAssetId twice = AssetIdNormalizer.normalize(once.uri()).orElseThrow();

        assertThat(once.uri()).isEqualTo(family.uri());
        assertThat(twice).isEqualTo(once);
        assertThat(twice.kind()).isEqualTo(family.kind());
        assertThat(AssetIdNormalizer.isCanonical(family.uri())).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("families")
    void buildFromPartsReproducesTheId(Family family) {
        CanonicalAssetId id = AssetIdNormalizer.normalize(family.uri()).orElseThrow();

        AssetIdParseResult rebuilt = AssetIdNormalizer.build(id.provider(), id.kind(), id.parts(), AssetIdHints.none());

        assertThat(rebuilt.id()).as("rebuild of %s: %s", id, rebuilt).contains(id);
    }

    @Test
    void everyKindWithACanonicalSchemeIsCovered() {
        Set<AssetKind> covered = EnumSet.noneOf(AssetKind.class);
        families().forEach(fam -> covered.add(fam.kind()));
        covered.add(AssetKind.PYTHON_JOB); // the Glue job family with a PYTHON_JOB hint, see CanonicalAssetIdTest

        Set<AssetKind> withoutScheme = EnumSet.complementOf(EnumSet.copyOf(covered));

        assertThat(withoutScheme).containsExactlyInAnyOrder(AssetKind.DATA_LAKE, AssetKind.DBT_MODEL);
    }

    @Test
    void distinctResourcesStayDistinct() {
        assertThat(uri("s3://Legacy_Bucket")).isNotEqualTo(uri("s3://legacy_bucket"));
        assertThat(uri("gs://b-1/Key.csv")).isNotEqualTo(uri("gs://b-1/key.csv"));
        assertThat(uri("bq://p-12345/Sales/orders")).isNotEqualTo(uri("bq://p-12345/sales/orders"));
        assertThat(uri("s3://shared-name")).isNotEqualTo(uri("gs://shared-name"));
        assertThat(uri("az://acct1/c-1/x")).isNotEqualTo(uri("az://acct1/c-1/X"));
        assertThat(uri("uc://m1/main/sales/orders")).isNotEqualTo(uri("uc://m2/main/sales/orders"));
        assertThat(uri("uc://m1/main/sales/orders")).isNotEqualTo(uri("uc://m1/main/sales/volumes/orders"));
        // The GCS crawler stores the bare object key as resourceId; keys collide across buckets. Canonical IDs do not.
        assertThat(uri("gs://bucket-a/2026/data.csv")).isNotEqualTo(uri("gs://bucket-b/2026/data.csv"));
        // Glue job names and DynamoDB table names keep their case; only Glue databases and tables fold.
        assertThat(uri("arn:aws:glue:us-east-1:123456789012:job/Nightly"))
                .isNotEqualTo(uri("arn:aws:glue:us-east-1:123456789012:job/nightly"));
        assertThat(uri("arn:aws:dynamodb:us-east-1:123456789012:table/Orders"))
                .isNotEqualTo(uri("arn:aws:dynamodb:us-east-1:123456789012:table/orders"));
    }

    private static String uri(String raw) {
        return AssetIdNormalizer.normalize(raw).orElseThrow().uri();
    }
}
