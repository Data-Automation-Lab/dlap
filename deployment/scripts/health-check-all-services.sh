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


# =============================================================================
# DLAP HF Spaces Comprehensive Health Check Script
# =============================================================================
# This script checks the health of all DLAP microservices, CI/CD components,
# and frontend services deployed on HF Spaces with detailed reporting
# Usage: ./health-check-all-services.sh [environment] [--verbose] [--json] [--dry-run]
# =============================================================================

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
PURPLE='\033[0;35m'
BOLD='\033[1m'
NC='\033[0m' # No Color

log() {
    echo -e "${BLUE}[$(date +'%Y-%m-%d %H:%M:%S')]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

log_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

log_info() {
    echo -e "${CYAN}[INFO]${NC} $1"
}

log_debug() {
    if [[ "$VERBOSE" == "true" ]]; then
        echo -e "${PURPLE}[DEBUG]${NC} $1"
    fi
}

# Configuration
ENVIRONMENT=${1:-dev}
VERBOSE="false"
JSON_OUTPUT="false"
DRY_RUN="false"
HF_ORG="dalabsai"
TIMEOUT=30
HEALTH_CHECK_INTERVAL=5
MAX_RETRIES=3

# Parse command line arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --verbose)
            VERBOSE="true"
            shift
            ;;
        --json)
            JSON_OUTPUT="true"
            shift
            ;;
        --dry-run)
            DRY_RUN="true"
            shift
            ;;
        -h|--help)
            show_help
            exit 0
            ;;
        *)
            if [[ -z "$ENVIRONMENT" ]]; then
                ENVIRONMENT="$1"
            fi
            shift
            ;;
    esac
done

# Service definitions with health check endpoints
declare -A MICROSERVICES_CONFIG
MICROSERVICES_CONFIG=(
    ["da-discovery"]="/actuator/health"
    ["da-catalog"]="/actuator/health"
    ["da-policyengine"]="/actuator/health"
    ["da-reporting"]="/actuator/health"
    ["da-autolabel"]="/actuator/health"
    ["da-autoarchival"]="/actuator/health"
    ["da-autodelete"]="/actuator/health"
    ["da-autocompliance"]="/actuator/health"
    ["da-admin-service"]="/actuator/health"
)

declare -A CICD_CONFIG
CICD_CONFIG=(
    ["dlap-build-pipeline"]="/health"
    ["dlap-deployment-manager"]="/health"
    ["dlap-test-runner"]="/health"
)

declare -A FRONTEND_CONFIG
FRONTEND_CONFIG=(
    ["new-ui"]="/"
)

# Health check results
declare -A SERVICE_STATUS
declare -A SERVICE_RESPONSE_TIME
declare -A SERVICE_HTTP_CODE
declare -A SERVICE_ERROR_MESSAGE

HEALTHY_COUNT=0
UNHEALTHY_COUNT=0
UNREACHABLE_COUNT=0
TOTAL_COUNT=0

# JSON output array
JSON_RESULTS="[]"

