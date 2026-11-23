# DALab Development Infrastructure

This directory contains the Docker Compose setup for the complete DALab development environment.

## Overview

The DALab infrastructure includes:

- **PostgreSQL 15**: Main database with separate schemas for each microservice
- **Keycloak 23**: Identity and Access Management with pre-configured realm
- **Apache Kafka**: Message broker for inter-service communication
- **Redis**: Caching and session management
- **Kafka UI**: Web interface for Kafka management
- **Prometheus** (optional): Metrics collection
- **Grafana** (optional): Monitoring dashboards

## Quick Start

### Prerequisites

- Docker Desktop installed and running
- Docker Compose v2.0 or higher
- At least 4GB RAM available for containers
- Ports 5432, 9080, 9092, 6379, 8080 available

### Starting the Infrastructure

#### Option 1: Using the startup script (Windows)
```bash
# Run the startup script
start-dalab-infrastructure.bat
```

#### Option 2: Using Docker Compose directly
```bash
# Start core services
docker-compose up -d postgresql keycloak zookeeper kafka

# Wait for services to be ready (30 seconds)
# Then start remaining services
docker-compose up -d redis kafka-ui kafka-init

# Optional: Start monitoring
docker-compose --profile monitoring up -d
```

## Services and Ports

| Service | Port | Description | Credentials |
|---------|------|-------------|-------------|
| PostgreSQL | 5432 | Database server | dalab / dalab |
| Keycloak | 9080 | Identity management | admin / admin |
| Kafka | 9092 | Message broker | - |
| Kafka UI | 8080 | Kafka web interface | - |
| Redis | 6379 | Cache server | - |
| Prometheus | 9090 | Metrics (optional) | - |
| Grafana | 3000 | Monitoring (optional) | admin / admin |

## Database Setup

### Database Structure

The PostgreSQL instance creates separate databases for each service:

- `dalab_common`: Shared entities (Asset, Label, User, AssetLabelAssignment)
- `da_discovery`: Discovery service data
- `da_catalog`: Catalog service data
- `da_policyengine`: Policy engine data
- `da_reporting`: Reporting service data
- `da_autolabel`: Auto-labeling service data
- `da_autoarchival`: Archival service data
- `da_autodelete`: Deletion service data
- `da_autocompliance`: Compliance service data
- `da_admin`: Administration service data

### Database Users

Each service has its own database user with appropriate permissions:

- `dalab_common_user`: Full access to common schema
- `da_catalog_user`: Access to catalog DB + read access to common
- `da_policyengine_user`: Access to policy DB + read access to common
- ... (similar pattern for other services)

### Test Data

The initialization scripts create comprehensive test data:

- **5 test users** with different roles (admin, analyst, engineer, scientist, manager)
- **14 predefined labels** covering compliance, classification, business, and technical categories
- **5 sample assets** from different cloud providers (GCP, AWS, Azure)
- **13 label assignments** showing various labeling scenarios

## Kafka Topics

The following Kafka topics are automatically created:

### Asset Events
- `dlap.asset.change.event`: Asset metadata changes
- `dlap.assets.discovered`: New asset discoveries
- `dlap.assets.labeled`: Asset labeling events

### Policy Events
- `dlap.policies.evaluations`: Policy evaluation results
- `dlap.policies.actions`: Policy action triggers

### Service-Specific Topics
- `dlap.discovery.scans`: Discovery scan events
- `dlap.discovery.results`: Discovery results
- `dlap.labeling.jobs`: Auto-labeling job events
- `dlap.labeling.feedback`: Labeling feedback
- `dlap.archival.tasks`: Archival task events
- `dlap.deletion.tasks`: Deletion task events
- `dlap.compliance.evaluations`: Compliance evaluations
- `dlap.reports.requests`: Report generation requests
- `dlap.notifications.send`: Notification events

## Connecting Services

### Database Connection

Each microservice should use these connection settings:

```yaml
# For da-catalog service
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/da_catalog
    username: da_catalog_user
    password: da_catalog_pass
  
  # For accessing common entities
  jpa:
    additional-datasources:
      common:
        url: jdbc:postgresql://localhost:5432/dalab_common
        username: da_catalog_user
        password: da_catalog_pass
```

