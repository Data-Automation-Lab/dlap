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

package com.dlap.discovery.crawler.web.rest;

import static org.assertj.core.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.dlap.discovery.common.model.CrawlerUser;

/**
 * Basic unit tests for user functionality without Spring context.
 */
class PublicUserResourceIT {

    private static final String DEFAULT_LOGIN = "johndoe";

    private CrawlerUser user;
    private List<CrawlerUser> userList;

    /**
     * Create a test user.
     */
    private CrawlerUser createTestUser() {
        CrawlerUser user = new CrawlerUser();
        user.setLogin(DEFAULT_LOGIN);
        user.setEmail(DEFAULT_LOGIN + "@localhost");
        user.setActivated(true);
        user.setFirstName("John");
        user.setLastName("Doe");
        user.setImageUrl("http://placehold.it/50x50");
        user.setLangKey("en");
        return user;
    }

    @BeforeEach
    public void initTest() {
        user = createTestUser();
        userList = new ArrayList<>();
        userList.add(user);
    }

    @Test
    void testUserBasicProperties() {
        // Very basic sanity test on the user object
        assertThat(user.getLogin()).isEqualTo(DEFAULT_LOGIN);
        assertThat(user.getEmail()).isEqualTo(DEFAULT_LOGIN + "@localhost");
        assertThat(user.isActivated()).isTrue();

        // Test user list functionality
        assertThat(userList).hasSize(1);
        assertThat(userList).contains(user);

        // Create another user with different login
        CrawlerUser user2 = createTestUser();
        user2.setLogin("janedoe");

        // Verify users are different
        assertThat(user).isNotEqualTo(user2);

        // Add to list and verify
        userList.add(user2);
        assertThat(userList).hasSize(2);
    }
}