# Health check function with retry logic
check_service_health() {
    local service_name="$1"
    local service_type="$2"
    local endpoint="$3"
    local retry_count=0
    
    TOTAL_COUNT=$((TOTAL_COUNT + 1))
    
    # Construct URL based on service type
    local url
    if [[ "$service_type" == "cicd" ]]; then
        url="https://${HF_ORG}-${service_name}.hf.space${endpoint}"
    elif [[ "$service_type" == "frontend" ]]; then
        url="https://${HF_ORG}-${service_name}-${ENVIRONMENT}.hf.space${endpoint}"
    else
        url="https://${HF_ORG}-${service_name}-${ENVIRONMENT}.hf.space${endpoint}"
    fi
    
    if [[ "$DRY_RUN" == "true" ]]; then
        echo -e "  ${service_name}... ${YELLOW}DRY-RUN${NC} (would check: $url)"
        SERVICE_STATUS["$service_name"]="dry-run"
        return 0
    fi
    
    log_debug "Checking $service_name at $url"
    
    # Retry logic
    while [[ $retry_count -lt $MAX_RETRIES ]]; do
        local start_time=$(date +%s%N)
        local http_status
        local response_body
        
        # Perform health check with timeout
        http_status=$(curl -s -o /tmp/health_response_$$ -w "%{http_code}" \
            --connect-timeout $TIMEOUT \
            --max-time $((TIMEOUT * 2)) \
            "$url" 2>/dev/null || echo "000")
        
        local end_time=$(date +%s%N)
        local response_time=$(( (end_time - start_time) / 1000000 )) # Convert to milliseconds
        
        SERVICE_HTTP_CODE["$service_name"]=$http_status
        SERVICE_RESPONSE_TIME["$service_name"]=$response_time
        
        log_debug "HTTP Status: $http_status, Response Time: ${response_time}ms"
        
        if [[ "$http_status" == "000" ]]; then
            SERVICE_STATUS["$service_name"]="unreachable"
            SERVICE_ERROR_MESSAGE["$service_name"]="Connection failed"
            retry_count=$((retry_count + 1))
            if [[ $retry_count -lt $MAX_RETRIES ]]; then
                log_debug "Retry $retry_count/$MAX_RETRIES for $service_name"
                sleep $HEALTH_CHECK_INTERVAL
                continue
            fi
        elif [[ "$http_status" == "200" ]]; then
            # For microservices, parse health response
            if [[ "$service_type" == "microservice" ]]; then
                response_body=$(cat /tmp/health_response_$$ 2>/dev/null || echo "")
                if echo "$response_body" | grep -q '"status":"UP"'; then
                    SERVICE_STATUS["$service_name"]="healthy"
                    break
                elif echo "$response_body" | grep -q '"status":"DOWN"'; then
                    SERVICE_STATUS["$service_name"]="unhealthy"
                    SERVICE_ERROR_MESSAGE["$service_name"]="Service reports DOWN status"
                    break
                else
                    SERVICE_STATUS["$service_name"]="unknown"
                    SERVICE_ERROR_MESSAGE["$service_name"]="Unknown health status"
                    break
                fi
            else
                SERVICE_STATUS["$service_name"]="healthy"
                break
            fi
        elif [[ "$http_status" -ge 400 ]] && [[ "$http_status" -lt 500 ]]; then
            SERVICE_STATUS["$service_name"]="client-error"
            SERVICE_ERROR_MESSAGE["$service_name"]="HTTP $http_status Client Error"
            break
        elif [[ "$http_status" -ge 500 ]]; then
            SERVICE_STATUS["$service_name"]="server-error"
            SERVICE_ERROR_MESSAGE["$service_name"]="HTTP $http_status Server Error"
            retry_count=$((retry_count + 1))
            if [[ $retry_count -lt $MAX_RETRIES ]]; then
                log_debug "Server error, retry $retry_count/$MAX_RETRIES for $service_name"
                sleep $HEALTH_CHECK_INTERVAL
                continue
            fi
            break
        else
            SERVICE_STATUS["$service_name"]="unknown"
            SERVICE_ERROR_MESSAGE["$service_name"]="HTTP $http_status Unknown Status"
            break
        fi
    done
    
    # Clean up temp file
    rm -f /tmp/health_response_$$
    
    # Display result
    local status_display
    local status_color
    
    case "${SERVICE_STATUS[$service_name]}" in
        "healthy")
            status_display="✅ HEALTHY"
            status_color="$GREEN"
            HEALTHY_COUNT=$((HEALTHY_COUNT + 1))
            ;;
        "unhealthy")
            status_display="⚠️  UNHEALTHY"
            status_color="$YELLOW"
            UNHEALTHY_COUNT=$((UNHEALTHY_COUNT + 1))
            ;;
        "unreachable")
            status_display="❌ UNREACHABLE"
            status_color="$RED"
            UNREACHABLE_COUNT=$((UNREACHABLE_COUNT + 1))
            ;;
        "client-error")
            status_display="🔒 CLIENT ERROR"
            status_color="$YELLOW"
            UNHEALTHY_COUNT=$((UNHEALTHY_COUNT + 1))
            ;;
        "server-error")
            status_display="💥 SERVER ERROR"
            status_color="$RED"
            UNHEALTHY_COUNT=$((UNHEALTHY_COUNT + 1))
            ;;
        "unknown")
            status_display="❓ UNKNOWN"
            status_color="$PURPLE"
            UNHEALTHY_COUNT=$((UNHEALTHY_COUNT + 1))
            ;;
        "dry-run")
            status_display="🔍 DRY-RUN"
            status_color="$CYAN"
            ;;
        *)
            status_display="❓ UNDEFINED"
            status_color="$PURPLE"
            UNHEALTHY_COUNT=$((UNHEALTHY_COUNT + 1))
            ;;
    esac
    
    printf "  %-25s %s" "$service_name" "$status_display"
    if [[ "$VERBOSE" == "true" && "${SERVICE_RESPONSE_TIME[$service_name]}" != "" ]]; then
        printf " (%sms)" "${SERVICE_RESPONSE_TIME[$service_name]}"
    fi
    if [[ "${SERVICE_ERROR_MESSAGE[$service_name]}" != "" ]]; then
        printf " - %s" "${SERVICE_ERROR_MESSAGE[$service_name]}"
    fi
    echo
    
    # Add to JSON results if JSON output is enabled
    if [[ "$JSON_OUTPUT" == "true" ]]; then
        local json_entry=$(cat << EOF
{
  "service": "$service_name",
  "type": "$service_type",
  "environment": "$ENVIRONMENT",
  "status": "${SERVICE_STATUS[$service_name]}",
  "http_code": "${SERVICE_HTTP_CODE[$service_name]}",
  "response_time_ms": "${SERVICE_RESPONSE_TIME[$service_name]}",
  "url": "$url",
  "error_message": "${SERVICE_ERROR_MESSAGE[$service_name]}",
  "timestamp": "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
}
EOF
        )
        JSON_RESULTS=$(echo "$JSON_RESULTS" | jq ". += [$json_entry]" 2>/dev/null || echo "$JSON_RESULTS")
    fi
}

