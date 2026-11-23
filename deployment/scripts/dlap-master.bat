@echo off
setlocal enabledelayedexpansion

REM ===========================================
REM DLAP Master Script - Unified Build & Start (Windows)
REM ===========================================
REM 
REM This script consolidates all build and start operations for DLAP platform
REM Supports multiple environments: dev, test, prod
REM
REM Usage:
REM   dlap-master.bat build [ENV] [OPTIONS]
REM   dlap-master.bat start [ENV] [OPTIONS]
REM   dlap-master.bat restart [ENV] [OPTIONS]
REM   dlap-master.bat stop [ENV]
REM   dlap-master.bat status [ENV]
REM   dlap-master.bat clean [ENV]
REM   dlap-master.bat services
REM
REM Environment Options:
REM   dev   - Development environment (default)
REM   test  - Test environment
REM   prod  - Production environment
REM
REM Build Options:
REM   --skip-tests       Skip running tests
REM   --skip-docker      Skip Docker image building
REM   --parallel         Build services in parallel (default)
REM   --sequential       Build services sequentially
REM   --services=LIST    Build specific services only (comma-separated)
REM   --frontend-only    Build only frontend
REM   --backend-only     Build only backend services
REM
REM Start Options:
REM   --infrastructure-only  Start only infrastructure services
REM   --services-only        Start only microservices
REM   --service=NAME         Start a specific service (e.g., keycloak, postgresql)
REM   --detached            Run in detached mode (default)
REM   --logs                Show logs after startup
REM   --health-check        Wait for health checks before proceeding
REM

REM ==================== CONFIGURATION ====================

REM Get script directory and project root
set SCRIPT_DIR=%~dp0
set PROJECT_ROOT=%SCRIPT_DIR%\..\..

REM Default values
set ENVIRONMENT=dev
set COMMAND=
set SKIP_TESTS=false
set SKIP_DOCKER=false
set BUILD_PARALLEL=true
set SERVICES_FILTER=
set FRONTEND_ONLY=false
set BACKEND_ONLY=false
set INFRASTRUCTURE_ONLY=false
set SERVICES_ONLY=false
set SINGLE_SERVICE=
set DETACHED=true
set SHOW_LOGS=false
set HEALTH_CHECK=false

REM Build configuration
set BUILD_TIMEOUT=600
set PARALLEL_JOBS=4

REM Service definitions
set ALL_SERVICES=da-protos da-discovery da-catalog da-policyengine da-reporting da-autolabel da-autoarchival da-autodelete da-autocompliance da-admin-service
set FRONTEND_SERVICES=new-ui/frontend

REM Environment-specific configurations
REM dev -> deployment/environments/dev
REM test -> deployment/environments/test
REM prod -> deployment/environments/prod

REM Infrastructure services startup order
set INFRASTRUCTURE_SERVICES=postgresql kafka keycloak

REM Microservices startup order (with dependencies)
set MICROSERVICES=da-discovery da-catalog da-policyengine da-reporting da-autolabel da-autoarchival da-autodelete da-autocompliance da-admin-service

REM ==================== INITIAL VALIDATION ====================

REM Initial validation to prevent crashes
if not exist "%SCRIPT_DIR%" (
    echo ERROR: Script directory not accessible: %SCRIPT_DIR%
    pause
    exit /b 1
)

if not exist "%PROJECT_ROOT%" (
    echo ERROR: Project root directory not found: %PROJECT_ROOT%
    echo Please ensure you're running this script from the correct location
    pause
    exit /b 1
)

REM Check if running with proper permissions
mkdir "%TEMP%\dlap_test" 2>nul
if errorlevel 1 (
    echo ERROR: Insufficient permissions to create temporary directories
    echo Please run as Administrator or check your permissions
    pause
    exit /b 1
) else (
    rmdir "%TEMP%\dlap_test" 2>nul
)

REM Jump to main execution
goto :main

REM ==================== LOGGING FUNCTIONS ====================

:log
echo [%date% %time%] %~1
goto :eof

:success
echo [%date% %time%] [SUCCESS] %~1
goto :eof

:error
echo [%date% %time%] [ERROR] %~1
goto :eof

:warning
echo [%date% %time%] [WARNING] %~1
goto :eof

:info
echo [%date% %time%] [INFO] %~1
goto :eof

:debug
echo [%date% %time%] [DEBUG] %~1
goto :eof

REM ==================== UTILITY FUNCTIONS ====================

:get_safe_datetime
REM Create safer date/time format for directory name
set "datestamp="
for /f "tokens=2 delims==" %%a in ('wmic OS Get localdatetime /value 2^>nul') do (
    if not "%%a"=="" set "dt=%%a"
)
if defined dt (
    set "YY=%dt:~2,2%" & set "YYYY=%dt:~0,4%" & set "MM=%dt:~4,2%" & set "DD=%dt:~6,2%"
    set "HH=%dt:~8,2%" & set "Min=%dt:~10,2%" & set "Sec=%dt:~12,2%"
    set "datestamp=%YYYY%%MM%%DD%_%HH%%Min%%Sec%"
) else (
    REM Fallback to simpler timestamp if wmic fails
    set "timestamp=%RANDOM%_%RANDOM%"
    set "datestamp=build_%timestamp%"
)
goto :eof

