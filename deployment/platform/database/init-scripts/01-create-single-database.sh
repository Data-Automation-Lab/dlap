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

# DLAP Single Database Creation Script
# This script creates a database for a single microservice
# Usage: ./01-create-single-database.sh <service_name>

set -e
set -u

# Function to create user and database for a single service
create_single_user_and_database() {
    local service_name="$1"
    local database=""
    local user=""
    local password=""
    
    # Map service names to database configuration
    case "$service_name" in
        "da-discovery")
            database="da_discovery"
            user="da_discovery_user"
            password="${DA_DISCOVERY_DB_PASSWORD:-da_discovery_pass}"
            ;;
        "da-catalog")
            database="da_catalog"
            user="da_catalog_user"
            password="${DA_CATALOG_DB_PASSWORD:-da_catalog_pass}"
            ;;
        "da-policyengine")
            database="da_policyengine"
            user="da_policyengine_user"
            password="${DA_POLICYENGINE_DB_PASSWORD:-da_policyengine_pass}"
            ;;
        "da-reporting")
            database="da_reporting"
            user="da_reporting_user"
            password="${DA_REPORTING_DB_PASSWORD:-da_reporting_pass}"
            ;;
        "da-autolabel")
            database="da_autolabel"
            user="da_autolabel_user"
            password="${DA_AUTOLABEL_DB_PASSWORD:-da_autolabel_pass}"
            ;;
        "da-autoarchival")
            database="da_autoarchival"
            user="da_autoarchival_user"
            password="${DA_AUTOARCHIVAL_DB_PASSWORD:-da_autoarchival_pass}"
            ;;
        "da-autodelete")
            database="da_autodelete"
            user="da_autodelete_user"
            password="${DA_AUTODELETE_DB_PASSWORD:-da_autodelete_pass}"
            ;;
        "da-autocompliance")
            database="da_autocompliance"
            user="da_autocompliance_user"
            password="${DA_AUTOCOMPLIANCE_DB_PASSWORD:-da_autocompliance_pass}"
            ;;
        "da-admin-service")
            database="da_admin"
            user="da_admin_user"
            password="${DA_ADMIN_DB_PASSWORD:-da_admin_pass}"
            ;;
        *)
            echo "Error: Unknown service '$service_name'"
            echo "Supported services: da-discovery, da-catalog, da-policyengine, da-reporting, da-autolabel, da-autoarchival, da-autodelete, da-autocompliance, da-admin-service"
            exit 1
            ;;
    esac
    
    echo "Creating database '$database' and user '$user' for service '$service_name'..."
    
    # Check if database already exists
    if psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" -tAc "SELECT 1 FROM pg_database WHERE datname='$database';" | grep -q 1; then
        echo "Database '$database' already exists for service '$service_name'"
        return 0
    fi
    
    # Create user and database
    # The password travels in the environment of the psql process (SEC-12), not in the SQL text or on the
    # command line, and the session keeps it out of the server log.
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
        CREATE SCHEMA IF NOT EXISTS dlap_${service_name#da-};
        GRANT ALL ON SCHEMA dlap_${service_name#da-} TO $user;
        GRANT ALL ON SCHEMA public TO $user;
        
        -- Grant permissions on future tables
        ALTER DEFAULT PRIVILEGES IN SCHEMA dlap_${service_name#da-} GRANT ALL ON TABLES TO $user;
        ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO $user;
        ALTER DEFAULT PRIVILEGES IN SCHEMA dlap_${service_name#da-} GRANT ALL ON SEQUENCES TO $user;
        ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO $user;
EOSQL
    
    echo "Database '$database' and user '$user' created successfully for service '$service_name'"
}

# Main execution
#
# This is a MANUAL single-database utility. When the Postgres entrypoint
# auto-runs the init-scripts dir it invokes this with no arguments - in
# the multi-DB dev setup that work is already done by
# 00-create-multiple-databases.sh, so a no-arg run must be a harmless
# no-op. It must NOT `exit`: the entrypoint *sources* non-executable .sh
# files, so an `exit` here would terminate the whole init sequence and
# skip 02-create-common-schema.sql / 03-insert-test-data.sql.
if [ $# -eq 0 ]; then
    echo "01-create-single-database.sh: no service name given; skipping"
    echo "  (multi-DB init is handled by 00-create-multiple-databases.sh;"
    echo "   run this manually as '$0 <service_name>' for single-DB setups)."
else
    SERVICE_NAME="$1"
    create_single_user_and_database "$SERVICE_NAME"
fi