### Kafka Connection

```yaml
spring:
  kafka:
    bootstrap-servers: localhost:9092
    consumer:
      group-id: ${spring.application.name}
    producer:
      retries: 3
```

### Keycloak Integration

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: http://localhost:9080/realms/jhipster
```

## Management Commands

### Viewing Logs
```bash
# View all logs
docker-compose logs

# View specific service logs
docker-compose logs postgresql
docker-compose logs keycloak
docker-compose logs kafka

# Follow logs in real-time
docker-compose logs -f kafka
```

### Health Checks
```bash
# Check all service status
docker-compose ps

# Check specific service health
docker-compose exec postgresql pg_isready -U dalab
docker-compose exec kafka kafka-topics --bootstrap-server localhost:9092 --list
```

### Database Operations
```bash
# Connect to PostgreSQL
docker-compose exec postgresql psql -U dalab -d dalab_common

# Run SQL script
docker-compose exec postgresql psql -U dalab -d dalab_common -f /path/to/script.sql

# Create database backup
docker-compose exec postgresql pg_dump -U dalab dalab_common > backup.sql
```

### Kafka Operations
```bash
# List topics
docker-compose exec kafka kafka-topics --bootstrap-server localhost:9092 --list

# Create topic
docker-compose exec kafka kafka-topics --bootstrap-server localhost:9092 --create --topic my-topic --partitions 3 --replication-factor 1

# Consume messages
docker-compose exec kafka kafka-console-consumer --bootstrap-server localhost:9092 --topic dlap.asset.change.event --from-beginning
```

## Stopping the Infrastructure

```bash
# Stop all services
docker-compose down

# Stop and remove volumes (WARNING: This deletes all data!)
docker-compose down -v

# Stop and remove images
docker-compose down --rmi all
```

## Troubleshooting

### Common Issues

1. **Port conflicts**: Ensure ports 5432, 9080, 9092, 6379, 8080 are not in use
2. **Memory issues**: Ensure Docker has at least 4GB RAM allocated
3. **Keycloak startup slow**: First startup can take 2-3 minutes
4. **Kafka connection refused**: Wait 30 seconds after starting for full initialization

### Useful Commands

```bash
# Check Docker resource usage
docker stats

# Restart specific service
docker-compose restart postgresql

# View container logs
docker-compose logs --tail=100 keycloak

# Execute commands inside containers
docker-compose exec postgresql bash
docker-compose exec kafka bash
```

## File Structure

```
platform/
├── database/                  # Database configuration
│   ├── init-scripts/          # Database initialization scripts
│   │   ├── 00-create-multiple-databases.sh
│   │   ├── 01-init-extensions.sql
│   │   ├── 01-create-schemas-and-users.sql
│   │   ├── 02-create-common-schema.sql
│   │   └── 03-insert-test-data.sql
│   └── docker-compose-database.yml
├── keycloak/                  # Keycloak configuration
│   ├── realm-config/
│   │   └── dalab-realm.json
│   └── keycloak-health-check.sh
├── nginx/                     # Load balancer configuration
│   └── nginx.conf
├── prometheus/                # Monitoring configuration
│   └── prometheus.yml
├── kafka/                     # Kafka configuration
├── monitoring/                # Monitoring stack
├── grafana/                   # Grafana dashboards
├── microservice-config-template.yml  # Service configuration template
└── README.md                  # This file
```

## Security Notes

⚠️ **For Development Only**: This setup uses default passwords and is configured for development convenience. Do not use in production without proper security hardening.

### Default Credentials
- PostgreSQL: `dalab` / `dalab`
- Keycloak Admin: `admin` / `admin`
- Grafana: `admin` / `admin`

### Production Checklist
- [ ] Change all default passwords
- [ ] Enable SSL/TLS for all services
- [ ] Configure proper network security
- [ ] Set up proper backup strategies
- [ ] Configure monitoring and alerting
- [ ] Review and harden Keycloak realm settings 