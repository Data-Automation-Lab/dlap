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


# DLAP Development Environment Startup Script
# Sets dev-specific configurations and starts all microservices

set -e

# Colors for output
GREEN='\033[0;32m'
BLUE='\033[0;34m'
NC='\033[0m'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"

echo -e "${BLUE}🚀 Starting DLAP Development Environment${NC}"
echo ""

# Set environment name
export ENVIRONMENT="dev"

# Development-specific configurations
export STARTUP_MODE="docker"
export BUILD_FIRST="true"
export WAIT_FOR_HEALTH="true"
export PARALLEL_START="true"

# Spring configuration for development
export SPRING_PROFILES_ACTIVE="dev"

# Database configuration (Docker Compose services)
export DATABASE_URL="jdbc:postgresql://localhost:5432"
export DATABASE_USERNAME="dlap"
export DATABASE_PASSWORD="${DATABASE_PASSWORD:-}"

# Kafka configuration (Docker Compose services)
export KAFKA_BOOTSTRAP_SERVERS="${KAFKA_BOOTSTRAP_SERVERS:-localhost:9092}"

# Keycloak configuration (Docker Compose services)
export KEYCLOAK_AUTH_SERVER_URL="http://localhost:8180"
export KEYCLOAK_REALM="dlap-realm"

# Redis disabled for dev environment to reduce resource usage
# export REDIS_URL="redis://localhost:6379"

# Development-specific features
export DEBUG_MODE="true"
export LOG_LEVEL="DEBUG"
export DEV_TOOLS_ENABLED="true"

# LLM provider configuration for development (optional, can be overridden in environment)
export OPENAI_API_KEY="${OPENAI_API_KEY:-your-openai-api-key-here}"
export OLLAMA_BASE_URL="${OLLAMA_BASE_URL:-http://localhost:11434}"

# Load environment-specific variables if present
if [ -f "${SCRIPT_DIR}/.env.dev" ]; then
    echo -e "${BLUE}Loading dev environment variables from .env.dev${NC}"
    source "${SCRIPT_DIR}/.env.dev"
fi

# Check if Docker Compose infrastructure is running
check_infrastructure() {
    echo -e "${BLUE}🔍 Checking infrastructure services...${NC}"
    
    local services=("postgresql" "keycloak" "kafka")
    local failed_services=()
    
    for service in "${services[@]}"; do
        case $service in
            "postgresql")
                if ! nc -z localhost 5432 2>/dev/null; then
                    failed_services+=("PostgreSQL (port 5432)")
                fi
                ;;
            "keycloak")
                if ! nc -z localhost 8180 2>/dev/null; then
                    failed_services+=("Keycloak (port 8180)")
                fi
                ;;
            "kafka")
                if ! nc -z localhost 9092 2>/dev/null; then
                    failed_services+=("Kafka (port 9092)")
                fi
                ;;
                    # Redis removed from dev environment
        esac
    done
    
    if [ ${#failed_services[@]} -gt 0 ]; then
        echo -e "${GREEN}⚠️  Some infrastructure services are not running:${NC}"
        for service in "${failed_services[@]}"; do
            echo "   - $service"
        done
        echo ""
        echo "Starting infrastructure services with Docker Compose..."
        docker-compose -f "${SCRIPT_DIR}/docker-compose.yml" up -d
        
        echo "Waiting for infrastructure services to be ready..."
        sleep 10
    else
        echo -e "${GREEN}✅ All infrastructure services are running${NC}"
    fi
    echo ""
}

# Show configuration
show_config() {
    echo -e "${BLUE}📋 Development Configuration:${NC}"
    echo "  Environment: ${ENVIRONMENT}"
    echo "  Startup Mode: ${STARTUP_MODE}"
    echo "  Spring Profile: ${SPRING_PROFILES_ACTIVE}"
    echo "  Database: ${DATABASE_URL}"
    echo "  Kafka: ${KAFKA_BOOTSTRAP_SERVERS}"
    echo "  Keycloak: ${KEYCLOAK_AUTH_SERVER_URL}"
    echo "  Redis: Disabled in dev environment"
    echo "  Build First: ${BUILD_FIRST}"
    echo "  Parallel Start: ${PARALLEL_START}"
    echo ""
}

# Main execution
main() {
    local command="${1:-start}"
    
    case "$command" in
        "start")
            show_config
            check_infrastructure
            echo -e "${BLUE}🚀 Starting microservices...${NC}"
            ;;
        "start-infra-only")
            echo -e "${BLUE}🏗️  Starting infrastructure services only...${NC}"
            docker-compose -f "${SCRIPT_DIR}/docker-compose.yml" up -d
            echo -e "${GREEN}✅ Infrastructure services started${NC}"
            exit 0
            ;;
        "stop-all")
            echo -e "${BLUE}🛑 Stopping all services (infrastructure + microservices)...${NC}"
            "${PROJECT_ROOT}/deployment/scripts/start-microservices.sh" stop
            docker-compose -f "${SCRIPT_DIR}/docker-compose.yml" down
            echo -e "${GREEN}✅ All services stopped${NC}"
            exit 0
            ;;
        "--help"|"-h")
            echo "DLAP Development Environment Startup Script"
            echo ""
            echo "Usage: $0 [COMMAND]"
            echo ""
            echo "Commands:"
            echo "  start             Start infrastructure and microservices (default)"
            echo "  start-infra-only  Start only infrastructure services"
            echo "  stop-all          Stop all services including infrastructure"
            echo "  [other]           Pass through to start-microservices.sh"
            echo ""
            echo "Environment Files:"
            echo "  .env.dev          Optional dev-specific environment variables"
            echo ""
            exit 0
            ;;
    esac
    
    # Call the common microservices script
    "${PROJECT_ROOT}/deployment/scripts/start-microservices.sh" "$@"
}

# Execute main function
main "$@" 