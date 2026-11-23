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


# ===========================================
# DLAP Master Script - Unified Build & Start
# ===========================================
# 
# This script consolidates all build and start operations for DLAP platform
# Supports multiple environments: dev, test, prod
#
# Usage:
#   ./dlap-master.sh build [ENV] [OPTIONS]
#   ./dlap-master.sh start [ENV] [OPTIONS]
#   ./dlap-master.sh restart [ENV] [OPTIONS]
#   ./dlap-master.sh stop [ENV]
#   ./dlap-master.sh status [ENV]
#   ./dlap-master.sh clean [ENV]
#
# Environment Options:
#   dev   - Development environment (default)
#   test  - Test environment
#   prod  - Production environment
#
# Build Options:
#   --skip-tests       Skip running tests
#   --skip-docker      Skip Docker image building
#   --parallel         Build services in parallel
#   --sequential       Build services sequentially (default)
#   --services=LIST    Build specific services only (comma-separated)
#   --frontend-only    Build only frontend
#   --backend-only     Build only backend services
#   --verbose          Show verbose build output in real-time
#
# Start Options:
#   --infrastructure-only  Start only infrastructure services
#   --services-only        Start only microservices
#   --detached            Run in detached mode (default)
#   --logs                Show logs after startup
#   --health-check        Wait for health checks before proceeding
#   --recreate            Recreate containers even if they already exist
#

set -e  # Exit on any error

# ==================== CONFIGURATION ====================

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
PURPLE='\033[0;35m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# Get script directory and project root
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

# Default values
ENVIRONMENT="dev"
COMMAND=""
SKIP_TESTS=false
SKIP_DOCKER=false
BUILD_PARALLEL=false
SERVICES_FILTER=""
FRONTEND_ONLY=false
BACKEND_ONLY=false
INFRASTRUCTURE_ONLY=false
SERVICES_ONLY=false
SINGLE_SERVICE=""
DETACHED=true
SHOW_LOGS=false
HEALTH_CHECK=false
RECREATE_CONTAINERS=false
VERBOSE=false
FORCE_REBUILD=false

# Build configuration
BUILD_TIMEOUT=600  # 10 minutes timeout per service
PARALLEL_JOBS=4

# Service definitions
ALL_SERVICES=(
    "da-protos"
    "da-discovery" 
    "da-catalog"
    "da-policyengine"
    "da-reporting"
    "da-autolabel"
    "da-autoarchival"
    "da-autodelete"
    "da-autocompliance"
    "da-admin-service"
)

FRONTEND_SERVICES=(
    "new-ui/frontend"
)

# Environment-specific configurations.
# 'prod' is intentionally NOT a compose target here (ADR-0037): production is a
# documented deployment path, not an in-tree compose stack. See deployment/PRODUCTION.md.
declare -A ENV_CONFIGS
ENV_CONFIGS[dev]="deployment/environments/dev"
ENV_CONFIGS[test]="deployment/environments/test"

# Infrastructure services startup order (KRaft mode - no Zookeeper needed)
# Note: Redis removed from dev environment, kafka-ui only in prod to reduce resource usage
INFRASTRUCTURE_SERVICES_BASE=(
    "postgresql"
    "kafka"
    "keycloak"
)

# Environment-specific infrastructure services
get_infrastructure_services() {
    local env="$1"
    local services=("${INFRASTRUCTURE_SERVICES_BASE[@]}")
    
    if [[ "$env" == "prod" ]]; then
        # Production: all services including Redis and Kafka UI
        services=("postgresql" "redis-master" "kafka" "kafka-ui" "keycloak")
    elif [[ "$env" == "test" ]]; then
        # Test: no Redis, no Kafka UI to reduce resource usage
        services=("postgresql" "kafka" "keycloak")
    else
        # Dev: minimal services (no Redis, no Kafka UI)
        services=("postgresql" "kafka" "keycloak")
    fi
    
    printf '%s\n' "${services[@]}"
}

# Microservices startup order (with dependencies)
MICROSERVICES=(
    "da-discovery"
    "da-catalog"
    "da-policyengine" 
    "da-reporting"
    "da-autolabel"
    "da-autoarchival"
    "da-autodelete"
    "da-autocompliance"
    "da-admin-service"
)

# ==================== LOGGING FUNCTIONS ====================

log() {
    echo -e "${BLUE}[$(date +'%Y-%m-%d %H:%M:%S')]${NC} $1"
}

success() {
    echo -e "${GREEN}[$(date +'%Y-%m-%d %H:%M:%S')] ✅ $1${NC}"
}

error() {
    echo -e "${RED}[$(date +'%Y-%m-%d %H:%M:%S')] ❌ $1${NC}"
}

warning() {
    echo -e "${YELLOW}[$(date +'%Y-%m-%d %H:%M:%S')] ⚠️  $1${NC}"
}

info() {
    echo -e "${CYAN}[$(date +'%Y-%m-%d %H:%M:%S')] ℹ️  $1${NC}"
}

debug() {
    echo -e "${PURPLE}[$(date +'%Y-%m-%d %H:%M:%S')] 🔍 $1${NC}"
}

# ==================== CROSS-PLATFORM TIMEOUT FUNCTION ====================

# Cross-platform timeout function that works on both Linux and macOS
timeout_command() {
    local timeout_seconds="$1"
    shift
    
    # Check if GNU timeout is available (Linux)
    if command -v timeout >/dev/null 2>&1; then
        timeout "$timeout_seconds" "$@"
    # Check if gtimeout is available (macOS with coreutils)
    elif command -v gtimeout >/dev/null 2>&1; then
        gtimeout "$timeout_seconds" "$@"
    # Fallback implementation for macOS without coreutils
    else
        # Start the command in background
        "$@" &
        local cmd_pid=$!
        
        # Wait for the specified timeout
        local elapsed=0
        while kill -0 "$cmd_pid" 2>/dev/null && [ $elapsed -lt "$timeout_seconds" ]; do
            sleep 1
            elapsed=$((elapsed + 1))
        done
        
        # If process is still running, kill it
        if kill -0 "$cmd_pid" 2>/dev/null; then
            kill -TERM "$cmd_pid" 2>/dev/null
            sleep 2
            kill -KILL "$cmd_pid" 2>/dev/null
            return 124  # Timeout exit code
        else
            # Wait for the process to finish and get its exit code
            wait "$cmd_pid"
            return $?
        fi
    fi
}

# ==================== UTILITY FUNCTIONS ====================