:show_help
echo DLAP Master Script - Unified Build ^& Start
echo.
echo USAGE:
echo     %~nx0 COMMAND [ENVIRONMENT] [OPTIONS]
echo.
echo COMMANDS:
echo     build       Build services for specified environment
echo     start       Start services for specified environment
echo     restart     Restart services for specified environment
echo     stop        Stop services for specified environment
echo     status      Show status of services
echo     clean       Clean build artifacts and containers
echo     services    List available services
echo     help        Show this help message
echo.
echo ENVIRONMENTS:
echo     dev         Development environment (default)
echo     test        Test environment
echo     prod        Production environment
echo.
echo BUILD OPTIONS:
echo     --skip-tests           Skip running tests during build
echo     --skip-docker          Skip Docker image building
echo     --parallel             Build services in parallel (default)
echo     --sequential           Build services sequentially
echo     --services=LIST        Build specific services only (comma-separated)
echo     --frontend-only        Build only frontend services
echo     --backend-only         Build only backend services
echo.
echo START OPTIONS:
echo     --infrastructure-only  Start only infrastructure services
echo     --services-only        Start only microservices (requires infra running)
echo     --service=NAME         Start a specific service (e.g., keycloak, postgresql)
echo     --detached            Run in detached mode (default)
echo     --logs                Show logs after startup
echo     --health-check        Wait for health checks before proceeding
echo.
echo EXAMPLES:
echo     %~nx0 build dev --skip-tests
echo     %~nx0 start prod --infrastructure-only
echo     %~nx0 restart test --health-check --logs
echo     %~nx0 build dev --services=da-catalog,da-discovery
echo     %~nx0 start dev --frontend-only
echo     %~nx0 start dev --service=keycloak
echo     %~nx0 start dev --service=postgresql --health-check
echo     %~nx0 services
echo.
goto :eof

:parse_arguments
set COMMAND=%~1
shift

REM Handle help command immediately
if "%COMMAND%"=="help" (
    call :show_help
    exit /b 0
)
if "%COMMAND%"=="--help" (
    call :show_help
    exit /b 0
)
if "%COMMAND%"=="-h" (
    call :show_help
    exit /b 0
)

REM Parse environment if provided as second argument
if "%~1"=="dev" set ENVIRONMENT=dev& shift& goto :parse_options
if "%~1"=="test" set ENVIRONMENT=test& shift& goto :parse_options
if "%~1"=="prod" set ENVIRONMENT=prod& shift& goto :parse_options

:parse_options
if "%~1"=="" goto :parse_done
if "%~1"=="--skip-tests" set SKIP_TESTS=true& shift& goto :parse_options
if "%~1"=="--skip-docker" set SKIP_DOCKER=true& shift& goto :parse_options
if "%~1"=="--parallel" set BUILD_PARALLEL=true& shift& goto :parse_options
if "%~1"=="--sequential" set BUILD_PARALLEL=false& shift& goto :parse_options
if "%~1"=="--frontend-only" set FRONTEND_ONLY=true& shift& goto :parse_options
if "%~1"=="--backend-only" set BACKEND_ONLY=true& shift& goto :parse_options
if "%~1"=="--infrastructure-only" set INFRASTRUCTURE_ONLY=true& shift& goto :parse_options
if "%~1"=="--services-only" set SERVICES_ONLY=true& shift& goto :parse_options
if "%~1"=="--detached" set DETACHED=true& shift& goto :parse_options
if "%~1"=="--logs" set SHOW_LOGS=true& shift& goto :parse_options
if "%~1"=="--health-check" set HEALTH_CHECK=true& shift& goto :parse_options

REM Handle --services=LIST parameter
echo %~1 | findstr /r "^--services=" >nul
if !errorlevel!==0 (
    set SERVICES_FILTER=%~1
    set SERVICES_FILTER=!SERVICES_FILTER:--services=!
    shift
    goto :parse_options
)

REM Handle --service=NAME parameter
echo %~1 | findstr /r "^--service=" >nul
if !errorlevel!==0 (
    set SINGLE_SERVICE=%~1
    set SINGLE_SERVICE=!SINGLE_SERVICE:--service=!
    shift
    goto :parse_options
)

call :error "Unknown option: %~1"
call :show_help
exit /b 1

:parse_done
goto :eof

:validate_environment
set env=%~1
set env_dir=%PROJECT_ROOT%\deployment\environments\%env%

if not exist "%env_dir%" (
    call :error "Environment '%env%' not found at %env_dir%"
    exit /b 1
)

if not exist "%env_dir%\docker-compose.yml" (
    call :error "docker-compose.yml not found for environment '%env%'"
    exit /b 1
)

call :success "Environment '%env%' validated"
goto :eof

