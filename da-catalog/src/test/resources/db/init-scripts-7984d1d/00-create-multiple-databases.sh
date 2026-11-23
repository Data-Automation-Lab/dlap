#!/bin/bash

# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

# DLAP Multiple Database Creation Script
# This script creates separate databases for each microservice

set -e
set -u

function create_user_and_database() {
    local database=$1
    local user=$2
    local password=$3
    # Schema name: per-service DBs are da_<svc> -> dlap_<svc>; the shared
    # dlap_common DB keeps schema dlap_common (not dlap_dlap_common).
    local schema="dlap_${database#da_}"
    if [ "$database" = "dlap_common" ]; then schema="dlap_common"; fi
    echo "Creating user '$user' and database '$database' (schema '$schema')..."
    
    psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
        CREATE USER $user WITH PASSWORD '$password';
        CREATE DATABASE $database;
        GRANT ALL PRIVILEGES ON DATABASE $database TO $user;
        
        -- Connect to the new database and grant schema permissions
        \c $database;
        
        -- Create schemas
        CREATE SCHEMA IF NOT EXISTS ${schema};
        GRANT ALL ON SCHEMA ${schema} TO $user;
        GRANT ALL ON SCHEMA public TO $user;
        
        -- Grant permissions on future tables
        ALTER DEFAULT PRIVILEGES IN SCHEMA ${schema} GRANT ALL ON TABLES TO $user;
        ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO $user;
        ALTER DEFAULT PRIVILEGES IN SCHEMA ${schema} GRANT ALL ON SEQUENCES TO $user;
        ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO $user;
EOSQL
}

# Single source of truth (ADR-0037): the list in POSTGRES_MULTIPLE_DATABASES is
# authoritative. Each entry <db> gets a matching user <db>_user / password <db>_pass and a
# schema (dlap_<svc> for da_<svc>; dlap_common keeps its own name). Compose supplies the
# canonical list; do not hardcode a second copy here (that was the drift trap).
if [ -n "${POSTGRES_MULTIPLE_DATABASES:-}" ]; then
    echo "Multiple database creation requested: $POSTGRES_MULTIPLE_DATABASES"

    IFS=',' read -ra _DBS <<< "$POSTGRES_MULTIPLE_DATABASES"
    for _db in "${_DBS[@]}"; do
        _db="$(echo "$_db" | xargs)"   # trim surrounding whitespace
        [ -z "$_db" ] && continue
        create_user_and_database "$_db" "${_db}_user" "${_db}_pass"
    done

    # Optional dedicated Keycloak database for the production KC-on-Postgres path
    # (deployment/PRODUCTION.md). The dev/quickstart Keycloak uses KC_DB=dev-file and
    # needs no Postgres DB, so this is skipped unless KEYCLOAK_DB_NAME is set.
    if [ -n "${KEYCLOAK_DB_NAME:-}" ]; then
        create_user_and_database "$KEYCLOAK_DB_NAME" \
            "${KEYCLOAK_DB_USER:-keycloak}" "${KEYCLOAK_DB_PASSWORD:-keycloak}"
    fi

    echo "Multiple databases created successfully"
fi