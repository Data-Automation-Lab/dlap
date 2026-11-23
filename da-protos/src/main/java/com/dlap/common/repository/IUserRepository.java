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

package com.dlap.common.repository;

import com.dlap.common.model.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for the {@link User} entity.
 * 
 * Provides standard CRUD operations and custom query methods for user management.
 * 
 * @author DLAP Team
 * @version 1.0
 * @since 1.0
 */
@Repository
public interface IUserRepository extends JpaRepository<User, UUID> {

    /**
     * Find user by Keycloak subject ID
     * 
     * @param keycloakSubjectId the Keycloak subject ID
     * @return optional user
     */
    Optional<User> findByKeycloakSubjectId(String keycloakSubjectId);

    /**
     * Find user by email address
     * 
     * @param email the email address
     * @return optional user
     */
    Optional<User> findByEmail(String email);

    /**
     * Find users by role
     * 
     * @param role the role to search for
     * @param pageable pagination information
     * @return page of users with the specified role
     */
    // roles is a Postgres text[] (basic array, not a JPA collection), so JPQL
    // `MEMBER OF` is invalid here; use the native array-contains operator.
    @Query(value = "SELECT * FROM dlap_common.dlap_users WHERE :role = ANY(roles)",
           countQuery = "SELECT count(*) FROM dlap_common.dlap_users WHERE :role = ANY(roles)",
           nativeQuery = true)
    Page<User> findByRole(@Param("role") String role, Pageable pageable);

    /**
     * Find users by department
     * 
     * @param department the department
     * @param pageable pagination information
     * @return page of users in the specified department
     */
    Page<User> findByDepartment(String department, Pageable pageable);

    /**
     * Find users by job title
     * 
     * @param jobTitle the job title
     * @param pageable pagination information
     * @return page of users with the specified job title
     */
    Page<User> findByJobTitle(String jobTitle, Pageable pageable);

    /**
     * Search users by full name containing the given text (case-insensitive)
     * 
     * @param namePattern the pattern to search for
     * @param pageable pagination information
     * @return page of users matching the name pattern
     */
    Page<User> findByFullNameContainingIgnoreCase(String namePattern, Pageable pageable);

    /**
     * Find all admin users
     * 
     * @return list of admin users
     */
    @Query(value = "SELECT * FROM dlap_common.dlap_users WHERE 'ADMIN' = ANY(roles)", nativeQuery = true)
    List<User> findAllAdmins();

    /**
     * Find all data engineers
     * 
     * @return list of data engineer users
     */
    @Query(value = "SELECT * FROM dlap_common.dlap_users WHERE 'DATA_ENGINEER' = ANY(roles)", nativeQuery = true)
    List<User> findAllDataEngineers();

    /**
     * Check if user exists by Keycloak subject ID
     * 
     * @param keycloakSubjectId the Keycloak subject ID
     * @return true if user exists, false otherwise
     */
    boolean existsByKeycloakSubjectId(String keycloakSubjectId);

    /**
     * Check if user exists by email
     * 
     * @param email the email address
     * @return true if user exists, false otherwise
     */
    boolean existsByEmail(String email);

    /**
     * Count users by role
     * 
     * @param role the role to count
     * @return number of users with the specified role
     */
    @Query(value = "SELECT count(*) FROM dlap_common.dlap_users WHERE :role = ANY(roles)", nativeQuery = true)
    long countByRole(@Param("role") String role);
} 