@echo off
setlocal enabledelayedexpansion

REM =============================================================================
REM DLAP HF Spaces Comprehensive Health Check Script - Windows
REM =============================================================================
REM This script checks the health of all DLAP microservices, CI/CD components,
REM and frontend services deployed on HF Spaces with detailed reporting
REM Usage: health-check-all-services.bat [environment] [--verbose] [--dry-run]
REM =============================================================================

REM Configuration
set "ENVIRONMENT=%1"
if "%ENVIRONMENT%"=="" set "ENVIRONMENT=dev"
set "VERBOSE=false"
set "DRY_RUN=false"
set "HF_ORG=dalabsai"
set "TIMEOUT=30"
set "MAX_RETRIES=3"

REM Parse command line arguments
:parse_args
if "%2"=="--verbose" (
    set "VERBOSE=true"
    shift
    goto parse_args
)
if "%2"=="--dry-run" (
    set "DRY_RUN=true"
    shift
    goto parse_args
)
if "%2"=="--help" (
    goto show_help
)
if "%2"=="-h" (
    goto show_help
)

REM Service definitions
set "MICROSERVICES=da-discovery da-catalog da-policyengine da-reporting da-autolabel da-autoarchival da-autodelete da-autocompliance da-admin-service"
set "CICD_COMPONENTS=dlap-build-pipeline dlap-deployment-manager dlap-test-runner"
set "FRONTEND_SERVICES=new-ui"

REM Health check counters
set /a "HEALTHY_COUNT=0"
set /a "UNHEALTHY_COUNT=0"
set /a "UNREACHABLE_COUNT=0"
set /a "TOTAL_COUNT=0"

REM Color codes for Windows (using echo with special characters)
REM Note: Windows batch doesn't support ANSI colors natively, but we can use conditional formatting

echo [INFO] DLAP Health Check for %ENVIRONMENT% environment
echo ==============================================================
echo Timestamp: %date% %time%
echo Organization: %HF_ORG%
echo Timeout: %TIMEOUT%s
if "%DRY_RUN%"=="true" (
    echo Mode: DRY RUN ^(no actual requests will be made^)
)
echo.

REM Check if curl is available
curl --version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] curl is required but not found
    echo Please install curl or use Git Bash with the .sh version
    echo You can install curl from: https://curl.se/windows/
    pause
    exit /b 1
)

REM Function to check service health with retry logic
:check_service_health
set "service_name=%1"
set "service_type=%2"
set "endpoint=%3"
set /a "TOTAL_COUNT+=1"
set /a "retry_count=0"

REM Construct URL based on service type
if "%service_type%"=="cicd" (
    set "url=https://%HF_ORG%-%service_name%.hf.space%endpoint%"
) else if "%service_type%"=="frontend" (
    set "url=https://%HF_ORG%-%service_name%-%ENVIRONMENT%.hf.space%endpoint%"
) else (
    set "url=https://%HF_ORG%-%service_name%-%ENVIRONMENT%.hf.space%endpoint%"
)

if "%DRY_RUN%"=="true" (
    echo   %service_name%... DRY-RUN ^(would check: !url!^)
    goto :eof
)

if "%VERBOSE%"=="true" (
    echo [DEBUG] Checking %service_name% at !url!
)

REM Retry loop
:retry_loop
if !retry_count! geq %MAX_RETRIES% goto end_retry

REM Get current time for response time calculation (simplified)
set "start_time=%time%"

REM Perform health check with timeout
curl -s -o nul -w "%%{http_code}" --connect-timeout %TIMEOUT% --max-time 60 "!url!" > temp_status.txt 2>nul
if errorlevel 1 (
    set "http_status=000"
) else (
    set /p http_status=<temp_status.txt
)

del temp_status.txt 2>nul

if "%VERBOSE%"=="true" (
    echo [DEBUG] HTTP Status: !http_status!
)