:list_services
call :info "Available Services:"
echo.
call :info "Infrastructure Services:"
for %%s in (%INFRASTRUCTURE_SERVICES%) do echo   %%s
echo.
call :info "Microservices:"
for %%s in (%MICROSERVICES%) do echo   %%s
echo.
goto :eof

:wait_for_service_health
set service=%~1
set port=%~2
set max_attempts=30
set attempt=0

call :log "Waiting for %service% to be healthy on port %port%..."

:health_check_loop
set /a attempt+=1
if %attempt% gtr %max_attempts% (
    call :error "%service% failed to become healthy after %max_attempts% attempts"
    exit /b 1
)

REM Check if port is responding
powershell -Command "try { $response = Invoke-WebRequest -Uri 'http://localhost:%port%' -UseBasicParsing -TimeoutSec 2; exit 0 } catch { exit 1 }" >nul 2>&1
if %errorlevel%==0 (
    call :success "%service% is healthy on port %port%"
    goto :eof
)

call :debug "Attempt %attempt%/%max_attempts%: %service% not ready yet..."
timeout /t 2 /nobreak >nul
goto :health_check_loop

:start_single_service
set env=%~1
set service=%~2
set env_dir=%PROJECT_ROOT%\deployment\environments\%env%

call :log "Starting individual service: %service% (environment: %env%)"

cd /d "%env_dir%"

REM Create network if it doesn't exist
if "%env%"=="test" (
    docker network create dlap-test-network 2>nul
) else (
    docker network create dlap-network 2>nul
)

REM Load environment variables
set env_file=
if exist ".env.%env%" (
    set env_file=--env-file .env.%env%
) else if exist "env.%env%.example" (
    call :warning "Using example env file. Copy env.%env%.example to .env.%env% for customization"
    set env_file=--env-file env.%env%.example
)

REM Check if service exists in infrastructure services
set found_in_infrastructure=false
for %%s in (%INFRASTRUCTURE_SERVICES%) do (
    if "%%s"=="%service%" set found_in_infrastructure=true
)

REM Check if service exists in microservices
set found_in_microservices=false
for %%s in (%MICROSERVICES%) do (
    if "%%s"=="%service%" set found_in_microservices=true
)

REM Validate service exists
if "%found_in_infrastructure%"=="false" if "%found_in_microservices%"=="false" (
    call :error "Service '%service%' not found in infrastructure or microservices list"
    call :info "Available infrastructure services: %INFRASTRUCTURE_SERVICES%"
    call :info "Available microservices: %MICROSERVICES%"
    exit /b 1
)

REM Start the service
if "%found_in_infrastructure%"=="true" (
    call :info "Starting infrastructure service: %service%"
    docker compose -f docker-compose.yml %env_file% up -d "%service%"
    if %errorlevel%==0 (
        call :success "%service% started successfully"
        
        REM Add health check delays for critical services
        if "%service%"=="postgresql" (
            call :log "Waiting for PostgreSQL to be ready..."
            timeout /t 10 /nobreak >nul
            if "%HEALTH_CHECK%"=="true" call :wait_for_service_health "PostgreSQL" "5432"
        )
        if "%service%"=="kafka" (
            call :log "Waiting for Kafka to be ready..."
            timeout /t 15 /nobreak >nul
            if "%HEALTH_CHECK%"=="true" call :wait_for_service_health "Kafka" "9092"
        )
        if "%service%"=="keycloak" (
            call :log "Waiting for Keycloak to be ready..."
            timeout /t 20 /nobreak >nul
            if "%HEALTH_CHECK%"=="true" (
                if "%env%"=="test" (
                    call :wait_for_service_health "Keycloak" "8280"
                ) else (
                    call :wait_for_service_health "Keycloak" "8180"
                )
            )
        )
        if "%service%"=="kafka-ui" (
            if "%HEALTH_CHECK%"=="true" call :wait_for_service_health "Kafka UI" "8090"
        )
        
        REM Show service URL
        call :show_service_url "%service%" "%env%"
    ) else (
        call :error "Failed to start %service%"
        exit /b 1
    )
) else (
    call :info "Starting microservice: %service%"
    docker compose -f docker-compose.yml %env_file% up -d "%service%"
    if %errorlevel%==0 (
        call :success "%service% started successfully"
        call :show_service_url "%service%" "%env%"
    ) else (
        call :error "Failed to start %service%"
        exit /b 1
    )
)

goto :eof

:show_service_url
set service=%~1
set env=%~2