ensure_microservice_database() {
    local service="$1"
    
    # Check if the single database creation script exists
    local script_path="${PROJECT_ROOT}/deployment/platform/database/init-scripts/01-create-single-database.sh"
    
    if [[ ! -f "$script_path" ]]; then
        warning "Single database creation script not found at $script_path"
        return 1
    fi
    
    # Make script executable
    chmod +x "$script_path" 2>/dev/null || true
    
    # Execute the script inside the PostgreSQL container
    if docker exec dlap-postgresql bash -c "cd /docker-entrypoint-initdb.d && ./01-create-single-database.sh $service"; then
        success "Database creation completed for $service"
    else
        error "Failed to create database for $service"
        return 1
    fi
}

handle_existing_container() {
    local service="$1"
    local compose_files="$2"
    local env_file="$3"
    
    # Check if container exists (including stopped containers)
    local container_id=$(docker ps -aq -f name="$service")
    
    if [[ -n "$container_id" ]]; then
        local container_status=$(docker ps -f name="$service" --format "{{.Status}}" 2>/dev/null)
        
        if [[ "$RECREATE_CONTAINERS" == true ]]; then
            info "Recreating existing container for $service..."
            docker compose $compose_files $env_file stop "$service" 2>/dev/null || true
            docker compose $compose_files $env_file rm -f "$service" 2>/dev/null || true
            # Also remove by container ID if compose fails
            docker rm -f "$container_id" 2>/dev/null || true
            sleep 2
            return 0  # Proceed with normal startup
        elif [[ -n "$container_status" ]]; then
            # Container exists and is running
            success "$service is already running"
            return 1  # Skip normal startup
        else
            # Container exists but is not running - try to start it
            info "Starting existing container for $service..."
            if docker compose $compose_files $env_file start "$service" 2>/dev/null; then
                success "$service started successfully"
                return 1  # Skip normal startup
            else
                warning "Failed to start existing container, will recreate..."
                # Force remove the container
                docker compose $compose_files $env_file rm -f "$service" 2>/dev/null || true
                docker rm -f "$container_id" 2>/dev/null || true
                sleep 2
                return 0  # Proceed with normal startup
            fi
        fi
    fi
    
    return 0  # No existing container, proceed with normal startup
}

show_help() {
    echo "DLAP Master Script - Unified Build & Start"
    echo
    echo "USAGE:"
    echo "    $0 COMMAND [ENVIRONMENT] [OPTIONS]"
    echo
    echo "COMMANDS:"
    echo "    build       Build services for specified environment"
    echo "    start       Start services for specified environment"
    echo "    restart     Restart services for specified environment"
    echo "    stop        Stop services for specified environment"
    echo "    status      Show status of services"
    echo "    clean       Clean build artifacts and containers"
    echo "    services    List available services"
    echo "    help        Show this help message"
    echo
    echo "ENVIRONMENTS:"
    echo "    dev         Development environment (default)"
    echo "    test        Test environment"
    echo "    prod        Production environment"
    echo
    echo "BUILD OPTIONS:"
    echo "    --skip-tests           Skip running tests during build"
    echo "    --skip-docker          Skip Docker image building"
    echo "    --parallel             Build services in parallel"
    echo "    --sequential           Build services sequentially (default)"
    echo "    --services=LIST        Build specific services only (comma-separated)"
    echo "    --frontend-only        Build only frontend services"
    echo "    --backend-only         Build only backend services"
    echo "    --force-rebuild        Force rebuild of JARs and Docker images (no cache)"
    echo "    --verbose              Show verbose build output"
    echo
    echo "START OPTIONS:"
    echo "    --infrastructure-only  Start only infrastructure services"
    echo "    --services-only        Start only microservices (requires infra running)"
    echo "    --service=NAME         Start a specific service (e.g., keycloak, postgresql)"
    echo "    --detached            Run in detached mode (default)"
    echo "    --logs                Show logs after startup"
    echo "    --health-check        Wait for health checks before proceeding"
    echo "    --recreate            Recreate containers even if they already exist"
    echo
    echo "EXAMPLES:"
    echo "    $0 build dev --skip-tests"
    echo "    $0 build dev --force-rebuild"
    echo "    $0 start prod --infrastructure-only"
    echo "    $0 restart test --health-check --logs"
    echo "    $0 build dev --services=da-catalog,da-discovery"
    echo "    $0 build dev --services=da-autodelete --force-rebuild"
    echo "    $0 start dev --frontend-only"
    echo "    $0 start dev --service=keycloak"
    echo "    $0 start dev --service=postgresql --health-check"
    echo "    $0 services"
    echo
}

parse_arguments() {
    COMMAND="$1"
    shift

    # Parse environment if provided as second argument
    if [[ "$1" =~ ^(dev|test|prod)$ ]]; then
        ENVIRONMENT="$1"
        shift
    fi

    # Parse options
    while [[ $# -gt 0 ]]; do
        case $1 in
            --skip-tests)
                SKIP_TESTS=true
                shift
                ;;
            --skip-docker)
                SKIP_DOCKER=true
                shift
                ;;
            --parallel)
                BUILD_PARALLEL=true
                shift
                ;;
            --sequential)
                BUILD_PARALLEL=false
                shift
                ;;
            --services=*)
                SERVICES_FILTER="${1#*=}"
                shift
                ;;
            --frontend-only)
                FRONTEND_ONLY=true
                shift
                ;;
            --backend-only)
                BACKEND_ONLY=true
                shift
                ;;
            --infrastructure-only)
                INFRASTRUCTURE_ONLY=true
                shift
                ;;
            --services-only)
                SERVICES_ONLY=true
                shift
                ;;
            --service=*)
                SINGLE_SERVICE="${1#*=}"
                shift
                ;;
            --detached)
                DETACHED=true
                shift
                ;;
            --logs)
                SHOW_LOGS=true
                shift
                ;;
            --health-check)
                HEALTH_CHECK=true
                shift
                ;;
            --recreate)
                RECREATE_CONTAINERS=true
                shift
                ;;
            --verbose)
                VERBOSE=true
                shift
                ;;
            --force-rebuild)
                FORCE_REBUILD=true
                shift
                ;;
            --help|-h)
                show_help
                exit 0
                ;;
            *)
                error "Unknown option: $1"
                show_help
                exit 1
                ;;
        esac
    done
}

validate_environment() {
    local env="$1"
    local env_dir="${PROJECT_ROOT}/${ENV_CONFIGS[$env]}"
    
    if [[ ! -d "$env_dir" ]]; then
        error "Environment '$env' not found at $env_dir"
        exit 1
    fi
    
    if [[ ! -f "$env_dir/docker-compose.yml" ]]; then
        error "docker-compose.yml not found for environment '$env'"
        exit 1
    fi
    
    success "Environment '$env' validated"
}

