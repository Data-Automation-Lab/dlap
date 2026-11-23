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
# This script creates separate databases for each microservice, and the two platform roles that
# monitoring and backup use (SEC-12, Q-176, Q-247, Q-297, Q-449, Q-455).
#
# Passwords come from the environment of the PostgreSQL container, one variable per role:
#
#   <DATABASE>_DB_PASSWORD   the service user <database>_user: da_catalog -> DA_CATALOG_DB_PASSWORD,
#                            dlap_common -> DLAP_COMMON_DB_PASSWORD, da_admin -> DA_ADMIN_DB_PASSWORD
#   DLAP_MONITOR_DB_PASSWORD the role dlap_monitor: member of pg_monitor, nothing else. The Postgres
#                            exporter connects as this role, never as the superuser (Q-247).
#   DLAP_BACKUP_DB_PASSWORD  the role dlap_backup: REPLICATION and pg_read_all_data, no superuser. The
#                            backup agent connects as this role (Q-297).
#
# A variable that is unset or empty falls back to the published development value <name>_pass
# (da_catalog_pass, dlap_monitor_pass, ...) so that the quickstart boots with no setup. Those values are
# public: the prod boot guard of every service (DevSecretGuard) refuses them, and the pilot and demo
# launchers refuse to start without real ones. Generate real ones with ../gen-db-passwords.sh.
#
# This script runs only when the data directory is empty. An existing database is moved forward with
# ../upgrades/sync-database-roles.sh, which creates the two platform roles and sets the passwords.
#
# No password is ever written to the output, to a command line or to the server log: it travels in the
# environment of the one psql process and is read there with \getenv, and the session turns statement
# logging off first (the dev database runs with log_statement=ddl, which would log a CREATE USER).

set -e
set -u

# The environment variable that holds the password of a database's user: da_catalog becomes
# DA_CATALOG_DB_PASSWORD.
function password_variable() {
    local name="${1^^}"
    printf '%s_DB_PASSWORD' "${name//[^A-Z0-9]/_}"
}

# resolve_password <variable> <development fallback>: the value of the variable, or the fallback when it
# is unset or empty.
function resolve_password() {
    local variable=$1
    local fallback=$2
    if [ -n "${!variable:-}" ]; then
        printf '%s' "${!variable}"
    else
        printf '%s' "$fallback"
    fi
}

# password_source <variable>: where a password came from, for the log. Never the value.
function password_source() {
    local variable=$1
    if [ -n "${!variable:-}" ]; then
        printf 'from %s' "$variable"
    else
        printf 'the published development default; set %s for anything but a laptop' "$variable"
    fi
}