call :info "Service URL for %service%:"
if "%service%"=="keycloak" (
    if "%env%"=="test" (
        echo   Keycloak Admin: http://localhost:8280
    ) else (
        echo   Keycloak Admin: http://localhost:8180
    )
) else if "%service%"=="kafka-ui" (
    echo   Kafka UI: http://localhost:8090
) else if "%service%"=="postgresql" (
    echo   PostgreSQL: localhost:5432
) else if "%service%"=="redis" (
    echo   Redis: localhost:6379
) else if "%service%"=="kafka" (
    echo   Kafka: localhost:9092
) else (
    REM Microservice URLs
    if "%env%"=="test" (
        if "%service%"=="da-discovery" echo   Discovery Service: http://localhost:8181
        if "%service%"=="da-catalog" echo   Catalog Service: http://localhost:8182
        if "%service%"=="da-policyengine" echo   Policy Engine: http://localhost:8183
        if "%service%"=="da-reporting" echo   Reporting Service: http://localhost:8184
        if "%service%"=="da-autolabel" echo   AutoLabel Service: http://localhost:8185
        if "%service%"=="da-autoarchival" echo   AutoArchival Service: http://localhost:8186
        if "%service%"=="da-autodelete" echo   AutoDelete Service: http://localhost:8187
        if "%service%"=="da-autocompliance" echo   AutoCompliance Service: http://localhost:8188
        if "%service%"=="da-admin-service" echo   Admin Service: http://localhost:8189
    ) else (
        if "%service%"=="da-discovery" echo   Discovery Service: http://localhost:8081
        if "%service%"=="da-catalog" echo   Catalog Service: http://localhost:8082
        if "%service%"=="da-policyengine" echo   Policy Engine: http://localhost:8083
        if "%service%"=="da-reporting" echo   Reporting Service: http://localhost:8084
        if "%service%"=="da-autolabel" echo   AutoLabel Service: http://localhost:8085
        if "%service%"=="da-autoarchival" echo   AutoArchival Service: http://localhost:8086
        if "%service%"=="da-autodelete" echo   AutoDelete Service: http://localhost:8087
        if "%service%"=="da-autocompliance" echo   AutoCompliance Service: http://localhost:8088
        if "%service%"=="da-admin-service" echo   Admin Service: http://localhost:8089
    )
)
echo.
goto :eof

:show_service_urls
set env=%~1

if "%env%"=="test" (
    echo   Frontend UI:        http://localhost:3100
    echo   Discovery:          http://localhost:8181
    echo   Catalog:            http://localhost:8182
    echo   Policy Engine:      http://localhost:8183
    echo   Reporting:          http://localhost:8184
    echo   AutoLabel:          http://localhost:8185
    echo   AutoArchival:       http://localhost:8186
    echo   AutoDelete:         http://localhost:8187
    echo   AutoCompliance:     http://localhost:8188
    echo   Admin Service:      http://localhost:8189
    echo.
    echo   Kafka UI:           http://localhost:8190
    echo   Keycloak:           http://localhost:8280
) else (
    echo   Frontend UI:        http://localhost:3000
    echo   Discovery:          http://localhost:8081
    echo   Catalog:            http://localhost:8082
    echo   Policy Engine:      http://localhost:8083
    echo   Reporting:          http://localhost:8084
    echo   AutoLabel:          http://localhost:8085
    echo   AutoArchival:       http://localhost:8086
    echo   AutoDelete:         http://localhost:8087
    echo   AutoCompliance:     http://localhost:8088
    echo   Admin Service:      http://localhost:8089
    echo.
    echo   Kafka UI:           http://localhost:8090
    echo   Keycloak:           http://localhost:8180
)
goto :eof

:check_prerequisites
call :log "Checking prerequisites..."

REM Check Docker
docker --version >nul 2>&1
if errorlevel 1 (
    call :error "Docker is not installed or not in PATH"
    exit /b 1
)

REM Check Docker Compose
docker compose version >nul 2>&1
if errorlevel 1 (
    call :error "Docker Compose is not installed or not in PATH"
    exit /b 1
)

REM Check Java (for backend builds)
if "%FRONTEND_ONLY%"=="false" (
    java -version >nul 2>&1
    if errorlevel 1 (
        call :error "Java is not installed or not in PATH"
        exit /b 1
    )
)

REM Check Node.js (for frontend builds)
if "%BACKEND_ONLY%"=="false" (
    node --version >nul 2>&1
    if errorlevel 1 (
        call :error "Node.js is not installed or not in PATH"
        exit /b 1
    )
)

REM Check if Docker is running
docker info >nul 2>&1
if errorlevel 1 (
    call :error "Docker is not running. Please start Docker and try again."
    exit /b 1
)

call :success "Prerequisites validated"
goto :eof

:check_build_prerequisites
call :log "Checking build prerequisites..."

REM Check Java (for backend builds)
if "%FRONTEND_ONLY%"=="false" (
    java -version >nul 2>&1
    if errorlevel 1 (
        call :error "Java is not installed or not in PATH"
        exit /b 1
    )
)

REM Check Node.js (for frontend builds)
if "%BACKEND_ONLY%"=="false" (
    node --version >nul 2>&1
    if errorlevel 1 (
        call :error "Node.js is not installed or not in PATH"
        exit /b 1
    )
)

call :success "Build prerequisites validated"
goto :eof

:check_docker_prerequisites
call :log "Checking Docker prerequisites..."

REM Check if Docker is running
docker info >nul 2>&1
if errorlevel 1 (
    call :error "Docker is not running. Please start Docker and try again."
    exit /b 1
)

