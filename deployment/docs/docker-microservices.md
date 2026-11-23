# DALab Microservices Docker Configuration

This document describes the standardized Docker configuration for all DALab microservices and their integration with the deployment module's modular architecture.

## Overview

All 10 DALab microservices now have consistent, production-ready Dockerfiles located in their respective `src/main/docker/` directories. This standardization enables seamless integration with the deployment module's environment-specific orchestration capabilities.

## Modular Deployment Architecture

The deployment system follows a modular architecture with:

### 🏗️ Common Infrastructure
- **`deployment/scripts/start-microservices.sh`** - Core microservice management script
- **`deployment/docs/docker-microservices.md`** - This documentation

### 🌍 Environment-Specific Scripts
- **`deployment/environments/dev/start-dev.sh`** - Development environment with dev-specific configurations
- **`deployment/environments/test/start-test.sh`** - Test environment with test isolation and automation
- Production: see [`deployment/PRODUCTION.md`](../PRODUCTION.md) - documented, not an in-tree stack (ADR-0037)

### 📁 Entry Points
- **`deployment/scripts/start-dev.sh`** - Main development entry point (delegates to env-specific script)

## Microservices Docker Structure

### Directory Layout

Each microservice follows the same Docker structure:

```
<microservice-name>/
├── src/
│   └── main/
│       └── docker/
│           └── Dockerfile
├── build.gradle.kts
└── ...
```

### Dockerized Services

All microservices have consistent Dockerfiles:

1. **da-catalog** - Central Catalog Service
2. **da-policyengine** - Policy Engine Service  
3. **da-reporting** - Reporting & Notification Service
4. **da-autolabel** - SmartLabel (AutoLabel) Service
5. **da-autoarchival** - SmartArchive (AutoArchival) Service
6. **da-autodelete** - SmartDelete (AutoDelete) Service
7. **da-autocompliance** - SmartCompliance (AutoCompliance) Service
8. **da-admin-service** - Administration Service
9. **da-discovery** - Discovery Service (updated to follow standard)
10. **new-ui** - Frontend UI Service (multi-stage build with React + Java backend)

## Docker Image Specifications

### Base Configuration
- **Base Image**: `openjdk:21-jdk-slim`
- **Working Directory**: `/app`
- **Port**: 8080 (configurable via environment)
- **Health Check**: Spring Boot Actuator `/actuator/health`

### Security Features
- Non-root user execution (`appuser:appgroup`)
- Proper file ownership and permissions
- Health checks for container orchestration

### Performance Optimizations
- **JVM Options**: G1GC, container-aware settings
- **Memory Management**: Configurable heap sizes via `JAVA_OPTS`
- **Resource Limits**: Production-ready defaults with environment overrides

### Example Dockerfile Structure
```dockerfile
FROM openjdk:21-jdk-slim

WORKDIR /app

# Create app user for security
RUN addgroup --system --gid 1001 appgroup && \
    adduser --system --uid 1001 --gid 1001 appuser

# Copy JAR file
COPY build/libs/<service-name>.jar app.jar

# Set ownership
RUN chown appuser:appgroup app.jar

# Switch to non-root user
USER appuser

# Expose port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

# Set JVM options
ENV JAVA_OPTS="-Xmx512m -Xms256m -XX:+UseG1GC -XX:+UseContainerSupport"

# Run the application
ENTRYPOINT exec java $JAVA_OPTS -jar app.jar
```

## Deployment Script Usage

### Development Environment
```bash
# Start development infrastructure + microservices
./deployment/scripts/start-dev.sh

# Start only infrastructure services
./deployment/environments/dev/start-dev.sh start-infra-only

# Stop all services including infrastructure
./deployment/environments/dev/start-dev.sh stop-all
```

### Test Environment
```bash
# Start test environment with separate ports
./deployment/environments/test/start-test.sh

# Run full integration test suite
./deployment/environments/test/start-test.sh test-full

# Run tests only (services must be running)
./deployment/environments/test/start-test.sh test-only

# Clean up test environment
./deployment/environments/test/start-test.sh cleanup
```

### Production Environment

Production is a documented hardening path, not a scripted in-tree stack (ADR-0037).
Follow [`deployment/PRODUCTION.md`](../PRODUCTION.md).

### Common Microservice Operations
```bash
# Start all microservices
./deployment/scripts/start-microservices.sh start

# Stop all microservices
./deployment/scripts/start-microservices.sh stop

# Restart all microservices
./deployment/scripts/start-microservices.sh restart

# Show microservice status
./deployment/scripts/start-microservices.sh status

# Build all microservices
./deployment/scripts/start-microservices.sh build
```

## Environment Configuration

### Development (dev)
- **Ports**: 8080-8089 for microservices
- **Infrastructure**: Local Docker Compose services
- **Features**: Debug mode, development tools, parallel startup
- **Database**: `postgresql://localhost:5432`
- **Configuration File**: `deployment/environments/dev/.env.dev` (optional)