REM Determine service status based on HTTP code
if "!http_status!"=="000" (
    set "service_status=unreachable"
    set "status_display=UNREACHABLE"
    set "status_symbol=X"
    set /a "retry_count+=1"
    if !retry_count! lss %MAX_RETRIES% (
        if "%VERBOSE%"=="true" (
            echo [DEBUG] Retry !retry_count!/%MAX_RETRIES% for %service_name%
        )
        timeout /t 5 >nul
        goto retry_loop
    )
    set /a "UNREACHABLE_COUNT+=1"
) else if "!http_status!"=="200" (
    REM For microservices, we should check the actual health response
    REM Simplified check - just consider 200 as healthy for now
    if "%service_type%"=="microservice" (
        REM Get the actual response to check for "UP" status
        curl -s --connect-timeout %TIMEOUT% --max-time 60 "!url!" > temp_response.txt 2>nul
        findstr /C:"\"status\":\"UP\"" temp_response.txt >nul 2>&1
        if errorlevel 1 (
            findstr /C:"\"status\":\"DOWN\"" temp_response.txt >nul 2>&1
            if not errorlevel 1 (
                set "service_status=unhealthy"
                set "status_display=UNHEALTHY"
                set "status_symbol=!"
                set /a "UNHEALTHY_COUNT+=1"
            ) else (
                set "service_status=unknown"
                set "status_display=UNKNOWN"
                set "status_symbol=?"
                set /a "UNHEALTHY_COUNT+=1"
            )
        ) else (
            set "service_status=healthy"
            set "status_display=HEALTHY"
            set "status_symbol=√"
            set /a "HEALTHY_COUNT+=1"
        )
        del temp_response.txt 2>nul
    ) else (
        set "service_status=healthy"
        set "status_display=HEALTHY"
        set "status_symbol=√"
        set /a "HEALTHY_COUNT+=1"
    )
    goto end_retry
) else if !http_status! geq 400 (
    if !http_status! lss 500 (
        set "service_status=client-error"
        set "status_display=CLIENT ERROR"
        set "status_symbol=!"
        set /a "UNHEALTHY_COUNT+=1"
    ) else (
        set "service_status=server-error"
        set "status_display=SERVER ERROR"
        set "status_symbol=X"
        set /a "retry_count+=1"
        if !retry_count! lss %MAX_RETRIES% (
            if "%VERBOSE%"=="true" (
                echo [DEBUG] Server error, retry !retry_count!/%MAX_RETRIES% for %service_name%
            )
            timeout /t 5 >nul
            goto retry_loop
        )
        set /a "UNHEALTHY_COUNT+=1"
    )
    goto end_retry
) else (
    set "service_status=unknown"
    set "status_display=UNKNOWN"
    set "status_symbol=?"
    set /a "UNHEALTHY_COUNT+=1"
)

:end_retry
REM Display result with status indicator
echo   %-25s [!status_symbol!] !status_display! ^(HTTP !http_status!^) | %service_name%

if "%VERBOSE%"=="true" (
    echo       URL: !url!
    if "!service_status!"=="unhealthy" echo       Reason: Service reports unhealthy status
    if "!service_status!"=="unreachable" echo       Reason: Connection failed after %MAX_RETRIES% retries
    if "!service_status!"=="client-error" echo       Reason: HTTP !http_status! Client Error
    if "!service_status!"=="server-error" echo       Reason: HTTP !http_status! Server Error
)

goto :eof

REM Main execution
echo Microservices:
echo --------------
for %%s in (%MICROSERVICES%) do (
    call :check_service_health "%%s" "microservice" "/actuator/health"
)

echo.
echo CI/CD Components:
echo -----------------
for %%s in (%CICD_COMPONENTS%) do (
    call :check_service_health "%%s" "cicd" "/health"
)

echo.
echo Frontend Services:
echo ------------------
for %%s in (%FRONTEND_SERVICES%) do (
    call :check_service_health "%%s" "frontend" "/"
)

echo.
echo ================================================================
echo Health Check Summary
echo ================================================================
echo Total Services: %TOTAL_COUNT%
echo Healthy: %HEALTHY_COUNT%
echo Unhealthy: %UNHEALTHY_COUNT%
echo Unreachable: %UNREACHABLE_COUNT%

REM Calculate success rate
set /a "success_rate=HEALTHY_COUNT * 100 / TOTAL_COUNT"
echo Success Rate: %success_rate%%%
echo.

REM Generate detailed report
set "report_file=health-check-report-%date:~-4%%date:~4,2%%date:~7,2%-%time:~0,2%%time:~3,2%%time:~6,2%.txt"
set "report_file=!report_file: =0!"