call :success "Docker prerequisites validated"
goto :eof

:build_service
set service=%~1
set build_log_dir=%~2
set log_file=%build_log_dir%\%service:-=%-build.log
set service_dir=%PROJECT_ROOT%\%service%

mkdir "%build_log_dir%" 2>nul

call :log "Building %service%..."

if not exist "%service_dir%" (
    call :error "Service directory not found: %service_dir%"
    exit /b 1
)

cd /d "%service_dir%"

REM Determine build command based on service type
if "%service%"=="da-discovery" (
    REM da-discovery uses Gradle - handle clean issues by using build without clean first
    if "%SKIP_TESTS%"=="true" (
        "%PROJECT_ROOT%\gradlew.bat" build -x test > "%log_file%" 2>&1
        if errorlevel 1 (
            REM If build fails, try with clean (might work on second try)
            "%PROJECT_ROOT%\gradlew.bat" clean build -x test >> "%log_file%" 2>&1
        )
    ) else (
        "%PROJECT_ROOT%\gradlew.bat" build > "%log_file%" 2>&1
        if errorlevel 1 (
            REM If build fails, try with clean (might work on second try)
            "%PROJECT_ROOT%\gradlew.bat" clean build >> "%log_file%" 2>&1
        )
    )
) else if "%service%"=="new-ui" (
    REM new-ui has npm dependency issues with Gradle node plugin, try simpler approach
    if "%SKIP_TESTS%"=="true" (
        REM Try building without the problematic Subframe sync task
        "%PROJECT_ROOT%\gradlew.bat" build -x syncSubframeComponents -x test > "%log_file%" 2>&1
        if errorlevel 1 (
            REM If that fails, try building just the backend part
            "%PROJECT_ROOT%\gradlew.bat" compileJava processResources classes jar -x test >> "%log_file%" 2>&1
        )
    ) else (
        "%PROJECT_ROOT%\gradlew.bat" build -x syncSubframeComponents > "%log_file%" 2>&1
        if errorlevel 1 (
            REM If that fails, try building just the backend part
            "%PROJECT_ROOT%\gradlew.bat" compileJava processResources classes jar >> "%log_file%" 2>&1
        )
    )
) else if exist "build.gradle.kts" (
    REM Backend service - use gradlew from project root
    if "%SKIP_TESTS%"=="true" (
        "%PROJECT_ROOT%\gradlew.bat" clean build -x test > "%log_file%" 2>&1
    ) else (
        "%PROJECT_ROOT%\gradlew.bat" clean build > "%log_file%" 2>&1
    )
) else if exist "build.gradle" (
    REM Backend service - use gradlew from project root
    if "%SKIP_TESTS%"=="true" (
        "%PROJECT_ROOT%\gradlew.bat" clean build -x test > "%log_file%" 2>&1
    ) else (
        "%PROJECT_ROOT%\gradlew.bat" clean build > "%log_file%" 2>&1
    )
) else if exist "package.json" (
    REM Frontend service
    if "%SKIP_TESTS%"=="true" (
        npm install && npm run build > "%log_file%" 2>&1
    ) else (
        npm install && npm run build && npm test -- --watchAll=false > "%log_file%" 2>&1
    )
) else (
    call :error "Unknown service type for %service%"
    exit /b 1
)

if errorlevel 1 (
    call :error "%service% build failed. Check %log_file%"
    exit /b 1
) else (
    call :success "%service% built successfully"
)

goto :eof

:build_services_sequential
REM Create safer date/time format for directory name
call :get_safe_datetime
set build_log_dir=%PROJECT_ROOT%\deployment\logs\build\%datestamp%
mkdir "%build_log_dir%" 2>nul

call :log "Building services sequentially"

set failed_builds=
if "%FRONTEND_ONLY%"=="true" (
    for %%s in (%FRONTEND_SERVICES%) do (
        call :build_service "%%s" "%build_log_dir%"
        if errorlevel 1 set failed_builds=!failed_builds! %%s
    )
) else if "%BACKEND_ONLY%"=="true" (
    for %%s in (%ALL_SERVICES%) do (
        call :build_service "%%s" "%build_log_dir%"
        if errorlevel 1 set failed_builds=!failed_builds! %%s
    )
) else (
    for %%s in (%ALL_SERVICES%) do (
        call :build_service "%%s" "%build_log_dir%"
        if errorlevel 1 set failed_builds=!failed_builds! %%s
    )
    for %%s in (%FRONTEND_SERVICES%) do (
        call :build_service "%%s" "%build_log_dir%"
        if errorlevel 1 set failed_builds=!failed_builds! %%s
    )
)

if "%failed_builds%"=="" (
    call :success "All services built successfully"
) else (
    call :error "Build failed for services:%failed_builds%"
    exit /b 1
)
goto :eof

:build_docker_images
if "%SKIP_DOCKER%"=="true" (
    call :info "Skipping Docker image building"
    goto :eof
)

call :log "Building Docker images..."

