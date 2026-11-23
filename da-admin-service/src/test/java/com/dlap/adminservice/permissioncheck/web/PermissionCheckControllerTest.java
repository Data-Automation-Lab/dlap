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

package com.dlap.adminservice.permissioncheck.web;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.dlap.adminservice.config.TestSecurityConfiguration;
import com.dlap.adminservice.permissioncheck.PermissionCheckReport;
import com.dlap.adminservice.permissioncheck.PermissionCheckReport.IdentityBasis;
import com.dlap.adminservice.permissioncheck.PermissionCheckReport.Status;
import com.dlap.adminservice.permissioncheck.PermissionCheckReport.TargetReport;
import com.dlap.adminservice.permissioncheck.PermissionCheckService;

/** The permission self-check API: ADMIN only, the report shape, 404 for an unknown cloud. */
@WebMvcTest(PermissionCheckController.class)
@Import(TestSecurityConfiguration.class)
class PermissionCheckControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private PermissionCheckService service;

    private static PermissionCheckReport failing() {
        TargetReport t = new TargetReport("delete", "bucket", "arn:aws:s3:::dlap-data", "role", true,
                List.of("s3:GetBucketVersioning"), List.of("s3:GetBucketVersioning"), List.of("s3:PutBucketPolicy"),
                List.of(), null);
        return new PermissionCheckReport("aws", Status.FAIL, "iam:SimulatePrincipalPolicy", "role",
                IdentityBasis.NAMED_ROLE, "Evaluates the named Actor role.",
                List.of("delete: s3:GetBucketVersioning on arn:aws:s3:::dlap-data"),
                List.of("delete: s3:PutBucketPolicy on arn:aws:s3:::dlap-data"), List.of(t));
    }

    @Test
    void unauthenticatedCallsAreRefused() throws Exception {
        mvc.perform(get("/api/v1/permission-check")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    @WithMockUser(roles = {"CDO", "AUDITOR", "POLICY_MANAGER"})
    void everyRoleButAdminIsForbidden() throws Exception {
        mvc.perform(get("/api/v1/permission-check")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/permission-check/aws")).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void anAdminReadsTheMissingAndExtraPermissionsOfACloud() throws Exception {
        given(service.check("aws")).willReturn(Optional.of(failing()));
        mvc.perform(get("/api/v1/permission-check/aws"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cloud").value("aws"))
                .andExpect(jsonPath("$.status").value("FAIL"))
                .andExpect(jsonPath("$.method").value("iam:SimulatePrincipalPolicy"))
                .andExpect(jsonPath("$.identityChecked").value("role"))
                .andExpect(jsonPath("$.identityBasis").value("NAMED_ROLE"))
                .andExpect(jsonPath("$.identityNote").value("Evaluates the named Actor role."))
                .andExpect(jsonPath("$.missing[0]").value("delete: s3:GetBucketVersioning on arn:aws:s3:::dlap-data"))
                .andExpect(jsonPath("$.extra[0]").value("delete: s3:PutBucketPolicy on arn:aws:s3:::dlap-data"))
                .andExpect(jsonPath("$.targets[0].namedResource").value(true))
                .andExpect(jsonPath("$.targets[0].extra[0]").value("s3:PutBucketPolicy"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void anAdminChecksEveryCloudAndAnUnknownCloudIsNotFound() throws Exception {
        given(service.checkAll()).willReturn(List.of(failing(), new PermissionCheckReport("gcp", Status.NOT_CONFIGURED,
                "testIamPermissions", null, IdentityBasis.CALLER, "Answers for the caller.", List.of(), List.of(),
                List.of())));
        mvc.perform(get("/api/v1/permission-check"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("FAIL"))
                .andExpect(jsonPath("$[1].status").value("NOT_CONFIGURED"))
                .andExpect(jsonPath("$[1].identityBasis").value("CALLER"))
                .andExpect(jsonPath("$[1].identityChecked").doesNotExist());
        given(service.check("oracle")).willReturn(Optional.empty());
        mvc.perform(get("/api/v1/permission-check/oracle")).andExpect(status().isNotFound());
    }
}