# Generate detailed report
generate_report() {
    local report_file="health-check-report-$(date +%Y%m%d-%H%M%S).txt"
    
    cat > "$report_file" << EOF
DLAP HF Spaces Health Check Report
===================================
Date: $(date)
Environment: $ENVIRONMENT
HF Organization: $HF_ORG

Summary:
- Total Services: $TOTAL_COUNT
- Healthy: $HEALTHY_COUNT
- Unhealthy: $UNHEALTHY_COUNT
- Unreachable: $UNREACHABLE_COUNT
- Success Rate: $(( HEALTHY_COUNT * 100 / TOTAL_COUNT ))%

Service Details:
================
EOF
    
    # Add detailed service information
    for service in "${!SERVICE_STATUS[@]}"; do
        cat >> "$report_file" << EOF
$service:
  Status: ${SERVICE_STATUS[$service]}
  HTTP Code: ${SERVICE_HTTP_CODE[$service]}
  Response Time: ${SERVICE_RESPONSE_TIME[$service]}ms
  Error: ${SERVICE_ERROR_MESSAGE[$service]}

EOF
    done
    
    cat >> "$report_file" << EOF
Recommendations:
================
EOF
    
    if [[ $UNHEALTHY_COUNT -gt 0 || $UNREACHABLE_COUNT -gt 0 ]]; then
        cat >> "$report_file" << EOF
⚠️  Action Required:
- $UNHEALTHY_COUNT services are unhealthy
- $UNREACHABLE_COUNT services are unreachable
- Check HF Spaces dashboard for detailed logs
- Verify service configurations and deployments
- Consider redeploying problematic services

Troubleshooting Steps:
1. Check service logs in HF Spaces dashboard
2. Verify environment variables and secrets
3. Check Docker image availability in registry
4. Validate network connectivity
5. Review service-specific configuration files
EOF
    else
        cat >> "$report_file" << EOF
✅ All systems operational
- All services are healthy and responding
- No immediate action required
- Continue regular monitoring

Maintenance Recommendations:
1. Monitor response times for performance trends
2. Set up automated alerting for service failures
3. Review logs for any warning messages
4. Plan regular health check automation
EOF
    fi
    
    echo "$report_file"
}

# Help function
show_help() {
    cat << EOF
DLAP HF Spaces Comprehensive Health Check Script
=================================================

Usage: $0 [environment] [options]

Arguments:
  environment    Target environment (dev/test/prod) [default: dev]

Options:
  --verbose      Enable verbose output with debug information
  --json         Output results in JSON format
  --dry-run      Show what would be checked without making requests
  -h, --help     Show this help message

Examples:
  $0                    # Check dev environment
  $0 test              # Check test environment
  $0 prod --verbose    # Check prod environment with verbose output
  $0 dev --json        # Check dev environment with JSON output
  $0 test --dry-run    # Dry run for test environment

Service Types Monitored:
  - Microservices: 9 services with /actuator/health endpoints
  - CI/CD Components: 3 components with /health endpoints  
  - Frontend Services: 1 service with / endpoint

Output Codes:
  ✅ HEALTHY      - Service is up and responding correctly
  ⚠️  UNHEALTHY   - Service is up but reports unhealthy status
  ❌ UNREACHABLE  - Service cannot be reached (network/DNS issues)
  🔒 CLIENT ERROR - HTTP 4xx client error (authentication, not found, etc.)
  💥 SERVER ERROR - HTTP 5xx server error (internal server issues)
  ❓ UNKNOWN      - Unexpected response or status

Environment Variables:
  HF_ORG         - Hugging Face organization name [default: dalabsai]
  TIMEOUT        - Request timeout in seconds [default: 30]

EOF
}