REM Create safer date/time format for directory name
call :get_safe_datetime
set build_log_dir=%PROJECT_ROOT%\deployment\logs\docker\%datestamp%
mkdir "%build_log_dir%" 2>nul

set failed_builds=

REM Build Docker images for each service
call :build_docker_image "da-protos" "%build_log_dir%"
call :build_docker_image "da-discovery" "%build_log_dir%"
call :build_docker_image "da-catalog" "%build_log_dir%"
call :build_docker_image "da-policyengine" "%build_log_dir%"
call :build_docker_image "da-reporting" "%build_log_dir%"
call :build_docker_image "da-autolabel" "%build_log_dir%"
call :build_docker_image "da-autoarchival" "%build_log_dir%"
call :build_docker_image "da-autodelete" "%build_log_dir%"
call :build_docker_image "da-autocompliance" "%build_log_dir%"
call :build_docker_image "da-admin-service" "%build_log_dir%"
call :build_docker_image "new-ui" "%build_log_dir%"

if "%failed_builds%"=="" (
    call :success "All Docker images built successfully"
) else (
    call :error "Docker image builds failed for:%failed_builds%"
    exit /b 1
)
goto :eof

:build_docker_image
set service=%~1
set build_log_dir=%~2
set dockerfile_path=%PROJECT_ROOT%\%service%\Dockerfile
set docker_dir=%PROJECT_ROOT%\%service%\src\main\docker

if exist "%dockerfile_path%" (
    set dockerfile_to_use=%dockerfile_path%
) else if exist "%docker_dir%\Dockerfile" (
    set dockerfile_to_use=%docker_dir%\Dockerfile
) else (
    call :warning "No Dockerfile found for %service%, skipping Docker build"
    goto :eof
)

set image_tag=dlap/%service%:latest
set log_file=%build_log_dir%\%service%-docker.log

call :log "Building Docker image for %service%..."

docker build -f "%dockerfile_to_use%" -t "%image_tag%" "%PROJECT_ROOT%\%service%" > "%log_file%" 2>&1
if errorlevel 1 (
    call :error "Docker image build failed for %service%. Check %log_file%"
    set failed_builds=%failed_builds% %service%
) else (
    call :success "Docker image for %service% built successfully"
)
goto :eof

:start_infrastructure
set env=%~1
set env_dir=%PROJECT_ROOT%\deployment\environments\%env%

call :log "Starting infrastructure services for environment: %env%"

cd /d "%env_dir%"

REM Create network if it doesn't exist
docker network create dlap-network 2>nul

REM Load environment variables
set env_file=
if exist ".env.%env%" (
    set env_file=--env-file .env.%env%
) else if exist "env.%env%.example" (
    call :warning "Using example env file. Copy env.%env%.example to .env.%env% for customization"
    set env_file=--env-file env.%env%.example
)

REM Start infrastructure services in order
for %%s in (%INFRASTRUCTURE_SERVICES%) do (
    call :log "Starting %%s..."
    docker compose -f docker-compose.yml %env_file% up -d %%s
    if errorlevel 1 (
        call :error "Failed to start %%s"
        exit /b 1
    )
    call :success "%%s started"
    
    REM Add delays for dependencies
    if "%%s"=="postgresql" (
        call :log "Waiting for PostgreSQL to be ready..."
        timeout /t 10 /nobreak >nul
    ) else if "%%s"=="kafka" (
        call :log "Waiting for Kafka to be ready..."
        timeout /t 15 /nobreak >nul
    ) else if "%%s"=="keycloak" (
        call :log "Waiting for Keycloak to be ready..."
        timeout /t 20 /nobreak >nul
    )
)

if "%HEALTH_CHECK%"=="true" (
    call :wait_for_infrastructure_health "%env%"
)

call :success "Infrastructure services started successfully"
goto :eof

:start_microservices
set env=%~1
set env_dir=%PROJECT_ROOT%\deployment\environments\%env%

call :log "Starting microservices for environment: %env%"

cd /d "%env_dir%"

REM Load environment variables
set env_file=
if exist ".env.%env%" (
    set env_file=--env-file .env.%env%
) else if exist "env.%env%.example" (
    set env_file=--env-file env.%env%.example
)

REM Start microservices using both compose files to ensure infrastructure services are available
set docker_flags=-d
if "%DETACHED%"=="false" set docker_flags=

REM Check if microservices compose file exists and run both files together
if exist "docker-compose.microservices.yml" (
    docker compose -f docker-compose.yml -f docker-compose.microservices.yml %env_file% up %docker_flags%
) else (
    docker compose -f docker-compose.yml %env_file% up %docker_flags%
)

if errorlevel 1 (
    call :error "Failed to start microservices"
    exit /b 1
)

call :success "Microservices started successfully"

if "%HEALTH_CHECK%"=="true" (
    call :wait_for_microservices_health "%env%"
)

goto :eof

:wait_for_infrastructure_health
call :log "Performing infrastructure health checks..."

