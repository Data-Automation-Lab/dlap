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

package com.dlap.discovery.crawler.service.mapper;

import java.util.*;
import java.util.stream.Collectors;

import com.dlap.discovery.client.dto.AdminUserDTO;
import com.dlap.discovery.client.dto.UserDTO;
import com.dlap.discovery.common.model.CrawlerAuthority;
import com.dlap.discovery.common.model.CrawlerUser;
import org.springframework.stereotype.Service;

/**
 * Mapper for the entity {@link CrawlerUser} and its DTO called {@link UserDTO}.
 *
 * Normal mappers are generated using MapStruct, this one is hand-coded as MapStruct
 * support is still in beta, and requires a manual step with an IDE.
 */
@Service
public class CrawlerUserMapper {

    public List<UserDTO> usersToUserDTOs(List<CrawlerUser> users) {
        return users.stream().filter(Objects::nonNull).map(this::userToUserDTO).toList();
    }

    public UserDTO userToUserDTO(CrawlerUser user) {
        return new UserDTO(user);
    }

    public List<AdminUserDTO> usersToAdminUserDTOs(List<CrawlerUser> users) {
        return users.stream().filter(Objects::nonNull).map(this::userToAdminUserDTO).toList();
    }

    public AdminUserDTO userToAdminUserDTO(CrawlerUser user) {
        return new AdminUserDTO(user);
    }

    public List<CrawlerUser> userDTOsToUsers(List<AdminUserDTO> userDTOs) {
        return userDTOs.stream().filter(Objects::nonNull).map(this::userDTOToUser).toList();
    }

    public CrawlerUser userDTOToUser(AdminUserDTO userDTO) {
        if (userDTO == null) {
            return null;
        } else {
            CrawlerUser user = new CrawlerUser();
            user.setId(userDTO.getId());
            user.setLogin(userDTO.getLogin());
            user.setFirstName(userDTO.getFirstName());
            user.setLastName(userDTO.getLastName());
            user.setEmail(userDTO.getEmail());
            user.setImageUrl(userDTO.getImageUrl());
            user.setActivated(userDTO.isActivated());
            user.setLangKey(userDTO.getLangKey());
            Set<CrawlerAuthority> authorities = this.authoritiesFromStrings(userDTO.getAuthorities());
            user.setAuthorities(authorities);
            return user;
        }
    }

    private Set<CrawlerAuthority> authoritiesFromStrings(Set<String> authoritiesAsString) {
        Set<CrawlerAuthority> authorities = new HashSet<>();

        if (authoritiesAsString != null) {
            authorities =
                authoritiesAsString
                    .stream()
                    .map(string -> {
                        CrawlerAuthority auth = new CrawlerAuthority();
                        auth.setName(string);
                        return auth;
                    })
                    .collect(Collectors.toSet());
        }

        return authorities;
    }

    public CrawlerUser userFromId(String id) {
        if (id == null) {
            return null;
        }
        CrawlerUser user = new CrawlerUser();
        user.setId(id);
        return user;
    }

    public UserDTO toDtoId(CrawlerUser user) {
        if (user == null) {
            return null;
        }
        UserDTO userDto = new UserDTO();
        userDto.setId(user.getId());
        return userDto;
    }

    public Set<UserDTO> toDtoIdSet(Set<CrawlerUser> users) {
        if (users == null) {
            return Collections.emptySet();
        }

        Set<UserDTO> userSet = new HashSet<>();
        for (CrawlerUser userEntity : users) {
            userSet.add(this.toDtoId(userEntity));
        }

        return userSet;
    }

    public UserDTO toDtoLogin(CrawlerUser user) {
        if (user == null) {
            return null;
        }
        UserDTO userDto = new UserDTO();
        userDto.setId(user.getId());
        userDto.setLogin(user.getLogin());
        return userDto;
    }

    public Set<UserDTO> toDtoLoginSet(Set<CrawlerUser> users) {
        if (users == null) {
            return Collections.emptySet();
        }

        Set<UserDTO> userSet = new HashSet<>();
        for (CrawlerUser userEntity : users) {
            userSet.add(this.toDtoLogin(userEntity));
        }

        return userSet;
    }
}
