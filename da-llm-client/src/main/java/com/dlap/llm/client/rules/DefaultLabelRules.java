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

package com.dlap.llm.client.rules;

import static com.dlap.llm.client.rules.LabelRule.ANY_FIELD;
import static com.dlap.llm.client.rules.LabelRule.METADATA_ONLY;
import static com.dlap.llm.client.rules.LabelRule.NAME_AND_PATH;

import java.util.List;

/**
 * The rule set the {@code RULES} label provider ships with.
 *
 * <p>Every rule proposes a label from the canonical taxonomy seeded by
 * {@code 02-create-common-schema.sql} and {@code 03-insert-test-data.sql}, so a
 * rule match lands on an existing label instead of auto-creating a new one.
 *
 * <p>Design limits, deliberate and part of the contract:
 * <ul>
 *   <li>The set never proposes {@code PUBLIC} or {@code INTERNAL}. A keyword in an
 *       asset name is not evidence that data is safe to expose, and a wrong
 *       permissive label is worse than no label. Rules may only escalate.</li>
 *   <li>The set never proposes {@code ARCHIVED}. That label records lifecycle state
 *       the policy engine owns; a name containing "archive" does not prove it.</li>
 *   <li>Evidence is the asset name, path and business metadata only. No rule reads
 *       row content, so a match means "the naming says so", not "the data was
 *       inspected".</li>
 *   <li>Confidences are fixed per rule: 0.95-0.98 for explicit markers and secret
 *       patterns, 0.85-0.92 for strong domain vocabulary, 0.80-0.82 for
 *       broader vocabulary, 0.65 for weak signals. The weak tier sits below the
 *       auto-labeler's default 0.70 threshold on purpose, so those suggestions go
 *       to human review instead of being applied.</li>
 * </ul>
 *
 * <p>Changing a rule, a confidence or the label vocabulary changes labeling
 * results, so {@link #VERSION} must be bumped with it.
 */
public final class DefaultLabelRules {

    /** Version of the shipped rule set; reported as the model name of the provider. */
    public static final String VERSION = "rules-v1";

    private static final String SENSITIVITY = "Sensitivity";
    private static final String COMPLIANCE = "Compliance";
    private static final String CLASSIFICATION = "Classification";
    private static final String BUSINESS_DOMAIN = "Business Domain";
    private static final String DATA_TYPE = "Data Type";