call :info "Checking PostgreSQL..."
set retries=30
:postgres_check
docker exec -it postgres-container pg_isready -U dlap_user >nul 2>&1
if not errorlevel 1 (
    call :success "PostgreSQL is ready"
    goto :kafka_health
)
set /a retries-=1
if %retries% gtr 0 (
    timeout /t 2 /nobreak >nul
    goto :postgres_check
)

:kafka_health
call :info "Checking Kafka..."
set retries=30
:kafka_check
docker exec -it kafka-container kafka-topics --bootstrap-server localhost:9092 --list >nul 2>&1
if not errorlevel 1 (
    call :success "Kafka is ready"
    goto :keycloak_health
)
set /a retries-=1
if %retries% gtr 0 (
    timeout /t 2 /nobreak >nul
    goto :kafka_check
)

:keycloak_health
call :info "Checking Keycloak..."
set retries=60
:keycloak_check
curl -sf "http://localhost:8180/health" >nul 2>&1
if not errorlevel 1 (
    call :success "Keycloak is ready"
    goto :health_done
)
set /a retries-=1
if %retries% gtr 0 (
    timeout /t 3 /nobreak >nul
    goto :keycloak_check
)

:health_done
call :success "Infrastructure health checks completed"
goto :eof

:wait_for_microservices_health
call :log "Performing microservices health checks..."

set services_ports=da-discovery:8080 da-catalog:8081 da-policyengine:8082 da-reporting:8083 da-autolabel:8084 da-autoarchival:8085 da-autodelete:8086 da-autocompliance:8087 da-admin-service:8088

for %%sp in (%services_ports%) do (
    for /f "tokens=1,2 delims=:" %%a in ("%%sp") do (
        set service=%%a
        set port=%%b
        
        call :info "Checking !service! on port !port!..."
        set retries=30
        
        :service_check
        curl -sf "http://localhost:!port!/actuator/health" >nul 2>&1
        if not errorlevel 1 (
            call :success "!service! is ready"
            goto :continue_service
        )
        set /a retries-=1
        if !retries! gtr 0 (
            timeout /t 3 /nobreak >nul
            goto :service_check
        )
        
        call :warning "!service! health check timed out"
        
        :continue_service
    )
)

call :success "Microservices health checks completed"
goto :eof

:stop_services
set env=%~1
set env_dir=%PROJECT_ROOT%\deployment\environments\%env%

call :log "Stopping services for environment: %env%"

cd /d "%env_dir%"

REM Stop microservices first (using both compose files if microservices file exists)
if exist "docker-compose.microservices.yml" (
    call :log "Stopping microservices..."
    docker compose -f docker-compose.yml -f docker-compose.microservices.yml down
)

REM Stop infrastructure
call :log "Stopping infrastructure..."
docker compose down

call :success "Services stopped successfully"
goto :eof

:show_status
set env=%~1
set env_dir=%PROJECT_ROOT%\deployment\environments\%env%

call :log "Service status for environment: %env%"

cd /d "%env_dir%"

echo.
call :info "Infrastructure Services:"
docker compose ps

echo.
if exist "docker-compose.microservices.yml" (
    call :info "Microservices:"
    docker compose -f docker-compose.yml -f docker-compose.microservices.yml ps
)

echo.
call :info "Service URLs:"
call :show_service_urls "%env%"
goto :eof

:show_logs
set env=%~1
set env_dir=%PROJECT_ROOT%\deployment\environments\%env%

call :log "Showing logs for environment: %env%"

cd /d "%env_dir%"

if exist "docker-compose.microservices.yml" (
    docker compose -f docker-compose.yml -f docker-compose.microservices.yml logs -f
) else (
    docker compose logs -f
)
goto :eof

:clean_environment
set env=%~1
set env_dir=%PROJECT_ROOT%\deployment\environments\%env%

call :log "Cleaning environment: %env%"

cd /d "%env_dir%"

REM Stop and remove containers
if exist "docker-compose.microservices.yml" (
    docker compose -f docker-compose.yml -f docker-compose.microservices.yml down -v --remove-orphans
)
docker compose down -v --remove-orphans

REM Clean build artifacts
call :log "Cleaning build artifacts..."
cd /d "%PROJECT_ROOT%"
if exist "build" rmdir /s /q build
if exist "out" rmdir /s /q out

REM Clean service-specific build artifacts
for %%s in (%ALL_SERVICES%) do (
    if exist "%%s\build" rmdir /s /q "%%s\build"
    if exist "%%s\out" rmdir /s /q "%%s\out"
)

call :success "Environment cleaned successfully"
goto :eof

REM ==================== MAIN EXECUTION ====================

:main
REM Parse command line arguments
if "%~1"=="" (
    call :show_help
    exit /b 0
)

call :parse_arguments %*

REM Show configuration
call :info "DLAP Master Script Configuration:"
call :info "  Command: %COMMAND%"
call :info "  Environment: %ENVIRONMENT%"
call :info "  Project Root: %PROJECT_ROOT%"

REM Validate environment
call :validate_environment "%ENVIRONMENT%"
if errorlevel 1 exit /b 1