function create_user_and_database() {
    local database=$1
    local user=$2
    local password=$3
    local source=${4:-the caller}
    # Schema name: per-service DBs are da_<svc> -> dlap_<svc>; the shared
    # dlap_common DB keeps schema dlap_common (not dlap_dlap_common).
    local schema="dlap_${database#da_}"
    if [ "$database" = "dlap_common" ]; then schema="dlap_common"; fi
    echo "Creating user '$user' (password $source) and database '$database' (schema '$schema')..."

    DLAP_INIT_PASSWORD="$password" psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
        SET log_statement = 'none';
        SET log_min_error_statement = 'panic';
        \getenv init_pw DLAP_INIT_PASSWORD
        CREATE USER $user WITH PASSWORD :'init_pw';
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

# The two platform roles (SEC-12). dlap_monitor reads the statistics views through pg_monitor and nothing
# else. dlap_backup streams WAL and takes base backups (REPLICATION), dumps every database
# (pg_read_all_data) and tunes max_slot_wal_keep_size (ALTER SYSTEM on that one parameter, which
# deployment/dr/agent.sh does when the server has no limit). It also needs EXECUTE on three built-in
# functions the agent calls, which PostgreSQL reserves to superusers. Function privileges are per
# database, so they are granted in template1 (every database created later inherits them) and in
# postgres, which the agent connects to.
function create_platform_roles() {
    local monitor_password backup_password
    monitor_password="$(resolve_password DLAP_MONITOR_DB_PASSWORD dlap_monitor_pass)"
    backup_password="$(resolve_password DLAP_BACKUP_DB_PASSWORD dlap_backup_pass)"
    echo "Creating role 'dlap_monitor' (pg_monitor; password $(password_source DLAP_MONITOR_DB_PASSWORD))" \
        "and role 'dlap_backup' (REPLICATION, pg_read_all_data; password $(password_source DLAP_BACKUP_DB_PASSWORD))..."

    DLAP_INIT_MONITOR_PASSWORD="$monitor_password" DLAP_INIT_BACKUP_PASSWORD="$backup_password" \
        psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
        SET log_statement = 'none';
        SET log_min_error_statement = 'panic';
        \getenv monitor_pw DLAP_INIT_MONITOR_PASSWORD
        \getenv backup_pw DLAP_INIT_BACKUP_PASSWORD
        CREATE ROLE dlap_monitor LOGIN PASSWORD :'monitor_pw' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
        GRANT pg_monitor TO dlap_monitor;
        CREATE ROLE dlap_backup LOGIN PASSWORD :'backup_pw' REPLICATION NOSUPERUSER NOCREATEDB NOCREATEROLE;
        GRANT pg_read_all_data TO dlap_backup;
        -- ALTER SYSTEM privileges on a single parameter exist from PostgreSQL 15.
        DO \$\$
        BEGIN
            IF current_setting('server_version_num')::int >= 150000 THEN
                EXECUTE 'GRANT ALTER SYSTEM ON PARAMETER max_slot_wal_keep_size TO dlap_backup';
            END IF;
        END
        \$\$;
EOSQL

    local database
    for database in template1 postgres; do
        psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$database" <<-EOSQL
        GRANT EXECUTE ON FUNCTION pg_control_system(), pg_control_checkpoint(), pg_reload_conf() TO dlap_backup;
EOSQL
    done
}

# Single source of truth (ADR-0037): the list in POSTGRES_MULTIPLE_DATABASES is
# authoritative. Each entry <db> gets a matching user <db>_user, whose password is the variable
# <DB>_DB_PASSWORD (published default <db>_pass), and a schema (dlap_<svc> for da_<svc>; dlap_common keeps
# its own name). Compose supplies the canonical list; do not hardcode a second copy here (that was the
# drift trap).
if [ -n "${POSTGRES_MULTIPLE_DATABASES:-}" ]; then
    echo "Multiple database creation requested: $POSTGRES_MULTIPLE_DATABASES"

    create_platform_roles

    IFS=',' read -ra _DBS <<< "$POSTGRES_MULTIPLE_DATABASES"
    for _db in "${_DBS[@]}"; do
        _db="$(echo "$_db" | xargs)"   # trim surrounding whitespace
        [ -z "$_db" ] && continue
        _variable="$(password_variable "$_db")"
        create_user_and_database "$_db" "${_db}_user" "$(resolve_password "$_variable" "${_db}_pass")" \
            "$(password_source "$_variable")"
    done

    # Optional dedicated Keycloak database for the production KC-on-Postgres path
    # (deployment/PRODUCTION.md). The dev/quickstart Keycloak uses KC_DB=dev-file and
    # needs no Postgres DB, so this is skipped unless KEYCLOAK_DB_NAME is set.
    if [ -n "${KEYCLOAK_DB_NAME:-}" ]; then
        create_user_and_database "$KEYCLOAK_DB_NAME" \
            "${KEYCLOAK_DB_USER:-keycloak}" "${KEYCLOAK_DB_PASSWORD:-keycloak}" \
            "$(password_source KEYCLOAK_DB_PASSWORD)"
    fi

    echo "Multiple databases created successfully"
fi