# Main execution
main() {
    if [[ "$JSON_OUTPUT" != "true" ]]; then
        echo -e "${BOLD}🏥 DLAP HF Spaces Health Check - $ENVIRONMENT Environment${NC}"
        echo "=================================================================="
        echo "Timestamp: $(date)"
        echo "Organization: $HF_ORG"
        echo "Timeout: ${TIMEOUT}s"
        if [[ "$DRY_RUN" == "true" ]]; then
            echo -e "${YELLOW}Mode: DRY RUN (no actual requests will be made)${NC}"
        fi
        echo
    fi
    
    # Check microservices
    if [[ "$JSON_OUTPUT" != "true" ]]; then
        echo -e "${BOLD}Microservices:${NC}"
    fi
    for service in "${!MICROSERVICES_CONFIG[@]}"; do
        check_service_health "$service" "microservice" "${MICROSERVICES_CONFIG[$service]}"
    done
    
    if [[ "$JSON_OUTPUT" != "true" ]]; then
        echo
        echo -e "${BOLD}CI/CD Components:${NC}"
    fi
    for service in "${!CICD_CONFIG[@]}"; do
        check_service_health "$service" "cicd" "${CICD_CONFIG[$service]}"
    done
    
    if [[ "$JSON_OUTPUT" != "true" ]]; then
        echo
        echo -e "${BOLD}Frontend Services:${NC}"
    fi
    for service in "${!FRONTEND_CONFIG[@]}"; do
        check_service_health "$service" "frontend" "${FRONTEND_CONFIG[$service]}"
    done
    
    # Output results
    if [[ "$JSON_OUTPUT" == "true" ]]; then
        # JSON output
        local json_summary=$(cat << EOF
{
  "summary": {
    "environment": "$ENVIRONMENT",
    "timestamp": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
    "total_services": $TOTAL_COUNT,
    "healthy": $HEALTHY_COUNT,
    "unhealthy": $UNHEALTHY_COUNT,
    "unreachable": $UNREACHABLE_COUNT,
    "success_rate": $(( HEALTHY_COUNT * 100 / TOTAL_COUNT ))
  },
  "services": $JSON_RESULTS
}
EOF
        )
        echo "$json_summary" | jq '.' 2>/dev/null || echo "$json_summary"
    else
        # Human-readable output
        echo
        echo -e "${BOLD}📊 Health Check Summary${NC}"
        echo "======================"
        echo "Total Services: $TOTAL_COUNT"
        echo -e "Healthy: ${GREEN}$HEALTHY_COUNT${NC}"
        if [[ $UNHEALTHY_COUNT -gt 0 ]]; then
            echo -e "Unhealthy: ${YELLOW}$UNHEALTHY_COUNT${NC}"
        else
            echo -e "Unhealthy: ${GREEN}$UNHEALTHY_COUNT${NC}"
        fi
        if [[ $UNREACHABLE_COUNT -gt 0 ]]; then
            echo -e "Unreachable: ${RED}$UNREACHABLE_COUNT${NC}"
        else
            echo -e "Unreachable: ${GREEN}$UNREACHABLE_COUNT${NC}"
        fi
        echo "Success Rate: $(( HEALTHY_COUNT * 100 / TOTAL_COUNT ))%"
        echo
        
        if [[ "$DRY_RUN" != "true" ]]; then
            # Generate detailed report
            local report_file
            report_file=$(generate_report)
            log_info "Detailed report saved to: $report_file"
            echo
        fi
        
        # Final status
        if [[ $UNHEALTHY_COUNT -eq 0 && $UNREACHABLE_COUNT -eq 0 ]]; then
            log_success "🎉 All systems operational!"
            if [[ "$VERBOSE" == "true" ]]; then
                log_info "All $TOTAL_COUNT services are healthy and responding within acceptable parameters"
            fi
            exit 0
        else
            local failed_count=$((UNHEALTHY_COUNT + UNREACHABLE_COUNT))
            log_warning "⚠️  $failed_count out of $TOTAL_COUNT services need attention"
            
            if [[ "$VERBOSE" == "true" ]]; then
                echo
                log_info "Troubleshooting recommendations:"
                log_info "1. Check HF Spaces dashboard for detailed service logs"
                log_info "2. Verify service configurations and environment variables"
                log_info "3. Check Docker registry for image availability"
                log_info "4. Consider redeploying problematic services"
                log_info "5. Review network connectivity and DNS resolution"
            fi
            exit 1
        fi
    fi
}

# Check if jq is available for JSON output
if [[ "$JSON_OUTPUT" == "true" ]] && ! command -v jq &> /dev/null; then
    log_warning "jq is not installed. JSON output may not be formatted properly."
fi

# Run main function
main