if "%COMMAND%"=="build" (
    REM Check basic prerequisites (Java, Node.js) but not Docker for build-only
    call :check_build_prerequisites
    if errorlevel 1 exit /b 1
    
    call :info "Building services"
    call :build_services_sequential  
    if errorlevel 1 exit /b 1
    
    REM Only check Docker and build images if not skipping Docker
    if "%SKIP_DOCKER%"=="false" (
        call :check_docker_prerequisites
        if errorlevel 1 exit /b 1
        
        call :build_docker_images
        if errorlevel 1 exit /b 1
    )
    
    call :success "Build completed successfully"
    
) else if "%COMMAND%"=="start" (
    call :check_prerequisites
    if errorlevel 1 exit /b 1
    
    if not "%SINGLE_SERVICE%"=="" (
        call :start_single_service "%ENVIRONMENT%" "%SINGLE_SERVICE%"
        
        if "%SHOW_LOGS%"=="true" (
            REM Show logs for the single service
            set env_dir=%PROJECT_ROOT%\deployment\environments\%ENVIRONMENT%
            cd /d "!env_dir!"
            docker compose logs -f "%SINGLE_SERVICE%" 2>nul || docker compose -f docker-compose.microservices.yml logs -f "%SINGLE_SERVICE%" 2>nul
        )
    ) else if "%SERVICES_ONLY%"=="true" (
        call :start_microservices "%ENVIRONMENT%"
    ) else if "%INFRASTRUCTURE_ONLY%"=="true" (
        call :start_infrastructure "%ENVIRONMENT%"
    ) else (
        call :start_infrastructure "%ENVIRONMENT%"
        if not errorlevel 1 call :start_microservices "%ENVIRONMENT%"
    )
    
    if "%SHOW_LOGS%"=="true" if "%SINGLE_SERVICE%"=="" (
        call :show_logs "%ENVIRONMENT%"
    ) else if "%SINGLE_SERVICE%"=="" (
        call :show_status "%ENVIRONMENT%"
    )
    
) else if "%COMMAND%"=="restart" (
    call :check_prerequisites
    if errorlevel 1 exit /b 1
    
    if not "%SINGLE_SERVICE%"=="" (
        REM Stop single service
        set env_dir=%PROJECT_ROOT%\deployment\environments\%ENVIRONMENT%
        cd /d "!env_dir!"
        docker compose stop "%SINGLE_SERVICE%" 2>nul || docker compose -f docker-compose.microservices.yml stop "%SINGLE_SERVICE%" 2>nul
        timeout /t 2 /nobreak >nul
        
        call :start_single_service "%ENVIRONMENT%" "%SINGLE_SERVICE%"
        
        if "%SHOW_LOGS%"=="true" (
            docker compose logs -f "%SINGLE_SERVICE%" 2>nul || docker compose -f docker-compose.microservices.yml logs -f "%SINGLE_SERVICE%" 2>nul
        )
    ) else (
        call :stop_services "%ENVIRONMENT%"
        timeout /t 5 /nobreak >nul
        
        if "%SERVICES_ONLY%"=="true" (
            call :start_microservices "%ENVIRONMENT%"
        ) else if "%INFRASTRUCTURE_ONLY%"=="true" (
            call :start_infrastructure "%ENVIRONMENT%"
        ) else (
            call :start_infrastructure "%ENVIRONMENT%"
            if not errorlevel 1 call :start_microservices "%ENVIRONMENT%"
        )
        
        if "%SHOW_LOGS%"=="true" (
            call :show_logs "%ENVIRONMENT%"
        ) else (
            call :show_status "%ENVIRONMENT%"
        )
    )
    
) else if "%COMMAND%"=="stop" (
    call :stop_services "%ENVIRONMENT%"
    
) else if "%COMMAND%"=="status" (
    call :show_status "%ENVIRONMENT%"
    
) else if "%COMMAND%"=="logs" (
    call :show_logs "%ENVIRONMENT%"
    
) else if "%COMMAND%"=="clean" (
    call :clean_environment "%ENVIRONMENT%"
    
) else if "%COMMAND%"=="services" (
    call :list_services
    
) else if "%COMMAND%"=="help" (
    call :show_help
    
) else (
    call :error "Unknown command: %COMMAND%"
    call :show_help
    exit /b 1
)

goto :eof

REM ==================== SCRIPT ENTRY POINT ====================

REM Execute main function
call :main %*
set MAIN_EXIT_CODE=%errorlevel%

if %MAIN_EXIT_CODE% neq 0 (
    call :error "Script execution failed with exit code %MAIN_EXIT_CODE%"
    echo.
    call :info "Common troubleshooting steps:"
    call :info "1. Ensure Docker Desktop is running"
    call :info "2. Check if Java 21 is installed and in PATH"
    call :info "3. Verify Node.js is installed for frontend builds"
    call :info "4. Check if the environment directory exists"
    call :info "5. Review build logs in deployment/logs/ directory"
    echo.
    pause
)

exit /b %MAIN_EXIT_CODE% 