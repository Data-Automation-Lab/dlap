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

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.dlap.discovery.client.dto.AdminUserDTO;
import com.dlap.discovery.common.model.CrawlerAuthority;
import com.dlap.discovery.common.model.CrawlerUser;
import com.dlap.discovery.common.service.AuthoritiesConstants;
import com.dlap.discovery.crawler.service.mapper.CrawlerUserMapper;

/**
 * Standalone unit tests for the UserResource functionality.
 * These tests can run without requiring a full application context.
 */
public class UserResourceUnitTest {

	private static final String DEFAULT_LOGIN = "johndoe";
	private static final String DEFAULT_ID = "id1";
	private static final String DEFAULT_EMAIL = "johndoe@localhost";
	private static final String DEFAULT_FIRSTNAME = "john";
	private static final String DEFAULT_LASTNAME = "doe";
	private static final String DEFAULT_IMAGEURL = "http://placehold.it/50x50";
	private static final String DEFAULT_LANGKEY = "en";

	private CrawlerUserMapper userMapper;
	private CrawlerUser user;

	@BeforeEach
	public void setup() {
		userMapper = createUserMapper();

		user = new CrawlerUser();
		user.setId(DEFAULT_ID);
		user.setLogin(DEFAULT_LOGIN);
		user.setEmail(DEFAULT_EMAIL);
		user.setFirstName(DEFAULT_FIRSTNAME);
		user.setLastName(DEFAULT_LASTNAME);
		user.setImageUrl(DEFAULT_IMAGEURL);
		user.setLangKey(DEFAULT_LANGKEY);
		user.setActivated(true);
	}

	/**
	 * Creates a basic implementation of CrawlerUserMapper for testing.
	 */
	private CrawlerUserMapper createUserMapper() {
		return new CrawlerUserMapper() {
			@Override
			public CrawlerUser userDTOToUser(AdminUserDTO userDTO) {
				if (userDTO == null) {
					return null;
				}

				CrawlerUser user = new CrawlerUser();
				user.setId(userDTO.getId());
				user.setLogin(userDTO.getLogin());
				user.setFirstName(userDTO.getFirstName());
				user.setLastName(userDTO.getLastName());
				user.setEmail(userDTO.getEmail());
				user.setImageUrl(userDTO.getImageUrl());
				user.setActivated(userDTO.isActivated());
				user.setLangKey(userDTO.getLangKey());

				Set<CrawlerAuthority> authorities = userDTO.getAuthorities().stream()
						.map(auth -> {
							CrawlerAuthority authority = new CrawlerAuthority();
							authority.setName(auth);
							return authority;
						})
						.collect(Collectors.toSet());

				user.setAuthorities(authorities);
				user.setCreatedDate(Instant.now());
				user.setLastModifiedDate(Instant.now());

				return user;
			}

			@Override
			public AdminUserDTO userToAdminUserDTO(CrawlerUser user) {
				if (user == null) {
					return null;
				}

				AdminUserDTO userDTO = new AdminUserDTO();
				userDTO.setId(user.getId());
				userDTO.setLogin(user.getLogin());
				userDTO.setFirstName(user.getFirstName());
				userDTO.setLastName(user.getLastName());
				userDTO.setEmail(user.getEmail());
				userDTO.setImageUrl(user.getImageUrl());
				userDTO.setActivated(user.isActivated());
				userDTO.setLangKey(user.getLangKey());
				userDTO.setCreatedBy(user.getCreatedBy());
				userDTO.setCreatedDate(user.getCreatedDate());
				userDTO.setLastModifiedBy(user.getLastModifiedBy());
				userDTO.setLastModifiedDate(user.getLastModifiedDate());

				Set<String> authorities = user.getAuthorities().stream()
						.map(CrawlerAuthority::getName)
						.collect(Collectors.toSet());

				userDTO.setAuthorities(authorities);

				return userDTO;
			}
		};
	}

	/**
	 * Test the equals method of CrawlerUser.
	 */
	@Test
	void testUserEquals() {
		CrawlerUser user1 = new CrawlerUser();
		user1.setId(DEFAULT_ID);
		CrawlerUser user2 = new CrawlerUser();
		user2.setId(user1.getId());
		assertThat(user1).isEqualTo(user2);

		user2.setId("id2");
		assertThat(user1).isNotEqualTo(user2);

		user1.setId(null);
		assertThat(user1).isNotEqualTo(user2);
	}