check_prerequisites() {
    local missing_tools=()
    
    # Check Docker
    if ! command -v docker &> /dev/null; then
        missing_tools+=("docker")
    fi
    
    # Check Docker Compose
    if ! docker compose version &> /dev/null; then
        missing_tools+=("docker compose")
    fi
    
    # Check Java (for backend builds)
    if [[ "$FRONTEND_ONLY" != true ]] && ! command -v java &> /dev/null; then
        missing_tools+=("java")
    fi
    
    # Check Node.js (for frontend builds)
    if [[ "$BACKEND_ONLY" != true ]] && ! command -v node &> /dev/null; then
        missing_tools+=("node")
    fi
    
    # Check Gradle (for backend builds)
    if [[ "$FRONTEND_ONLY" != true ]] && ! command -v gradle &> /dev/null; then
        # Check for Gradle wrapper
        if [[ ! -f "$PROJECT_ROOT/gradlew" ]]; then
            missing_tools+=("gradle")
        fi
    fi
    
    if [[ ${#missing_tools[@]} -gt 0 ]]; then
        error "Missing required tools: ${missing_tools[*]}"
        exit 1
    fi
    
    # Check if Docker is running
    if ! docker info &> /dev/null; then
        error "Docker is not running. Please start Docker and try again."
        exit 1
    fi
    
    success "Prerequisites validated"
}

get_build_services() {
    local services=()
    
    if [[ -n "$SERVICES_FILTER" ]]; then
        IFS=',' read -ra services <<< "$SERVICES_FILTER"
    elif [[ "$FRONTEND_ONLY" == true ]]; then
        services=("${FRONTEND_SERVICES[@]}")
    elif [[ "$BACKEND_ONLY" == true ]]; then
        services=("${ALL_SERVICES[@]}")
    else
        services=("${ALL_SERVICES[@]}" "${FRONTEND_SERVICES[@]}")
    fi
    
    printf '%s\n' "${services[@]}"
}

# ==================== BUILD FUNCTIONS ====================

build_service() {
    local service="$1"
    local build_log_dir="$2"
    local log_file="${build_log_dir}/${service//\//-}-build.log"
    local start_time=$(date +%s)
    
    mkdir -p "$build_log_dir"
    
    log "Building ${service}..."
    
    if [[ ! -d "${PROJECT_ROOT}/${service}" ]]; then
        error "Service directory not found: ${PROJECT_ROOT}/${service}"
        return 1
    fi
    
    cd "${PROJECT_ROOT}/${service}"
    
    # Determine build command based on service type
    local build_cmd=""
    local test_cmd=""
    
    # Special handling for specific services (matching batch script logic)
    if [[ "$service" == "da-discovery" ]]; then
        # da-discovery uses Gradle - handle clean issues by using build without clean first
        local gradle_cmd="${PROJECT_ROOT}/gradlew"
        # Use .bat extension on Windows (MINGW/Git Bash environment)
        if [[ "$OSTYPE" == "msys" ]] || [[ -n "$WINDIR" ]]; then
            gradle_cmd="${PROJECT_ROOT}/gradlew.bat"
        fi
        if [[ "$SKIP_TESTS" == true ]]; then
            if [[ "$FORCE_REBUILD" == true ]]; then
                build_cmd="$gradle_cmd clean assemble"
            else
                build_cmd="$gradle_cmd assemble || $gradle_cmd clean assemble"
            fi
        else
            if [[ "$FORCE_REBUILD" == true ]]; then
                build_cmd="$gradle_cmd clean build"
            else
                build_cmd="$gradle_cmd build || $gradle_cmd clean build"
            fi
        fi
    elif [[ "$service" == "new-ui/frontend" ]]; then
        # Frontend service - use npm build
        if [[ "$SKIP_TESTS" == true ]]; then
            if [[ "$FORCE_REBUILD" == true ]]; then
                build_cmd="rm -rf node_modules package-lock.json && npm install && npm run build"
            else
                build_cmd="npm install && npm run build"
            fi
        else
            if [[ "$FORCE_REBUILD" == true ]]; then
                build_cmd="rm -rf node_modules package-lock.json && npm install && npm run build && npm run test:ci"
            else
                build_cmd="npm install && npm run build && npm run test:ci"
            fi
        fi
    elif [[ -f "package.json" ]]; then
        # Frontend service
        if [[ "$FORCE_REBUILD" == true ]]; then
            build_cmd="rm -rf node_modules package-lock.json && npm install && npm run build"
        else
            build_cmd="npm install && npm run build"
        fi
        test_cmd="npm test -- --watchAll=false"
    elif [[ -f "build.gradle.kts" ]] || [[ -f "build.gradle" ]]; then
        # Backend service - always use gradlew from project root like the batch script
        local gradle_cmd="${PROJECT_ROOT}/gradlew"
        # Use .bat extension on Windows (MINGW/Git Bash environment)
        if [[ "$OSTYPE" == "msys" ]] || [[ -n "$WINDIR" ]]; then
            gradle_cmd="${PROJECT_ROOT}/gradlew.bat"
        fi
        if [[ ! -f "$gradle_cmd" ]]; then
            gradle_cmd="gradle"
        fi
        
        if [[ "$SKIP_TESTS" == true ]]; then
            if [[ "$FORCE_REBUILD" == true ]]; then
                build_cmd="$gradle_cmd clean assemble"
            else
                build_cmd="$gradle_cmd clean assemble"
            fi
        else
            if [[ "$FORCE_REBUILD" == true ]]; then
                build_cmd="$gradle_cmd clean build"
            else
                build_cmd="$gradle_cmd clean build"
            fi
            test_cmd="$gradle_cmd test"
        fi
    else
        error "Unknown service type for ${service}"
        return 1
    fi
    
    # Execute build
    local build_failed=false
    if [[ "$VERBOSE" == true ]]; then
        info "Verbose mode enabled for ${service}. Build logs will be streamed to console."
        # Use a subshell with pipefail to get the correct exit status from the build command
        if ! (set -o pipefail; timeout_command ${BUILD_TIMEOUT} bash -c "$build_cmd" 2>&1 | tee "${log_file}"); then
            build_failed=true
        fi
    else
        if ! timeout_command ${BUILD_TIMEOUT} bash -c "$build_cmd" > "${log_file}" 2>&1; then
            build_failed=true
        fi
    fi

    if [[ "$build_failed" == true ]]; then
        # Build actually failed - log error and return failure
        error "Build failed for ${service}. Check ${log_file} for details."
        return 1
    fi
    
    # Build succeeded, now run tests if applicable
    local end_time=$(date +%s)
    local duration=$((end_time - start_time))
    
    if [[ "$SKIP_TESTS" == false ]] && [[ -n "$test_cmd" ]] && [[ ! "$build_cmd" =~ "test" ]]; then
        if [[ "$VERBOSE" == true ]]; then
            if ! (set -o pipefail; timeout_command ${BUILD_TIMEOUT} bash -c "$test_cmd" 2>&1 | tee -a "${log_file}"); then
                warning "${service} tests failed (build succeeded)"
            fi
        else
            if ! timeout_command ${BUILD_TIMEOUT} bash -c "$test_cmd" >> "${log_file}" 2>&1; then
                warning "${service} tests failed (build succeeded)"
            fi
        fi
    fi
    
    success "${service} built successfully (duration: ${duration}s)"
    return 0
}

build_services_parallel() {
    local services=("$@")
    local build_log_dir="${PROJECT_ROOT}/deployment/logs/build/$(date +%Y%m%d_%H%M%S)"
    
    # Use associative array to map PID to service
    declare -A pid_to_service
    local pids=()
    local failed_builds=()
    local completed_builds=0
    local total_builds=${#services[@]}
    
    mkdir -p "$build_log_dir"
    
    info "Building ${total_builds} services in parallel (max ${PARALLEL_JOBS} concurrent)"
    
    local service_idx=0
    while [[ $service_idx -lt $total_builds || ${#pids[@]} -gt 0 ]]; do
        # Start new jobs if there are slots and services in queue
        while [[ ${#pids[@]} -lt $PARALLEL_JOBS && $service_idx -lt $total_builds ]]; do
            local service="${services[$service_idx]}"
            
            build_service "$service" "$build_log_dir" &
            local pid=$!
            
            pids+=($pid)
            pid_to_service[$pid]=$service
            ((service_idx++))
            
            info "(${service_idx}/${total_builds}) Building ${service} (PID: $pid)..."
        done
        
        # Wait for any job to finish - use compatible approach
        local finished_pid=""
        local exit_code=0
        
        # Check each PID to see if it's finished
        for pid in "${pids[@]}"; do
            if ! kill -0 "$pid" 2>/dev/null; then
                # Process has finished
                finished_pid=$pid
                # Get exit code
                wait "$pid" 2>/dev/null || exit_code=$?
                break
            fi
        done
        
        # If no process finished, wait a bit and continue
        if [[ -z "$finished_pid" ]]; then
            sleep 1
            continue
        fi
        
        # Find which job completed and remove from pids array
        local service=${pid_to_service[$finished_pid]}
        unset pid_to_service[$finished_pid]
        
        local new_pids=()
        for pid in "${pids[@]}"; do
            if [[ $pid -ne $finished_pid ]]; then
                new_pids+=("$pid")
            fi
        done
        pids=("${new_pids[@]}")
        
        ((completed_builds++))
        
        if [[ $exit_code -eq 0 ]]; then
            success "(${completed_builds}/${total_builds}) ${service} built successfully."
        else
            error "(${completed_builds}/${total_builds}) ${service} build failed (exit code: $exit_code)."
            failed_builds+=("$service")
        fi
        
        if [[ $service_idx -ge $total_builds && ${#pids[@]} -eq 0 ]]; then
            break
        fi
    done

    echo # Newline for cleaner output
    
    if [[ ${#failed_builds[@]} -eq 0 ]]; then
        success "All services built successfully"
        return 0
    else
        error "Build failed for services: ${failed_builds[*]}"
        return 1
    fi
}

build_services_sequential() {
    local services=("$@")
    local build_log_dir="${PROJECT_ROOT}/deployment/logs/build/$(date +%Y%m%d_%H%M%S)"
    local failed_builds=()
    
    mkdir -p "$build_log_dir"
    
    log "Building ${#services[@]} services sequentially"
    
    for service in "${services[@]}"; do
        if ! build_service "$service" "$build_log_dir"; then
            failed_builds+=("$service")
        fi
    done
    
    if [[ ${#failed_builds[@]} -eq 0 ]]; then
        success "All services built successfully"
        return 0
    else
        error "Build failed for services: ${failed_builds[*]}"
        return 1
    fi
}

build_docker_images() {
    if [[ "$SKIP_DOCKER" == true ]]; then
        info "Skipping Docker image building"
        return 0
    fi
    
    log "Building Docker images..."
    
    local build_log_dir="${PROJECT_ROOT}/deployment/logs/docker/$(date +%Y%m%d_%H%M%S)"
    mkdir -p "$build_log_dir"
    
    local failed_builds=()
    local services_to_build=()
    
    # Determine which services to build Docker images for
    if [[ "$FRONTEND_ONLY" == true ]]; then
        services_to_build=("${FRONTEND_SERVICES[@]}")
    elif [[ "$BACKEND_ONLY" == true ]]; then
        services_to_build=("${ALL_SERVICES[@]}")
    else
        services_to_build=("${ALL_SERVICES[@]}" "${FRONTEND_SERVICES[@]}")
    fi
    
    # Filter by specific services if requested
    if [[ -n "$SERVICES_FILTER" ]]; then
        IFS=',' read -ra services_to_build <<< "$SERVICES_FILTER"
    fi
    
    for service in "${services_to_build[@]}"; do
        local dockerfile_path="${PROJECT_ROOT}/${service}/Dockerfile"
        local docker_dir="${PROJECT_ROOT}/${service}/src/main/docker"
        
        # Check for Dockerfile in multiple locations
        if [[ -f "$dockerfile_path" ]]; then
            dockerfile_path="$dockerfile_path"
        elif [[ -f "$docker_dir/Dockerfile" ]]; then
            dockerfile_path="$docker_dir/Dockerfile"
        else
            warning "No Dockerfile found for ${service}, skipping Docker build"
            continue
        fi
        
        local service_name="${service//\//-}"
        local image_tag="dlap/${service_name}:latest"
        local log_file="${build_log_dir}/${service_name}-docker.log"

        # --- MODIFIED: Always rebuild JAR for backend services to ensure latest changes ---
        # Only for backend (Java) services
        if [[ -f "${PROJECT_ROOT}/${service}/build.gradle.kts" || -f "${PROJECT_ROOT}/${service}/build.gradle" ]]; then
            local gradle_cmd="${PROJECT_ROOT}/gradlew"
            if [[ "$OSTYPE" == "msys" ]] || [[ -n "$WINDIR" ]]; then
                gradle_cmd="${PROJECT_ROOT}/gradlew.bat"
            fi
            
            if [[ "$FORCE_REBUILD" == true ]]; then
                info "Forcing JAR rebuild for ${service} to ensure latest changes are included..."
                # Clean and rebuild to ensure latest changes
                if [[ "$SKIP_TESTS" == true ]]; then
                    (cd "${PROJECT_ROOT}/${service}" && "$gradle_cmd" clean bootJar -x test)
                else
                    (cd "${PROJECT_ROOT}/${service}" && "$gradle_cmd" clean bootJar)
                fi
            else
                # Check if JAR exists, build if not
                local jar_name="${service}.jar"
                local jar_path="${PROJECT_ROOT}/${service}/build/libs/${jar_name}"
                if [[ ! -f "$jar_path" ]]; then
                    info "JAR not found for ${service}, running Gradle bootJar..."
                    if [[ "$SKIP_TESTS" == true ]]; then
                        (cd "${PROJECT_ROOT}/${service}" && "$gradle_cmd" bootJar -x test)
                    else
                        (cd "${PROJECT_ROOT}/${service}" && "$gradle_cmd" bootJar)
                    fi
                else
                    info "JAR exists for ${service}, skipping rebuild"
                fi
            fi
            
            if [[ $? -ne 0 ]]; then
                error "Failed to build JAR for ${service}"
                failed_builds+=("${service}")
                continue
            fi
        fi
        # --- END MODIFIED ---
        
        log "Building Docker image for ${service}..."
        
        # Build command with conditional --no-cache
        local docker_build_cmd="docker build"
        if [[ "$FORCE_REBUILD" == true ]]; then
            docker_build_cmd="$docker_build_cmd --no-cache"
            log "Building Docker image for ${service} with --no-cache to ensure latest changes..."
        else
            log "Building Docker image for ${service}..."
        fi
        
        if $docker_build_cmd -f "$dockerfile_path" -t "$image_tag" "${PROJECT_ROOT}/${service}" > "$log_file" 2>&1; then
            if [[ "$FORCE_REBUILD" == true ]]; then
                success "Docker image for ${service} built successfully with latest changes"
            else
                success "Docker image for ${service} built successfully"
            fi
        else
            error "Docker image build failed for ${service}. Check $log_file"
            failed_builds+=("${service}")
        fi
    done
    
    if [[ ${#failed_builds[@]} -eq 0 ]]; then
        success "All Docker images built successfully with latest changes"
        return 0
    else
        error "Docker image builds failed for: ${failed_builds[*]}"
        return 1
    fi
}

# ==================== START/STOP FUNCTIONS ====================

start_infrastructure() {
    local env="$1"
    local env_dir="${PROJECT_ROOT}/${ENV_CONFIGS[$env]}"
    
    log "Starting infrastructure services for environment: $env"
    
    cd "$env_dir"
    
    # Create network if it doesn't exist (updated for new network names)
    if [[ "$env" == "test" ]]; then
        docker network create dlap-test-network 2>/dev/null || true
    else
    docker network create dlap-network 2>/dev/null || true
    fi
    
    # Load environment variables
    local env_file=""
    if [[ -f ".env.${env}" ]]; then
        env_file="--env-file .env.${env}"
    elif [[ -f "env.${env}.example" ]]; then
        warning "Using example env file. Copy env.${env}.example to .env.${env} for customization"
        env_file="--env-file env.${env}.example"
    fi
    
    # Strategy 3: Use infrastructure-only docker-compose.yml file
    # Start infrastructure services in order (environment-specific)
    local infrastructure_services=($(get_infrastructure_services "$env"))
    for service in "${infrastructure_services[@]}"; do
        log "Starting $service..."
        
        # Handle existing containers gracefully
        if handle_existing_container "$service" "-f docker-compose.yml" "$env_file"; then
            # Proceed with normal startup
            if docker compose -f docker-compose.yml $env_file up -d "$service"; then
            success "$service started"
        else
            error "Failed to start $service"
            return 1
            fi
        fi
        
        # Add delays for dependencies
        case "$service" in
            "postgresql")
                log "Waiting for PostgreSQL to be ready..."
                sleep 10
                ;;
            "kafka")
                log "Waiting for Kafka to be ready..."
                sleep 15
                ;;
            "keycloak")
                log "Waiting for Keycloak to be ready..."
                sleep 20
                ;;
        esac
    done
    
    if [[ "$HEALTH_CHECK" == true ]]; then
        wait_for_infrastructure_health "$env"
    fi
    
    success "Infrastructure services started successfully"
}

start_single_service() {
    local env="$1"
    local service="$2"
    local env_dir="${PROJECT_ROOT}/${ENV_CONFIGS[$env]}"
    
    log "Starting individual service: $service (environment: $env)"
    
    cd "$env_dir"
    
    # Create network if it doesn't exist
    if [[ "$env" == "test" ]]; then
        docker network create dlap-test-network 2>/dev/null || true
    else
        docker network create dlap-network 2>/dev/null || true
    fi
    
    # Load environment variables
    local env_file=""
    if [[ -f ".env.${env}" ]]; then
        env_file="--env-file .env.${env}"
    elif [[ -f "env.${env}.example" ]]; then
        warning "Using example env file. Copy env.${env}.example to .env.${env} for customization"
        env_file="--env-file env.${env}.example"
    fi
    
    # Check if service exists in infrastructure services (environment-specific)
    local found_in_infrastructure=false
    local infrastructure_services=($(get_infrastructure_services "$env"))
    for infra_service in "${infrastructure_services[@]}"; do
        if [[ "$infra_service" == "$service" ]]; then
            found_in_infrastructure=true
            break
        fi
    done
    
    # Check if service exists in microservices
    local found_in_microservices=false
    for micro_service in "${MICROSERVICES[@]}"; do
        if [[ "$micro_service" == "$service" ]]; then
            found_in_microservices=true
            break
        fi
    done
    
    # Validate service exists
    if [[ "$found_in_infrastructure" == false ]] && [[ "$found_in_microservices" == false ]]; then
        error "Service '$service' not found in infrastructure or microservices list"
        info "Available infrastructure services: ${INFRASTRUCTURE_SERVICES[*]}"
        info "Available microservices: ${MICROSERVICES[*]}"
        return 1
    fi

    # For microservices, ensure required infrastructure is running
    if [[ "$found_in_microservices" == true ]]; then
        info "Checking required infrastructure for microservice: $service"
        
        # Check if PostgreSQL is running
        if ! docker ps --format "table {{.Names}}" | grep -q "postgresql"; then
            warning "PostgreSQL is not running. Starting PostgreSQL..."
            if ! docker compose -f docker-compose.yml $env_file up -d postgresql; then
                error "Failed to start PostgreSQL"
                return 1
            fi
            log "Waiting for PostgreSQL to be ready..."
            sleep 10
            if [[ "$HEALTH_CHECK" == true ]]; then
                wait_for_postgresql_health
            fi
        fi
        
        # Check if Kafka is running
        if ! docker ps --format "table {{.Names}}" | grep -q "kafka"; then
            warning "Kafka is not running. Starting Kafka..."
            if ! docker compose -f docker-compose.yml $env_file up -d kafka; then
                error "Failed to start Kafka"
                return 1
            fi
            log "Waiting for Kafka to be ready..."
            sleep 15
            if [[ "$HEALTH_CHECK" == true ]]; then
                wait_for_kafka_health
            fi
        fi
        
        # Check if Keycloak is running
        if ! docker ps --format "table {{.Names}}" | grep -q "keycloak"; then
            warning "Keycloak is not running. Starting Keycloak..."
            if ! docker compose -f docker-compose.yml $env_file up -d keycloak; then
                error "Failed to start Keycloak"
                return 1
            fi
            log "Waiting for Keycloak to be ready..."
            sleep 20
            if [[ "$HEALTH_CHECK" == true ]]; then
                wait_for_keycloak_health
            fi
        fi
        
        success "Required infrastructure is running"
        
        # Ensure the microservice's database exists
        ensure_microservice_database "$service"
    fi

    # Start the service
    if [[ "$found_in_infrastructure" == true ]]; then
        info "Starting infrastructure service: $service"
        
        # Handle existing containers gracefully
        if handle_existing_container "$service" "-f docker-compose.yml" "$env_file"; then
            # Proceed with normal startup
            if docker compose -f docker-compose.yml $env_file up -d "$service"; then
                success "$service started successfully"
                
                # Add service-specific startup delays and health checks
                case "$service" in
                    "postgresql")
                        log "Waiting for PostgreSQL to be ready..."
                        sleep 10
                        if [[ "$HEALTH_CHECK" == true ]]; then
                            wait_for_postgresql_health
                        fi
                        ;;
                    "kafka")
                        log "Waiting for Kafka to be ready..."
                        sleep 15
                        if [[ "$HEALTH_CHECK" == true ]]; then
                            wait_for_kafka_health
                        fi
                        ;;
                    "keycloak")
                        log "Waiting for Keycloak to be ready..."
                        sleep 20
                        if [[ "$HEALTH_CHECK" == true ]]; then
                            wait_for_keycloak_health
                        fi
                        ;;
                    "redis")
                        log "Waiting for Redis to be ready..."
                        sleep 5
                        if [[ "$HEALTH_CHECK" == true ]]; then
                            wait_for_redis_health
                        fi
                        ;;
                esac
            else
                error "Failed to start $service"
                return 1
            fi
        fi
    else
        # Microservice - needs microservices compose file
        local microservices_compose="docker-compose.microservices.yml"
        if [[ ! -f "$microservices_compose" ]]; then
            error "Microservices compose file not found: $microservices_compose"
            return 1
        fi
        
        info "Starting microservice: $service"
        
        # Handle existing containers gracefully
        if handle_existing_container "$service" "-f docker-compose.yml -f $microservices_compose" "$env_file"; then
            # Proceed with normal startup
            if docker compose -f docker-compose.yml -f "$microservices_compose" $env_file up -d "$service"; then
                success "$service started successfully"
                
                if [[ "$HEALTH_CHECK" == true ]]; then
                    wait_for_service_health "$service"
                fi
            else
                error "Failed to start $service"
                return 1
            fi
        fi
    fi
    
    # Show service status and access URLs
    info "Service Status:"
    docker compose ps "$service" 2>/dev/null || docker compose -f docker-compose.microservices.yml ps "$service" 2>/dev/null
    
    # Show relevant URLs
    case "$service" in
        "keycloak")
            local port="8180"
            [[ "$env" == "test" ]] && port="8280"
            info "Keycloak Admin Console: http://localhost:$port/admin"
            info "Keycloak Realm: http://localhost:$port/realms/dlap"
            ;;
        "kafka-ui")
            local port="8090"
            [[ "$env" == "test" ]] && port="8190"
            info "Kafka UI: http://localhost:$port"
            ;;
        "postgresql")
            local port="5432"
            [[ "$env" == "test" ]] && port="5532"
            info "PostgreSQL: localhost:$port (user: dlap, password: dlap)"
            ;;
                            "redis"|"redis-master")
                        if [[ "$env" == "prod" ]]; then
                            info "Redis Master: localhost:6379 (password required)"
                        else
                            info "Redis is not available in $env environment (production only)"
                        fi
                        ;;
        "da-"*)
            local port_map=("da-discovery:8081" "da-catalog:8082" "da-policyengine:8083" "da-reporting:8084" "da-autolabel:8085" "da-autoarchival:8086" "da-autodelete:8087" "da-autocompliance:8088" "da-admin-service:8089")
            for mapping in "${port_map[@]}"; do
                local svc="${mapping%:*}"
                local port="${mapping#*:}"
                if [[ "$svc" == "$service" ]]; then
                    [[ "$env" == "test" ]] && port=$((port + 100))
                    info "$service API: http://localhost:$port"
                    info "$service Health: http://localhost:$port/actuator/health"
                    break
                fi
            done
            ;;
    esac
}

start_microservices() {
    local env="$1"
    local env_dir="${PROJECT_ROOT}/${ENV_CONFIGS[$env]}"
    
    log "Starting microservices for environment: $env"
    
    cd "$env_dir"
    
    # Strategy 3: Use consolidated microservices compose file with build contexts
    local microservices_compose="docker-compose.microservices.yml"
    if [[ ! -f "$microservices_compose" ]]; then
        error "Microservices compose file not found: $microservices_compose"
        error "Please ensure Docker consolidation has been completed"
        return 1
    fi
    
    # Load environment variables
    local env_file=""
    if [[ -f ".env.${env}" ]]; then
        env_file="--env-file .env.${env}"
    elif [[ -f "env.${env}.example" ]]; then
        env_file="--env-file env.${env}.example"
    fi
    
    # Start microservices using multiple compose files (Strategy 3)
    local docker_flags="-d"
    if [[ "$DETACHED" == false ]]; then
        docker_flags=""
    fi
    
    info "Using consolidated Docker structure with build contexts"
    info "Infrastructure: docker-compose.yml | Microservices: docker-compose.microservices.yml"
    
    # Strategy 3: Use multiple compose files for complete environment
    if [[ "$RECREATE_CONTAINERS" == true ]]; then
        info "Recreating microservices containers..."
        docker compose -f docker-compose.yml -f "$microservices_compose" $env_file down 2>/dev/null || true
        sleep 2
    fi
    
    if docker compose -f docker-compose.yml -f "$microservices_compose" $env_file up $docker_flags; then
        success "Microservices started successfully using consolidated structure"
        
        if [[ "$HEALTH_CHECK" == true ]]; then
            wait_for_microservices_health "$env"
        fi
        
        return 0
    else
        # If normal startup fails, try to start existing containers
        warning "Normal startup failed, attempting to start existing containers..."
        if docker compose -f docker-compose.yml -f "$microservices_compose" $env_file start; then
            success "Existing microservices containers started"
        return 0
    else
        error "Failed to start microservices"
        return 1
    fi
    fi
}

wait_for_postgresql_health() {
    info "Checking PostgreSQL health..."
    local retries=30
    while [[ $retries -gt 0 ]]; do
        if docker exec $(docker ps -q -f name=postgresql) pg_isready -U dlap &>/dev/null; then
            success "PostgreSQL is ready"
            return 0
        fi
        ((retries--))
        sleep 2
    done
    error "PostgreSQL health check timed out"
    return 1
}

wait_for_keycloak_health() {
    info "Checking Keycloak health..."
    local retries=60
    local container_name=$(docker ps -q -f name=keycloak)
    while [[ $retries -gt 0 ]]; do
        if docker exec "$container_name" curl -sf http://localhost:8080/health &>/dev/null; then
            success "Keycloak is ready"
            return 0
        fi
        ((retries--))
        sleep 3
    done
    error "Keycloak health check timed out"
    return 1
}

wait_for_kafka_health() {
    info "Checking Kafka health..."
    local retries=30
    local container_name=$(docker ps -q -f name=kafka)
    while [[ $retries -gt 0 ]]; do
        if docker exec "$container_name" kafka-topics --bootstrap-server localhost:9092 --list &>/dev/null; then
            success "Kafka is ready"
            return 0
        fi
        ((retries--))
        sleep 2
    done
    error "Kafka health check timed out"
    return 1
}

wait_for_redis_health() {
    info "Checking Redis health..."
    local retries=15
    local container_name=$(docker ps -q -f name=redis)
    while [[ $retries -gt 0 ]]; do
        # Handle both redis (test) and redis-master (prod) container names
        if docker exec "$container_name" redis-cli ping | grep -q PONG; then
            success "Redis is ready"
            return 0
        elif docker exec "$container_name" redis-cli -a "${REDIS_PASSWORD:-}" ping | grep -q PONG; then
            success "Redis is ready"
            return 0
        fi
        ((retries--))
        sleep 1
    done
    error "Redis health check timed out"
    return 1
}

wait_for_service_health() {
    local service="$1"
    info "Checking $service health..."
    local retries=30
    local container_name=$(docker ps -q -f name="$service")
    while [[ $retries -gt 0 ]]; do
        if docker exec "$container_name" curl -sf http://localhost:8080/actuator/health &>/dev/null; then
            success "$service is ready"
            return 0
        fi
        ((retries--))
        sleep 2
    done
    error "$service health check timed out"
    return 1
}

wait_for_infrastructure_health() {
    local env="$1"
    log "Performing infrastructure health checks..."
    
    # PostgreSQL health check
    wait_for_postgresql_health
    
    # Kafka health check
    info "Checking Kafka..."
    retries=30
    while [[ $retries -gt 0 ]]; do
        if docker exec $(docker ps -q -f name=kafka) kafka-topics --bootstrap-server localhost:9092 --list &>/dev/null; then
            success "Kafka is ready"
            break
        fi
        ((retries--))
        sleep 2
    done
    
    # Keycloak health check
    info "Checking Keycloak..."
    retries=60
    while [[ $retries -gt 0 ]]; do
        if curl -sf "http://localhost:8180/health" &>/dev/null; then
            success "Keycloak is ready"
            break
        fi
        ((retries--))
        sleep 3
    done
    
    success "Infrastructure health checks completed"
}

wait_for_microservices_health() {
    local env="$1"
    log "Performing microservices health checks..."
    
    local services_ports=(
        "da-discovery:8080"
        "da-catalog:8081"
        "da-policyengine:8082"
        "da-reporting:8083"
        "da-autolabel:8084"
        "da-autoarchival:8085"
        "da-autodelete:8086"
        "da-autocompliance:8087"
        "da-admin-service:8088"
    )
    
    for service_port in "${services_ports[@]}"; do
        local service="${service_port%:*}"
        local port="${service_port#*:}"
        
        info "Checking $service on port $port..."
        local retries=30
        while [[ $retries -gt 0 ]]; do
            if curl -sf "http://localhost:$port/actuator/health" &>/dev/null; then
                success "$service is ready"
                break
            fi
            ((retries--))
            sleep 3
        done
        
        if [[ $retries -eq 0 ]]; then
            warning "$service health check timed out"
        fi
    done
    
    success "Microservices health checks completed"
}

stop_services() {
    local env="$1"
    local env_dir="${PROJECT_ROOT}/${ENV_CONFIGS[$env]}"
    
    log "Stopping services for environment: $env"
    
    cd "$env_dir"
    
    # Stop microservices first
    if [[ -f "docker-compose.microservices.yml" ]]; then
        log "Stopping microservices..."
        docker compose -f docker-compose.microservices.yml down
    fi
    
    # Stop infrastructure
    log "Stopping infrastructure..."
    docker compose down
    
    success "Services stopped successfully"
}

show_status() {
    local env="$1"
    local env_dir="${PROJECT_ROOT}/${ENV_CONFIGS[$env]}"
    
    log "Service status for environment: $env"
    
    cd "$env_dir"
    
    echo
    info "Infrastructure Services:"
    docker compose ps
    
    echo
    if [[ -f "docker-compose.microservices.yml" ]]; then
        info "Microservices:"
        docker compose -f docker-compose.microservices.yml ps
    fi
    
    echo
    info "Service URLs (${env^} Environment):"
    if [[ "$env" == "dev" ]]; then
    cat << EOF
  Frontend UI:        http://localhost:3000
  Discovery:          http://localhost:8081
  Catalog:            http://localhost:8082
  Policy Engine:      http://localhost:8083
  Reporting:          http://localhost:8084
  AutoLabel:          http://localhost:8085
  AutoArchival:       http://localhost:8086
  AutoDelete:         http://localhost:8087
  AutoCompliance:     http://localhost:8088
  Admin Service:      http://localhost:8089
  
  Keycloak:           http://localhost:8180
  
  Note: Redis and Kafka UI are disabled in dev environment to reduce resource usage
EOF
    elif [[ "$env" == "test" ]]; then
        cat << EOF
  Frontend UI:        http://localhost:3100
  Discovery:          http://localhost:8181
  Catalog:            http://localhost:8182
  Policy Engine:      http://localhost:8183
  Reporting:          http://localhost:8184
  AutoLabel:          http://localhost:8185
  AutoArchival:       http://localhost:8186
  AutoDelete:         http://localhost:8187
  AutoCompliance:     http://localhost:8188
  Admin Service:      http://localhost:8189
  
  Keycloak:           http://localhost:8280
  
  Note: Redis and Kafka UI are disabled in test environment to reduce resource usage
EOF
    else
        cat << EOF
  Frontend UI:        http://localhost:3000
  Discovery:          http://localhost:8081
  Catalog:            http://localhost:8082
  Policy Engine:      http://localhost:8083
  Reporting:          http://localhost:8084
  AutoLabel:          http://localhost:8085
  AutoArchival:       http://localhost:8086
  AutoDelete:         http://localhost:8087
  AutoCompliance:     http://localhost:8088
  Admin Service:      http://localhost:8089
  
  Keycloak:           http://localhost:8180
  Redis Master:       localhost:6379 (password required)
  Kafka UI:           http://localhost:8090
EOF
    fi
}

show_logs() {
    local env="$1"
    local env_dir="${PROJECT_ROOT}/${ENV_CONFIGS[$env]}"
    
    cd "$env_dir"
    
    log "Showing logs for environment: $env"
    
    if [[ -f "docker-compose.microservices.yml" ]]; then
        docker compose -f docker-compose.microservices.yml logs -f
    else
        docker compose logs -f
    fi
}

clean_environment() {
    local env="$1"
    local env_dir="${PROJECT_ROOT}/${ENV_CONFIGS[$env]}"
    
    log "Cleaning environment: $env"
    
    cd "$env_dir"
    
    # Stop and remove containers
    if [[ -f "docker-compose.microservices.yml" ]]; then
        docker compose -f docker-compose.microservices.yml down -v --remove-orphans
    fi
    docker compose down -v --remove-orphans
    
    # Clean build artifacts
    log "Cleaning build artifacts..."
    rm -rf "${PROJECT_ROOT}/deployment/logs"
    
    # Clean Gradle build directories
    for service in "${ALL_SERVICES[@]}"; do
        if [[ -d "${PROJECT_ROOT}/${service}/build" ]]; then
            rm -rf "${PROJECT_ROOT}/${service}/build"
        fi
    done
    
    # Clean frontend build directories
    for service in "${FRONTEND_SERVICES[@]}"; do
        if [[ -d "${PROJECT_ROOT}/${service}/build" ]]; then
            rm -rf "${PROJECT_ROOT}/${service}/build"
        fi
        if [[ -d "${PROJECT_ROOT}/${service}/dist" ]]; then
            rm -rf "${PROJECT_ROOT}/${service}/dist"
        fi
    done
    
    success "Environment cleaned successfully"
}

# ==================== MAIN EXECUTION ====================

main() {
    # Parse command line arguments
    if [[ $# -eq 0 ]]; then
        show_help
        exit 0
    fi
    
    parse_arguments "$@"
    
    # Show configuration
    info "DLAP Master Script Configuration:"
    info "  Command: $COMMAND"
    info "  Environment: $ENVIRONMENT"
    info "  Project Root: $PROJECT_ROOT"
    if [[ "$FORCE_REBUILD" == true ]]; then
        info "  Mode: Force rebuild (no cache)"
    elif [[ "$RECREATE_CONTAINERS" == true ]]; then
        info "  Mode: Recreate existing containers"
    else
        info "  Mode: Start existing containers or create new ones"
    fi
    
    # Validate environment
    validate_environment "$ENVIRONMENT"
    
    case "$COMMAND" in
        "build")
            check_prerequisites
            
            local services=($(get_build_services))
            info "Building services: ${services[*]}"
            
            # Build services with fallback mechanism
            if [[ "$BUILD_PARALLEL" == true ]]; then
                info "Attempting parallel build..."
                if ! build_services_parallel "${services[@]}"; then
                    warning "Parallel build failed, falling back to sequential build..."
                    build_services_sequential "${services[@]}"
                fi
            else
                build_services_sequential "${services[@]}"
            fi
            
            # Build Docker images
            build_docker_images
            
            success "Build completed successfully"
            ;;
            
        "start")
            check_prerequisites
            
            if [[ -n "$SINGLE_SERVICE" ]]; then
                start_single_service "$ENVIRONMENT" "$SINGLE_SERVICE"
                
                if [[ "$SHOW_LOGS" == true ]]; then
                    # Show logs for the single service
                    local env_dir="${PROJECT_ROOT}/${ENV_CONFIGS[$ENVIRONMENT]}"
                    cd "$env_dir"
                    docker compose logs -f "$SINGLE_SERVICE" 2>/dev/null || \
                    docker compose -f docker-compose.microservices.yml logs -f "$SINGLE_SERVICE" 2>/dev/null
                fi
            elif [[ "$SERVICES_ONLY" == true ]]; then
                start_microservices "$ENVIRONMENT"
            elif [[ "$INFRASTRUCTURE_ONLY" == true ]]; then
                start_infrastructure "$ENVIRONMENT"
            else
                start_infrastructure "$ENVIRONMENT"
                start_microservices "$ENVIRONMENT"
            fi
            
            if [[ "$SHOW_LOGS" == true ]] && [[ -z "$SINGLE_SERVICE" ]]; then
                show_logs "$ENVIRONMENT"
            elif [[ -z "$SINGLE_SERVICE" ]]; then
                show_status "$ENVIRONMENT"
            fi
            ;;
            
        "restart")
            check_prerequisites
            
            if [[ -n "$SINGLE_SERVICE" ]]; then
                # Stop single service
                local env_dir="${PROJECT_ROOT}/${ENV_CONFIGS[$ENVIRONMENT]}"
                cd "$env_dir"
                docker compose stop "$SINGLE_SERVICE" 2>/dev/null || \
                docker compose -f docker-compose.microservices.yml stop "$SINGLE_SERVICE" 2>/dev/null
                sleep 2
                
                start_single_service "$ENVIRONMENT" "$SINGLE_SERVICE"
                
                if [[ "$SHOW_LOGS" == true ]]; then
                    docker compose logs -f "$SINGLE_SERVICE" 2>/dev/null || \
                    docker compose -f docker-compose.microservices.yml logs -f "$SINGLE_SERVICE" 2>/dev/null
                fi
            else
            stop_services "$ENVIRONMENT"
            sleep 5
            
            if [[ "$SERVICES_ONLY" == true ]]; then
                start_microservices "$ENVIRONMENT"
            elif [[ "$INFRASTRUCTURE_ONLY" == true ]]; then
                start_infrastructure "$ENVIRONMENT"
            else
                start_infrastructure "$ENVIRONMENT"
                start_microservices "$ENVIRONMENT"
            fi
            
            if [[ "$SHOW_LOGS" == true ]]; then
                show_logs "$ENVIRONMENT"
            else
                show_status "$ENVIRONMENT"
                fi
            fi
            ;;
            
        "stop")
            stop_services "$ENVIRONMENT"
            ;;
            
        "status")
            show_status "$ENVIRONMENT"
            ;;
            
        "logs")
            show_logs "$ENVIRONMENT"
            ;;
            
        "clean")
            clean_environment "$ENVIRONMENT"
            ;;
            
        "help"|"--help"|"-h")
            show_help
            ;;
            
        "services"|"list")
            info "Available Services for environment: $ENVIRONMENT"
            echo
            info "Infrastructure Services:"
            local infrastructure_services=($(get_infrastructure_services "$ENVIRONMENT"))
            printf "  %s\n" "${infrastructure_services[@]}"
            echo
            info "Microservices:"
            printf "  %s\n" "${MICROSERVICES[@]}"
            ;;
            
        *)
            error "Unknown command: $COMMAND"
            show_help
            exit 1
            ;;
    esac
}

# Execute main function with all arguments
main "$@" 