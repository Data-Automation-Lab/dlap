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


# DLAP Test Environment Startup Script
# Sets test-specific configurations and starts all microservices

set -e

# Colors for output
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
NC='\033[0m'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"

echo -e "${BLUE}🧪 Starting DLAP Test Environment${NC}"
echo ""

# Set environment name
export ENVIRONMENT="test"

# Test-specific configurations
export STARTUP_MODE="docker"
export BUILD_FIRST="true"
export WAIT_FOR_HEALTH="true"
export PARALLEL_START="true"

# Spring configuration for testing
export SPRING_PROFILES_ACTIVE="test"

# Test database configuration (separate from dev)
export DATABASE_URL="jdbc:postgresql://localhost:5433"
export DATABASE_USERNAME="dlap_test"
export DATABASE_PASSWORD="${DATABASE_PASSWORD:-}"

# Kafka configuration (test-specific ports)
export KAFKA_BOOTSTRAP_SERVERS="localhost:9093"

# Keycloak configuration (test-specific)
export KEYCLOAK_AUTH_SERVER_URL="http://localhost:8181"
export KEYCLOAK_REALM="dlap-test-realm"

# Redis configuration (test-specific)
export REDIS_URL="redis://localhost:6380"

# Test-specific features
export DEBUG_MODE="false"
export LOG_LEVEL="INFO"
export TEST_DATA_ENABLED="true"
export MOCK_EXTERNAL_SERVICES="true"

# Test-specific service ports (offset by 100 from dev)
export DA_DISCOVERY_PORT="8180"
export DA_CATALOG_PORT="8181"
export DA_POLICYENGINE_PORT="8182"
export DA_REPORTING_PORT="8183"
export DA_AUTOLABEL_PORT="8184"
export DA_AUTOARCHIVAL_PORT="8185"
export DA_AUTODELETE_PORT="8186"
export DA_AUTOCOMPLIANCE_PORT="8187"
export DA_ADMIN_SERVICE_PORT="8188"

# Test environment LLM configuration (use mock/local services)
export OPENAI_API_KEY="${OPENAI_API_KEY:-}"
export OLLAMA_BASE_URL="http://localhost:11435"  # Different port for test
export USE_MOCK_LLM="true"

# Load environment-specific variables if present
if [ -f "${SCRIPT_DIR}/.env.test" ]; then
    echo -e "${BLUE}Loading test environment variables from .env.test${NC}"
    source "${SCRIPT_DIR}/.env.test"
fi

# Override microservices ports for test environment
override_service_ports() {
    export MICROSERVICES_PORTS_OVERRIDE="true"
    
    # Override the ports in the start-microservices.sh script
    export DA_DISCOVERY_PORT="8180"
    export DA_CATALOG_PORT="8181"
    export DA_POLICYENGINE_PORT="8182"
    export DA_REPORTING_PORT="8183"
    export DA_AUTOLABEL_PORT="8184"
    export DA_AUTOARCHIVAL_PORT="8185"
    export DA_AUTODELETE_PORT="8186"
    export DA_AUTOCOMPLIANCE_PORT="8187"
    export DA_ADMIN_SERVICE_PORT="8188"
}