echo Generating detailed report...
(
echo DLAP HF Spaces Health Check Report - Windows
echo ==============================================
echo Date: %date% %time%
echo Environment: %ENVIRONMENT%
echo HF Organization: %HF_ORG%
echo.
echo Summary:
echo - Total Services: %TOTAL_COUNT%
echo - Healthy: %HEALTHY_COUNT%
echo - Unhealthy: %UNHEALTHY_COUNT%
echo - Unreachable: %UNREACHABLE_COUNT%
echo - Success Rate: %success_rate%%%
echo.
echo Legend:
echo [√] HEALTHY      - Service is up and responding correctly
echo [!] UNHEALTHY    - Service is up but reports unhealthy status
echo [X] UNREACHABLE  - Service cannot be reached
echo [?] UNKNOWN      - Unexpected response or status
echo.
echo Troubleshooting:
if %UNHEALTHY_COUNT% gtr 0 (
    echo - %UNHEALTHY_COUNT% services are unhealthy
)
if %UNREACHABLE_COUNT% gtr 0 (
    echo - %UNREACHABLE_COUNT% services are unreachable
)
if %UNHEALTHY_COUNT% gtr 0 (
    echo.
    echo Recommended Actions:
    echo 1. Check HF Spaces dashboard for detailed logs
    echo 2. Verify service configurations and deployments
    echo 3. Check Docker image availability in registry
    echo 4. Consider redeploying problematic services
    echo 5. Review environment variables and secrets
) else (
    echo.
    echo All systems operational - no immediate action required
)
echo.
echo Support Resources:
echo - HF Spaces Dashboard: https://huggingface.co/spaces
echo - DLAP Documentation: docs/
echo - Health Check Script: deployment/scripts/health-check-all-services.bat
) > "%report_file%"

echo [SUCCESS] Detailed report saved to: %report_file%
echo.

REM Final status determination
set /a "failed_count=UNHEALTHY_COUNT + UNREACHABLE_COUNT"
if %failed_count% equ 0 (
    echo [SUCCESS] All systems operational!
    echo All %TOTAL_COUNT% services are healthy and responding correctly.
    if "%VERBOSE%"=="true" (
        echo.
        echo Next steps:
        echo - Continue regular monitoring
        echo - Set up automated alerting for service failures
        echo - Review logs for any warning messages
    )
    exit /b 0
) else (
    echo [WARNING] %failed_count% out of %TOTAL_COUNT% services need attention
    if "%VERBOSE%"=="true" (
        echo.
        echo Troubleshooting steps:
        echo 1. Check HF Spaces dashboard: https://huggingface.co/spaces
        echo 2. Review service logs for error messages
        echo 3. Verify environment variables and configuration
        echo 4. Check Docker registry for image availability
        echo 5. Consider redeploying problematic services
        echo.
        echo For detailed analysis, review the generated report: %report_file%
    )
    exit /b 1
)

:show_help
echo DLAP HF Spaces Comprehensive Health Check Script - Windows
echo ===========================================================
echo.
echo Usage: %~nx0 [environment] [options]
echo.
echo Arguments:
echo   environment    Target environment ^(dev/test/prod^) [default: dev]
echo.
echo Options:
echo   --verbose      Enable verbose output with debug information
echo   --dry-run      Show what would be checked without making requests
echo   -h, --help     Show this help message
echo.
echo Examples:
echo   %~nx0                    # Check dev environment
echo   %~nx0 test              # Check test environment
echo   %~nx0 prod --verbose    # Check prod environment with verbose output
echo   %~nx0 test --dry-run    # Dry run for test environment
echo.
echo Service Types Monitored:
echo   - Microservices: 9 services with /actuator/health endpoints
echo   - CI/CD Components: 3 components with /health endpoints
echo   - Frontend Services: 1 service with / endpoint
echo.
echo Output Codes:
echo   [√] HEALTHY      - Service is up and responding correctly
echo   [!] UNHEALTHY    - Service is up but reports unhealthy status
echo   [X] UNREACHABLE  - Service cannot be reached
echo   [?] UNKNOWN      - Unexpected response or status
echo.
echo Requirements:
echo   - curl must be installed and available in PATH
echo   - Internet connectivity to reach HF Spaces
echo   - Valid service deployments on Hugging Face Spaces
echo.
echo Environment Variables:
echo   HF_ORG         - Hugging Face organization name [default: dalabsai]
echo   TIMEOUT        - Request timeout in seconds [default: 30]
echo.
exit /b 0 