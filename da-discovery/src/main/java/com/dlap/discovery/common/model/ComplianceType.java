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

package com.dlap.discovery.common.model;

/**
 * Represents compliance standards applicable to a CloudResource.
 */
// TODO: UI should read supported compliance types from the database
public enum ComplianceType {
	GDPR, // General Data Protection Regulation
	PII, // Personally Identifiable Information
	HIPAA, // Health Insurance Portability and Accountability Act
	CCPA, // California Consumer Privacy Act
	SOX, // Sarbanes-Oxley Act
	PCI_DSS, // Payment Card Industry Data Security Standard
	FERPA, // Family Educational Rights and Privacy Act
	GLBA, // Gramm-Leach-Bliley Act
	FISMA, // Federal Information Security Management Act
	ISO_27001, // International Organization for Standardization 27001
	NIST_800_53, // National Institute of Standards and Technology Special Publication 800-53
	FedRAMP, // Federal Risk and Authorization Management Program
	COPPA, // Children's Online Privacy Protection Act
	HITECH, // Health Information Technology for Economic and Clinical Health Act
	PHI, // Protected Health Information
	PIPEDA, // Personal Information Protection and Electronic Documents Act (Canada)
	BASEL_III, // Basel III banking regulations
	FINRA, // Financial Industry Regulatory Authority
	SEC_17A_4, // Securities and Exchange Commission Rule 17a-4
	FFIEC, // Federal Financial Institutions Examination Council
	AML, // Anti-Money Laundering
	KYC, // Know Your Customer
	FHIR, // Fast Healthcare Interoperability Resources
	MIFID_II // Markets in Financial Instruments Directive II
}