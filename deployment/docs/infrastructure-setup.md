# DALab Infrastructure Setup Guide

## Overview

The complete DALab development infrastructure has been successfully set up with Docker Compose. This provides a production-like environment for developing and testing all DALab microservices.

## What's Included

### 🗄️ **Database Infrastructure**
- **PostgreSQL 15** with optimized configuration
- **Separate databases** for each microservice
- **Common schema** (dalab_common) with shared entities
- **Comprehensive test data** with realistic examples
- **Automatic schema initialization** and user management

### 🔐 **Security & Identity Management**
- **Keycloak 23** with pre-configured realm
- **JWT-based authentication** ready for all services
- **RBAC support** with predefined roles
- **Development users** with different permission levels
- **Social login support** (Google, Facebook OAuth2)

### 📨 **Message Broker & Streaming**
- **Apache Kafka** with Zookeeper
- **Pre-created topics** for all DALab event types
- **Kafka UI** for easy management and monitoring
- **High availability configuration** with proper partitioning

### 💾 **Caching & Session Management**
- **Redis** for distributed caching
- **Session storage** for web applications
- **Performance optimization** for microservices

### 🌐 **Load Balancing & API Gateway**
- **Nginx** with SSL termination and load balancing
- **API gateway** routing for all 9 microservices
- **Rate limiting** and security headers
- **Static asset caching** and compression

### 📊 **Monitoring & Observability**
- **Prometheus** for metrics collection
- **Grafana** for dashboards and visualization
- **Health checks** for all services
- **Logs aggregation** capabilities

## Quick Start

### 1. Start Development Environment
```bash
# Navigate to deployment directory
cd deployment

# Start complete development environment
./scripts/start-dev.sh

# Or start infrastructure only
./scripts/start-dev.sh --infrastructure-only
```

### 2. Production

Production is **not** an in-tree compose stack (ADR-0037). See
[`deployment/PRODUCTION.md`](../PRODUCTION.md) for the hardened deployment path
(external Postgres, Keycloak-on-Postgres + hardened realm, real secrets, TLS).

### 3. Access Services

#### Development Environment
- **Database**: `localhost:5432` (dalab/dalab)
- **Keycloak**: `http://localhost:8180` (admin/admin)
- **Kafka UI**: `http://localhost:8090`
- **Redis**: `localhost:6379`
- **Nginx (API Gateway)**: `http://localhost:80`

#### Production Environment
- **Nginx (Load Balancer)**: `https://localhost:443`
- **Keycloak**: `https://localhost:8443`
- **Grafana**: `https://localhost:3000`
- **Prometheus**: `https://localhost:9090`

### 4. Verify Setup
```bash
# Check all services are running
docker-compose ps

# Check database
docker-compose exec postgresql psql -U dalab -d dalab_common -c "SELECT COUNT(*) FROM dalab_users;"

# Check Kafka topics
docker-compose exec kafka kafka-topics --bootstrap-server localhost:9092 --list

# Check service health
curl http://localhost/health
```

## Database Schema

### Common Entities (dalab_common database)
- **dalab_users**: 5 test users with different roles
- **dalab_labels**: 14 predefined labels (compliance, classification, business)
- **dalab_assets**: 5 sample assets (GCP, AWS, Azure)
- **dalab_asset_label_assignments**: 13 label assignments with confidence scores

### Service Databases
Each microservice has its own database:
- `da_discovery` → `da_discovery_user`
- `da_catalog` → `da_catalog_user`
- `da_policyengine` → `da_policyengine_user`
- `da_reporting` → `da_reporting_user`
- `da_autolabel` → `da_autolabel_user`
- `da_autoarchival` → `da_autoarchival_user`
- `da_autodelete` → `da_autodelete_user`
- `da_autocompliance` → `da_autocompliance_user`
- `da_admin` → `da_admin_user`

## Kafka Topics

All necessary topics are automatically created:

### Asset Events
- `dlap.asset.change.event`
- `dlap.assets.discovered`
- `dlap.assets.labeled`

### Policy Events
- `dlap.policies.evaluations`
- `dlap.policies.actions`

### Service Events
- `dlap.discovery.scans`
- `dlap.labeling.jobs`
- `dlap.archival.tasks`
- `dlap.deletion.tasks`
- `dlap.compliance.evaluations`
- `dlap.reports.requests`
- `dlap.notifications.send`

## Integration with Microservices

### Database Connection
Services can connect using the template configuration:
```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/da_[SERVICE_NAME]
    username: da_[SERVICE_NAME]_user
    password: da_[SERVICE_NAME]_pass
```

### Common Entities Access
All services have read/write access to common entities:
```yaml
dalab:
  common-db:
    url: jdbc:postgresql://localhost:5432/dalab_common
    username: da_[SERVICE_NAME]_user  # Already has permissions
    password: da_[SERVICE_NAME]_pass
```

### Kafka Integration
```yaml
spring:
  kafka:
    bootstrap-servers: localhost:9092
    consumer:
      group-id: ${spring.application.name}
```