### Test (test)
- **Ports**: 8180-8188 for microservices (offset by 100)
- **Infrastructure**: Separate test instances with different ports
- **Features**: Test data, mock external services, automated testing
- **Database**: `postgresql://localhost:5433`
- **Configuration File**: `deployment/environments/test/.env.test` (optional)

### Production (prod)
- **Ports**: Standard 8080-8089 with load balancers
- **Infrastructure**: Managed external services (RDS, MSK, ElastiCache)
- **Features**: Security hardening, monitoring, sequential startup
- **Database**: External managed PostgreSQL
- **Configuration**: real secrets via your orchestrator's secret store or a `.env` from the
  root [`.env.example`](../../.env.example); see [`PRODUCTION.md`](../PRODUCTION.md)

## Service Dependencies

The deployment scripts handle service dependencies automatically:

```
da-catalog (base service)
├── da-policyengine (depends on da-catalog)
│   └── da-reporting (depends on da-catalog + da-policyengine)
└── da-admin-service (depends on da-discovery + da-catalog)
```

## Port Configuration Integration

The Docker microservices integrate with the existing port configuration:

| Service | Dev Port | Test Port | Prod Port | Load Balancer |
|---------|----------|-----------|-----------|---------------|
| da-discovery | 8080 | 8180 | 8080 | 80/443 |
| da-catalog | 8081 | 8181 | 8081 | 80/443 |
| da-policyengine | 8082 | 8182 | 8082 | 80/443 |
| da-reporting | 8083 | 8183 | 8083 | 80/443 |
| da-autolabel | 8084 | 8184 | 8084 | 80/443 |
| da-autoarchival | 8085 | 8185 | 8085 | 80/443 |
| da-autodelete | 8086 | 8186 | 8086 | 80/443 |
| da-autocompliance | 8087 | 8187 | 8087 | 80/443 |
| da-admin-service | 8088 | 8188 | 8088 | 80/443 |

## Build and Deployment Process

### Local Development
1. **Gradle Build**: `./gradlew bootJar` for each service
2. **Docker Build**: `docker build -f src/main/docker/Dockerfile -t dalab/<service>:latest .`
3. **Service Start**: Docker run with environment-specific configurations

### CI/CD Pipeline Integration
1. **Automated Builds**: Jenkins/GitHub Actions integration
2. **Image Registry**: Push to corporate Docker registry
3. **Deployment**: Environment-specific deployment with health checks
4. **Monitoring**: Prometheus metrics and Grafana dashboards

## Monitoring and Observability

### Health Checks
- **Endpoint**: `/actuator/health` on each service
- **Timeout**: 30 seconds with 3 retries
- **Dependencies**: Database, Kafka, Redis connectivity

### Metrics Collection
- **Prometheus**: JVM metrics, application metrics, custom business metrics
- **Grafana**: Pre-built dashboards for each microservice
- **Jaeger**: Distributed tracing across service calls

### Logging
- **Format**: Structured JSON logging with correlation IDs
- **Aggregation**: ELK stack or cloud logging services
- **Retention**: Environment-specific retention policies

## Troubleshooting

### Common Issues
1. **Port Conflicts**: Use environment-specific ports or check for running services
2. **Build Failures**: Check Java 21 compatibility and Gradle wrapper
3. **Health Check Failures**: Verify database and Kafka connectivity
4. **Memory Issues**: Adjust JVM heap settings via `JAVA_OPTS`

### Debug Commands
```bash
# Check service status
./deployment/scripts/start-microservices.sh status

# View service logs
docker logs dalab-<service-name>

# Test service health
curl http://localhost:8080/actuator/health

# Check port availability
lsof -i :8080
```

### Performance Optimization
- **Parallel Builds**: Enable parallel Gradle builds
- **Resource Limits**: Configure appropriate CPU and memory limits
- **Connection Pooling**: Optimize database connection pool sizes
- **Caching**: Enable Redis caching for frequent operations

## Security Considerations

### Container Security
- **Non-root Execution**: All containers run as `appuser`
- **Minimal Attack Surface**: Slim base images with security updates
- **Secrets Management**: Environment variables for sensitive data
- **Network Isolation**: Service-specific networks in production

### Production Hardening
- **SSL/TLS**: HTTPS only in production environments
- **Authentication**: JWT tokens with Keycloak integration
- **Authorization**: Role-based access control (RBAC)
- **Audit Logging**: Comprehensive audit trails for security events

## Related Documentation

- **[Infrastructure Setup Guide](infrastructure-setup.md)** - Complete infrastructure deployment
- **[Port Configuration Guide](port-configuration.md)** - Detailed port allocation
- **[Development Environment Setup](../environments/dev/README.md)** - Development guide
- **[Production Deployment Guide](../PRODUCTION.md)** - Production setup & hardening

---

**Last Updated**: 2025-01-03  
**Version**: 2.0 - Modular deployment architecture with environment-specific configurations 