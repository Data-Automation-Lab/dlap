# DALab Deployment Structure Documentation

## Overview

This document explains the complete deployment infrastructure organization for the DALab platform, including the recent consolidation of scattered deployment assets into a unified deployment module.

## Directory Structure

### Root Level Structure
```
microservices-dalab/
├── deployment/              # 🎯 UNIFIED DEPLOYMENT MODULE
├── gradle/                  # Gradle wrapper (required by root gradlew)
├── gradlew, gradlew.bat    # Root build system scripts
├── build.gradle.kts        # Root project configuration
├── settings.gradle.kts     # Multi-project settings
├── da-*/                   # Individual microservice projects
├── new-ui/                 # Frontend application
└── docs/                   # Project documentation
```

### Deployment Module Structure
```
deployment/
├── environments/           # Environment-specific configurations
│   ├── dev/               # Development environment
│   │   ├── docker-compose.yml
│   │   ├── .env.example
│   │   └── README.md
│   ├── test/              # Testing environment
│   └── prod/              # Production environment
│       ├── docker-compose.yml
│       └── nginx/
├── platform/              # Infrastructure components
│   ├── nginx/             # Load balancer and API gateway
│   │   ├── nginx.conf
│   │   └── ssl/
│   ├── keycloak/          # Identity and access management
│   │   ├── realm-config/
│   │   └── themes/
│   ├── database/          # Database configurations
│   │   ├── docker-compose-database.yml
│   │   ├── init-scripts/
│   │   │   ├── 01-create-schemas-and-users.sql
│   │   │   └── 02-create-common-schema.sql
│   │   ├── backups/
│   │   └── pgadmin/
│   ├── monitoring/        # Prometheus and Grafana
│   └── kafka/             # Message broker configuration
├── scripts/               # Build and deployment scripts
│   ├── build-all.sh       # Linux/macOS parallel build script
│   ├── build-all.bat      # Windows parallel build script
│   ├── start-dev.sh       # Development environment startup
│   └── test-parallel-build.bat  # Build performance testing
├── gradle/                # Deployment-specific Gradle wrapper
│   └── wrapper/
├── docs/                  # Deployment documentation
│   ├── port-configuration.md
│   ├── aggregation-workflow-architecture.md
│   └── deployment-structure.md (this file)
└── README.md              # Main deployment documentation
```

## Consolidation Summary

### Assets Moved to Deployment Module

#### 1. Scripts Directory (`scripts/` → `deployment/scripts/`)
**Original Location**: `scripts/test-parallel-build.bat`
**New Location**: `deployment/scripts/test-parallel-build.bat`
**Changes Made**: 
- Updated script to navigate to root directory before running gradlew
- Added path navigation: `cd /d "%~dp0..\.."` 

#### 2. Database Docker Configuration (`src/main/docker/` → `deployment/platform/database/`)
**Original Location**: 
- `src/main/docker/docker-compose-database.yml`
- `src/main/docker/database/init-scripts/01-create-schemas-and-users.sql`
- `src/main/docker/database/init-scripts/02-create-common-schema.sql`

**New Location**:
- `deployment/platform/database/docker-compose-database.yml`
- `deployment/platform/database/init-scripts/01-create-schemas-and-users.sql`
- `deployment/platform/database/init-scripts/02-create-common-schema.sql`

**Changes Made**:
- Updated volume paths in docker-compose to use relative paths
- Added comments explaining standalone database usage
- Fixed typo in PgAdmin environment variable

#### 3. Gradle Wrapper (`gradle/` → `deployment/gradle/`)
**Original Location**: `gradle/wrapper/`
**New Location**: `deployment/gradle/wrapper/` (copy)
**Note**: Original kept in root because `gradlew` scripts depend on it

### Directory Cleanup

#### Removed Directories
- ✅ `scripts/` - Moved to `deployment/scripts/`
- ✅ `src/` - Moved to `deployment/platform/database/`

#### Preserved Directories
- ✅ `gradle/` - Required by root `gradlew` and `gradlew.bat`

## Gradle Directory Strategy

### Why Two Gradle Directories?