### Keycloak Integration
```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: http://localhost:8180/realms/dalab
```

## Test Data Details

### Test Users
| Username | Role | Password | Email |
|----------|------|----------|-------|
| admin | ADMIN, USER | (Keycloak) | admin@dalab.com |
| data_analyst | DATA_ANALYST, USER | (Keycloak) | analyst@dalab.com |
| data_engineer | DATA_ENGINEER, USER | (Keycloak) | engineer@dalab.com |
| data_scientist | DATA_SCIENTIST, USER | (Keycloak) | scientist@dalab.com |
| policy_manager | POLICY_MANAGER, USER | (Keycloak) | manager@dalab.com |

### Sample Assets
1. **GCS Bucket**: Customer data with PII labels
2. **S3 Bucket**: Sales analytics with business labels
3. **BigQuery Table**: Customer profiles with analytics labels
4. **Azure Blob**: ML models with technical labels
5. **S3 Archive**: Historical data with archived status

### Label Categories
- **Compliance**: PII, PHI, FINANCIAL
- **Classification**: PUBLIC, INTERNAL, CONFIDENTIAL
- **Business**: CUSTOMER_DATA, ANALYTICS, SALES_DATA, MARKETING_DATA
- **Technical**: ML_TRAINING, LOG_DATA
- **Lifecycle**: ARCHIVED

## Environment Structure

```
deployment/
├── environments/                      # Environment-specific configurations
│   ├── dev/                          # Development environment
│   │   ├── docker-compose.yml        # Development services
│   │   ├── .env.example              # Environment variables template
│   │   └── README.md                 # Development setup guide
│   ├── test/                         # Testing environment
│   └── prod/                         # Production environment
│       ├── docker-compose.yml        # Production services with HA
│       ├── .env.example              # Production environment variables
│       └── README.md                 # Production deployment guide
├── platform/                         # Infrastructure components
│   ├── database/                     # Database configurations
│   │   ├── init-scripts/             # Database initialization
│   │   │   ├── 00-create-multiple-databases.sh
│   │   │   ├── 01-init-extensions.sql
│   │   │   ├── 01-create-schemas-and-users.sql
│   │   │   ├── 02-create-common-schema.sql
│   │   │   └── 03-insert-test-data.sql
│   │   └── docker-compose-database.yml
│   ├── keycloak/                     # Identity management
│   │   ├── realm-config/             # Realm configurations
│   │   │   └── dalab-realm.json      # DALab realm with social login
│   │   ├── keycloak-health-check.sh
│   │   └── env.social-login.example
│   ├── nginx/                        # Load balancer and API gateway
│   │   ├── nginx.conf                # Production nginx configuration
│   │   └── ssl/                      # SSL certificates
│   ├── kafka/                        # Message broker
│   ├── prometheus/                   # Monitoring
│   │   └── prometheus.yml
│   └── microservice-config-template.yml
└── scripts/                          # Build and deployment scripts
    ├── build-all.sh                  # Cross-platform build script
    ├── build-all.bat                 # Windows build script
    ├── start-dev.sh                  # Development environment startup
    └── start-dalab-infrastructure.bat # Infrastructure startup (Windows)
```

## Next Steps

Now that the infrastructure is ready, you can:

1. **Start the infrastructure**: Run `./scripts/start-dev.sh`
2. **Test service integration**: All services are configured for infrastructure connectivity
3. **Deploy to production**: Use production environment configuration
4. **Set up CI/CD**: Create deployment pipelines
5. **Add monitoring**: Enable Prometheus/Grafana profiles

## Troubleshooting

### Common Issues
- **Port conflicts**: Ensure required ports are free (see port configuration guide)
- **Memory issues**: Allocate at least 4GB RAM to Docker
- **Startup order**: Infrastructure services start in proper dependency order

### Useful Commands
```bash
# Check service status
docker-compose ps

# View logs
docker-compose logs [service-name]

# Restart specific service
docker-compose restart [service-name]

# Stop everything
docker-compose down

# Stop and remove data (CAUTION)
docker-compose down -v

# Check service health
curl http://localhost/health
```

## Security Notes

⚠️ **Development vs Production**: 

**Development Environment**:
- Uses default credentials for ease of development
- HTTP connections for local development
- Simplified security for debugging

**Production Environment**:
- Requires proper SSL/TLS certificates
- Strong passwords and secure credentials
- Network security and firewall configuration
- Backup strategies and disaster recovery
- Monitoring and alerting setup

## Success Criteria ✅

- ✅ PostgreSQL running with all databases and users created
- ✅ Keycloak running with imported realm configuration and social login
- ✅ Kafka running with all topics created
- ✅ Redis running and accessible
- ✅ Nginx load balancer configured and operational
- ✅ Test data loaded and verified
- ✅ All services healthy and accessible
- ✅ Configuration templates provided for microservices
- ✅ Documentation complete with troubleshooting guides
- ✅ Social login (Google/Facebook) configured and ready
- ✅ Production-ready SSL/TLS configuration available

The DALab infrastructure is now ready for full-scale microservice development, testing, and production deployment! 