    private static final List<LabelRule> RULES = List.of(
            LabelRule.keywords("pii.explicit", "PII", SENSITIVITY, 0.95, ANY_FIELD,
                    "Names or describes personal data explicitly",
                    "pii", "personal data", "personal information", "personally identifiable",
                    "personally identifiable information"),
            LabelRule.keywords("pii.government-id", "PII", SENSITIVITY, 0.92, ANY_FIELD,
                    "Mentions a government identifier or date of birth",
                    "ssn", "social security", "passport", "drivers license", "driver license",
                    "driving licence", "national id", "tax id", "taxpayer id", "date of birth", "dob"),
            LabelRule.regex("pii.ssn-pattern", "PII", SENSITIVITY, 0.90, ANY_FIELD,
                    "Contains a value shaped like a US social security number",
                    "\\b\\d{3}-\\d{2}-\\d{4}\\b"),
            LabelRule.keywords("pii.contact", "PII", SENSITIVITY, 0.85, NAME_AND_PATH,
                    "Named for contact details of a person",
                    "email", "e mail", "phone", "telephone", "mobile number", "home address",
                    "street address", "mailing address", "postal address"),
            LabelRule.keywords("pii.hr-record", "PII", SENSITIVITY, 0.80, NAME_AND_PATH,
                    "Named for employee or applicant records",
                    "payroll", "salary", "salaries", "employee", "personnel", "applicant", "hr"),

            LabelRule.keywords("phi.explicit", "PHI", COMPLIANCE, 0.95, ANY_FIELD,
                    "Names or describes protected health information explicitly",
                    "phi", "protected health information", "hipaa"),
            LabelRule.keywords("phi.clinical", "PHI", COMPLIANCE, 0.88, NAME_AND_PATH,
                    "Named for clinical or patient records",
                    "patient", "medical record", "health record", "diagnosis", "diagnoses",
                    "prescription", "clinical", "ehr", "emr", "lab result", "icd10"),

            LabelRule.keywords("financial.payment-card", "FINANCIAL", COMPLIANCE, 0.92, ANY_FIELD,
                    "Mentions payment card or bank account fields",
                    "credit card", "debit card", "card number", "cardholder", "cvv", "iban",
                    "routing number", "bank account"),
            LabelRule.keywords("financial.records", "FINANCIAL", COMPLIANCE, 0.82, NAME_AND_PATH,
                    "Named for financial records",
                    "invoice", "payment", "billing", "ledger", "general ledger", "payroll",
                    "bank statement", "tax return", "accounts payable", "accounts receivable", "expense"),

            LabelRule.keywords("confidential.explicit", "CONFIDENTIAL", CLASSIFICATION, 0.90, ANY_FIELD,
                    "Marked confidential, secret or restricted",
                    "confidential", "secret", "restricted", "proprietary", "privileged", "trade secret"),
            LabelRule.keywords("confidential.credentials", "CONFIDENTIAL", CLASSIFICATION, 0.92,
                    NAME_AND_PATH, "Named for credentials or key material",
                    "password", "passwd", "credential", "api key", "apikey", "access key",
                    "secret key", "private key", "keystore", "truststore"),
            LabelRule.regex("confidential.key-file", "CONFIDENTIAL", CLASSIFICATION, 0.95, NAME_AND_PATH,
                    "Key, certificate store or environment file by extension or name",
                    "(?i)(\\.(pem|p12|pfx|jks|keystore)$|(^|[/\\\\])id_(rsa|ed25519|ecdsa)$"
                            + "|(^|[/\\\\])\\.env(\\.[\\w-]+)?$)"),
            LabelRule.regex("confidential.embedded-secret", "CONFIDENTIAL", CLASSIFICATION, 0.98,
                    METADATA_ONLY, "Metadata carries an access key id or a private key header",
                    "\\bAKIA[0-9A-Z]{16}\\b|-----BEGIN [A-Z ]*PRIVATE KEY-----"),

            LabelRule.keywords("customer.data", "CUSTOMER_DATA", BUSINESS_DOMAIN, 0.85, NAME_AND_PATH,
                    "Named for customer records",
                    "customer", "crm", "subscriber"),
            LabelRule.keywords("sales.data", "SALES_DATA", BUSINESS_DOMAIN, 0.85, NAME_AND_PATH,
                    "Named for sales records",
                    "sales", "order", "opportunity", "opportunities", "booking", "quota"),
            LabelRule.keywords("marketing.data", "MARKETING_DATA", BUSINESS_DOMAIN, 0.85, NAME_AND_PATH,
                    "Named for marketing activity",
                    "marketing", "campaign", "newsletter", "utm", "adwords"),
            LabelRule.keywords("inventory.data", "INVENTORY_DATA", BUSINESS_DOMAIN, 0.85, NAME_AND_PATH,
                    "Named for inventory or logistics records",
                    "inventory", "inventories", "warehouse", "sku", "stock level", "shipment"),
            LabelRule.keywords("analytics.data", "ANALYTICS", BUSINESS_DOMAIN, 0.65, NAME_AND_PATH,
                    "Named for analytics output (weak signal, routed to review)",
                    "analytics", "dashboard", "kpi", "metric"),

            LabelRule.keywords("ml.training", "ML_TRAINING", DATA_TYPE, 0.88, NAME_AND_PATH,
                    "Named for machine-learning training material",
                    "training data", "training set", "training dataset", "ml training",
                    "model training", "feature store", "embedding", "fine tuning", "finetune",
                    "model artifact"),
            LabelRule.keywords("log.data", "LOG_DATA", DATA_TYPE, 0.85, NAME_AND_PATH,
                    "Named for log output",
                    "log", "syslog", "cloudtrail", "stackdriver"));

    private DefaultLabelRules() {
    }

    /**
     * Returns the shipped rules in evaluation order. Order breaks ties between rules
     * that propose the same label at the same confidence.
     *
     * @return an immutable list of rules; never {@code null} or empty
     */
    public static List<LabelRule> all() {
        return RULES;
    }
}