1. **Root `gradle/`**: Required by the root build system
   - Referenced by `gradlew` and `gradlew.bat`
   - Used for multi-project builds
   - Cannot be moved without breaking build system

2. **Deployment `gradle/`**: For deployment-specific scripts
   - Used by deployment scripts that need Gradle wrapper
   - Provides deployment module independence
   - Allows deployment module to be self-contained

### Build System Integration

The deployment scripts are designed to work with the root build system:

```bash
# deployment/scripts/build-all.sh
cd "${PROJECT_ROOT}"  # Navigate to root
./gradlew clean build  # Use root gradlew
```

```batch
REM deployment/scripts/test-parallel-build.bat
cd /d "%~dp0..\.."     REM Navigate to root
gradlew clean build    REM Use root gradlew
```

## Environment Configuration

### Development Environment
- **Location**: `deployment/environments/dev/`
- **Purpose**: Local development with hot reload and debugging
- **Key Features**:
  - All 9 microservices configured
  - PostgreSQL with test data
  - Keycloak with pre-configured users
  - Kafka with 14 topics
  - Redis for caching

### Production
- **Location**: [`deployment/PRODUCTION.md`](../PRODUCTION.md) (documented, not an in-tree compose stack - ADR-0037)
- **Purpose**: High-availability production deployment via your own orchestration (K8s/Helm, etc.)
- **Covers**: external/HA Postgres, Keycloak-on-Postgres + hardened realm, real fail-closed
  secrets, JWT issuer/audience, TLS termination, `ddl-auto=validate`, resource limits

## Platform Components

### Nginx Load Balancer
- **Location**: `deployment/platform/nginx/`
- **Purpose**: API gateway and load balancer
- **Features**:
  - Routes all 9 microservices
  - SSL termination
  - Rate limiting
  - Security headers

### Database Platform
- **Location**: `deployment/platform/database/`
- **Purpose**: PostgreSQL database with initialization
- **Features**:
  - 10 schemas for all services
  - Service-specific users with restricted permissions
  - Comprehensive test data
  - Performance optimization

### Keycloak Platform
- **Location**: `deployment/platform/keycloak/`
- **Purpose**: Identity and access management
- **Features**:
  - DALab realm configuration
  - Social login (Google, Facebook)
  - Role-based access control
  - User federation

## Usage Instructions

### Quick Start Development
```bash
cd deployment/environments/dev
./start-dev.sh
```

### Build All Services
```bash
cd deployment/scripts
./build-all.sh
```

### Production Deployment
Production is a documented hardening path - see [`deployment/PRODUCTION.md`](../PRODUCTION.md)
(ADR-0037).

## Benefits of Consolidation

### 1. Centralized Management
- All deployment assets in one location
- Consistent configuration across environments
- Easier maintenance and updates

### 2. Environment Consistency
- Standardized configurations
- Reduced configuration drift
- Simplified troubleshooting

### 3. Developer Productivity
- One-command environment setup
- Clear documentation structure
- Reduced context switching

### 4. Production Readiness
- Enterprise-grade configurations
- Security best practices
- Monitoring and observability

## Migration Notes

### For Developers
- Use `deployment/scripts/` instead of root `scripts/`
- Database configurations moved to `deployment/platform/database/`
- Environment startup scripts in `deployment/environments/*/`

### For DevOps
- All infrastructure configurations centralized in `deployment/platform/`
- Environment-specific overrides in `deployment/environments/`
- Build scripts support parallel execution and caching

### For Documentation
- Deployment docs in `deployment/docs/`
- Environment-specific READMEs in respective directories
- Port configuration documented in `deployment/docs/port-configuration.md`

## Future Enhancements

### Planned Additions
- CI/CD pipeline configurations
- Kubernetes deployment manifests
- Terraform infrastructure as code
- Automated backup and restore scripts
- Performance monitoring dashboards

### Extensibility
- Environment templates for new deployments
- Plugin architecture for additional services
- Configuration validation scripts
- Automated testing frameworks

---

**Last Updated**: 2025-01-03  
**Version**: 1.0  
**Maintainer**: DALab Development Team 