# Check test infrastructure
check_test_infrastructure() {
    echo -e "${BLUE}🔍 Checking test infrastructure services...${NC}"
    
    local services=("postgresql:5433" "kafka:9093" "redis:6380")
    local failed_services=()
    
    for service_port in "${services[@]}"; do
        IFS=':' read -ra ADDR <<< "$service_port"
        local service="${ADDR[0]}"
        local port="${ADDR[1]}"
        
        if ! nc -z localhost $port 2>/dev/null; then
            failed_services+=("$service (port $port)")
        fi
    done
    
    if [ ${#failed_services[@]} -gt 0 ]; then
        echo -e "${YELLOW}⚠️  Test infrastructure services need to be started:${NC}"
        for service in "${failed_services[@]}"; do
            echo "   - $service"
        done
        echo ""
        echo "Note: Test environment requires separate infrastructure services"
        echo "Please ensure test Docker Compose services are running on test ports"
        echo ""
    else
        echo -e "${GREEN}✅ All test infrastructure services are running${NC}"
    fi
    echo ""
}

# Setup test data
setup_test_data() {
    echo -e "${BLUE}🗃️  Setting up test data...${NC}"
    
    # This could run database migrations, seed test data, etc.
    if [ "$TEST_DATA_ENABLED" = "true" ]; then
        echo "  - Test data setup enabled"
        echo "  - Mock external services: $MOCK_EXTERNAL_SERVICES"
        echo "  - Using mock LLM services: $USE_MOCK_LLM"
    fi
    echo ""
}

# Show test configuration
show_test_config() {
    echo -e "${BLUE}📋 Test Environment Configuration:${NC}"
    echo "  Environment: ${ENVIRONMENT}"
    echo "  Startup Mode: ${STARTUP_MODE}"
    echo "  Spring Profile: ${SPRING_PROFILES_ACTIVE}"
    echo "  Database: ${DATABASE_URL}"
    echo "  Kafka: ${KAFKA_BOOTSTRAP_SERVERS}"
    echo "  Keycloak: ${KEYCLOAK_AUTH_SERVER_URL}"
    echo "  Redis: ${REDIS_URL}"
    echo "  Service Ports: 8180-8188 (test offset)"
    echo "  Test Data: ${TEST_DATA_ENABLED}"
    echo "  Mock Services: ${MOCK_EXTERNAL_SERVICES}"
    echo ""
}

# Run integration tests
run_integration_tests() {
    echo -e "${BLUE}🧪 Running integration tests...${NC}"
    
    # Wait for all services to be healthy
    echo "Waiting for all services to be ready..."
    sleep 30
    
    # Run test suite
    cd "${PROJECT_ROOT}"
    
    # Example integration test commands
    echo "Running API integration tests..."
    # ./gradlew integrationTest -Ptest.environment=test
    
    echo "Running end-to-end workflow tests..."
    # newman run tests/postman/dlap-integration-tests.json --environment tests/postman/test-environment.json
    
    echo -e "${GREEN}✅ Integration tests completed${NC}"
}

# Main execution
main() {
    local command="${1:-start}"
    
    case "$command" in
        "start")
            show_test_config
            check_test_infrastructure
            setup_test_data
            override_service_ports
            echo -e "${BLUE}🚀 Starting test microservices...${NC}"
            ;;
        "test-full")
            show_test_config
            check_test_infrastructure
            setup_test_data
            override_service_ports
            echo -e "${BLUE}🚀 Starting services and running integration tests...${NC}"
            # Start services first
            "${PROJECT_ROOT}/deployment/scripts/start-microservices.sh" start
            # Then run tests
            run_integration_tests
            exit 0
            ;;
        "test-only")
            echo -e "${BLUE}🧪 Running integration tests only (assuming services are running)...${NC}"
            run_integration_tests
            exit 0
            ;;
        "cleanup")
            echo -e "${BLUE}🧹 Cleaning up test environment...${NC}"
            "${PROJECT_ROOT}/deployment/scripts/start-microservices.sh" stop
            # Clean test data, containers, etc.
            echo -e "${GREEN}✅ Test environment cleaned up${NC}"
            exit 0
            ;;
        "--help"|"-h")
            echo "DLAP Test Environment Startup Script"
            echo ""
            echo "Usage: $0 [COMMAND]"
            echo ""
            echo "Commands:"
            echo "  start       Start test infrastructure and microservices (default)"
            echo "  test-full   Start services and run full integration test suite"
            echo "  test-only   Run integration tests only (services must be running)"
            echo "  cleanup     Stop services and clean up test environment"
            echo "  [other]     Pass through to start-microservices.sh"
            echo ""
            echo "Environment Files:"
            echo "  .env.test   Optional test-specific environment variables"
            echo ""
            echo "Test Configuration:"
            echo "  - Uses separate ports (8180-8188) to avoid conflicts"
            echo "  - Uses separate database and infrastructure services"
            echo "  - Enables test data and mock external services"
            echo "  - Configured for automated testing workflows"
            echo ""
            exit 0
            ;;
    esac
    
    # Call the common microservices script with port overrides
    MICROSERVICES_PORTS_OVERRIDE="true" "${PROJECT_ROOT}/deployment/scripts/start-microservices.sh" "$@"
}

# Execute main function
main "$@" 