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

package com.dlap.discovery.crawler.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import com.dlap.discovery.common.util.SecurityUtils;

@Component
public class CrawlerJwtGrantedAuthorityConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    public CrawlerJwtGrantedAuthorityConverter() {
        // Bean extracting authority.
    }

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        List<GrantedAuthority> realmAuthorities = extractRealmAccessRoles(jwt);
        if (!realmAuthorities.isEmpty()) {
            return realmAuthorities;
        }
        return SecurityUtils.extractAuthorityFromClaims(jwt.getClaims());
    }

    private static List<GrantedAuthority> extractRealmAccessRoles(Jwt jwt) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        Object claim = jwt.getClaim("realm_access");
        if (!(claim instanceof Map<?, ?> realmAccess)) {
            return authorities;
        }
        Object rolesObj = realmAccess.get("roles");
        if (!(rolesObj instanceof Collection<?> roles)) {
            return authorities;
        }
        for (Object role : roles) {
            if (role == null) {
                continue;
            }
            String name = role.toString();
            authorities.add(new SimpleGrantedAuthority(name.startsWith("ROLE_") ? name : "ROLE_" + name));
        }
        return authorities;
    }
}