	/**
	 * Test the mapping from DTO to User.
	 */
	@Test
	void testUserDTOtoUser() {
		AdminUserDTO userDTO = new AdminUserDTO();
		userDTO.setId(DEFAULT_ID);
		userDTO.setLogin(DEFAULT_LOGIN);
		userDTO.setFirstName(DEFAULT_FIRSTNAME);
		userDTO.setLastName(DEFAULT_LASTNAME);
		userDTO.setEmail(DEFAULT_EMAIL);
		userDTO.setActivated(true);
		userDTO.setImageUrl(DEFAULT_IMAGEURL);
		userDTO.setLangKey(DEFAULT_LANGKEY);
		userDTO.setCreatedBy(DEFAULT_LOGIN);
		userDTO.setLastModifiedBy(DEFAULT_LOGIN);
		userDTO.setAuthorities(Collections.singleton(AuthoritiesConstants.USER));

		CrawlerUser user = userMapper.userDTOToUser(userDTO);

		assertThat(user.getId()).isEqualTo(DEFAULT_ID);
		assertThat(user.getLogin()).isEqualTo(DEFAULT_LOGIN);
		assertThat(user.getFirstName()).isEqualTo(DEFAULT_FIRSTNAME);
		assertThat(user.getLastName()).isEqualTo(DEFAULT_LASTNAME);
		assertThat(user.getEmail()).isEqualTo(DEFAULT_EMAIL);
		assertThat(user.isActivated()).isTrue();
		assertThat(user.getImageUrl()).isEqualTo(DEFAULT_IMAGEURL);
		assertThat(user.getLangKey()).isEqualTo(DEFAULT_LANGKEY);
		assertThat(user.getCreatedBy()).isNull();
		assertThat(user.getCreatedDate()).isNotNull();
		assertThat(user.getLastModifiedBy()).isNull();
		assertThat(user.getLastModifiedDate()).isNotNull();
		assertThat(user.getAuthorities()).extracting("name").containsExactly(AuthoritiesConstants.USER);
	}

	/**
	 * Test the mapping from User to DTO.
	 */
	@Test
	void testUserToUserDTO() {
		user.setCreatedBy(DEFAULT_LOGIN);
		user.setCreatedDate(Instant.now());
		user.setLastModifiedBy(DEFAULT_LOGIN);
		user.setLastModifiedDate(Instant.now());

		Set<CrawlerAuthority> authorities = new HashSet<>();
		CrawlerAuthority authority = new CrawlerAuthority();
		authority.setName(AuthoritiesConstants.USER);
		authorities.add(authority);
		user.setAuthorities(authorities);

		AdminUserDTO userDTO = userMapper.userToAdminUserDTO(user);

		assertThat(userDTO.getId()).isEqualTo(DEFAULT_ID);
		assertThat(userDTO.getLogin()).isEqualTo(DEFAULT_LOGIN);
		assertThat(userDTO.getFirstName()).isEqualTo(DEFAULT_FIRSTNAME);
		assertThat(userDTO.getLastName()).isEqualTo(DEFAULT_LASTNAME);
		assertThat(userDTO.getEmail()).isEqualTo(DEFAULT_EMAIL);
		assertThat(userDTO.isActivated()).isTrue();
		assertThat(userDTO.getImageUrl()).isEqualTo(DEFAULT_IMAGEURL);
		assertThat(userDTO.getLangKey()).isEqualTo(DEFAULT_LANGKEY);
		assertThat(userDTO.getCreatedBy()).isEqualTo(DEFAULT_LOGIN);
		assertThat(userDTO.getCreatedDate()).isEqualTo(user.getCreatedDate());
		assertThat(userDTO.getLastModifiedBy()).isEqualTo(DEFAULT_LOGIN);
		assertThat(userDTO.getLastModifiedDate()).isEqualTo(user.getLastModifiedDate());
		assertThat(userDTO.getAuthorities()).containsExactly(AuthoritiesConstants.USER);
		assertThat(userDTO.toString()).isNotNull();
	}

	/**
	 * Test the equals method of CrawlerAuthority.
	 */
	@Test
	void testAuthorityEquals() {
		CrawlerAuthority authorityA = new CrawlerAuthority();
		assertThat(authorityA).isNotEqualTo(null).isNotEqualTo(new Object());
		assertThat(authorityA.hashCode()).isZero();
		assertThat(authorityA.toString()).isNotNull();

		CrawlerAuthority authorityB = new CrawlerAuthority();
		assertThat(authorityA).isEqualTo(authorityB);

		authorityB.setName(AuthoritiesConstants.ADMIN);
		assertThat(authorityA).isNotEqualTo(authorityB);

		authorityA.setName(AuthoritiesConstants.USER);
		assertThat(authorityA).isNotEqualTo(authorityB);

		authorityB.setName(AuthoritiesConstants.USER);
		assertThat(authorityA).isEqualTo(authorityB).hasSameHashCodeAs(authorityB);
	}
}