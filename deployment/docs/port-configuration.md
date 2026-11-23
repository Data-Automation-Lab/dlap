# DALab Port Configuration Guide

## Overview

This document provides a comprehensive overview of port allocation across all DALab services in different environments. Understanding this configuration is crucial for proper deployment and integration.

## Port Allocation Strategy

### Container Internal Ports vs External Ports
- **Internal Ports**: Ports used within Docker containers (consistent across environments)
- **External Ports**: Ports exposed to host machine (different per environment)
- **Nginx Proxy**: Routes external traffic to internal services via container names

## Development Environment Ports

### Infrastructure Services
| Service | Container Name | Internal Port | External Port | Access URL |
|---------|---------------|---------------|---------------|------------|
| PostgreSQL | dalab-postgresql | 5432 | 5432 | localhost:5432 |
| Keycloak | dalab-keycloak | 9080 | 9080 | localhost:9080 |
| Kafka | dalab-kafka | 9092 | 9092 | localhost:9092 |
| Kafka UI | dalab-kafka-ui | 8080 | 8080 | localhost:8080 |
| Redis | dalab-redis | 6379 | 6379 | localhost:6379 |
| Zookeeper | dalab-zookeeper | 2181 | 2181 | localhost:2181 |
| Prometheus | dalab-prometheus | 9090 | 9090 | localhost:9090 |
| Grafana | dalab-grafana | 3000 | 3000 | localhost:3000 |

### Microservices (Development - Direct Access)
| Service | Container Name | Internal Port | External Port | Direct Access URL |
|---------|---------------|---------------|---------------|------------------|
| da-discovery | da-discovery | 8080 | 8081 | localhost:8081 |
| da-catalog | da-catalog | 8080 | 8082 | localhost:8082 |
| da-policyengine | da-policyengine | 8080 | 8083 | localhost:8083 |
| da-reporting | da-reporting | 8080 | 8084 | localhost:8084 |
| da-autolabel | da-autolabel | 8080 | 8085 | localhost:8085 |
| da-autoarchival | da-autoarchival | 8080 | 8086 | localhost:8086 |
| da-autodelete | da-autodelete | 8080 | 8087 | localhost:8087 |
| da-autocompliance | da-autocompliance | 8080 | 8088 | localhost:8088 |
| da-admin-service | da-admin-service | 8080 | 8089 | localhost:8089 |

### Frontend
| Service | Container Name | Internal Port | External Port | Access URL |
|---------|---------------|---------------|---------------|------------|
| React UI | dalab-frontend | 80 | 3000 | localhost:3000 |

## Production Environment Ports

### Infrastructure Services
| Service | Container Name | Internal Port | External Port | Access URL |
|---------|---------------|---------------|---------------|------------|
| PostgreSQL Primary | dalab-postgres-primary | 5432 | 5432 | localhost:5432 |
| PostgreSQL Replica | dalab-postgres-replica | 5432 | 5433 | localhost:5433 |
| Redis Master | dalab-redis-master | 6379 | 6379 | localhost:6379 |
| Keycloak | dalab-keycloak | 8080 | 8180 | localhost:8180 |
| Kafka | dalab-kafka | 9092 | 9092 | localhost:9092 |
| Zookeeper | dalab-zookeeper | 2181 | 2181 | localhost:2181 |
| Nginx | dalab-nginx | 80/443 | 80/443 | localhost (HTTPS) |
| Prometheus | dalab-prometheus | 9090 | 9090 | localhost:9090 |
| Grafana | dalab-grafana | 3000 | 3001 | localhost:3001 |

### Microservices (Production - Behind Nginx)
| Service | Container Name | Internal Port | External Port | Nginx Route |
|---------|---------------|---------------|---------------|-------------|
| da-discovery | dalab-da-discovery | 8080 | 8081 | /api/v1/discovery/ |
| da-catalog | dalab-da-catalog | 8080 | 8082 | /api/v1/catalog/ |
| da-policyengine | dalab-da-policyengine | 8080 | 8083 | /api/v1/policyengine/ |
| da-reporting | dalab-da-reporting | 8080 | 8084 | /api/v1/reporting/ |
| da-autolabel | dalab-da-autolabel | 8080 | 8085 | /api/v1/labeling/ |
| da-autoarchival | dalab-da-autoarchival | 8080 | 8086 | /api/v1/archival/ |
| da-autodelete | dalab-da-autodelete | 8080 | 8087 | /api/v1/deletion/ |
| da-autocompliance | dalab-da-autocompliance | 8080 | 8088 | /api/v1/compliance/ |
| da-admin-service | dalab-da-admin-service | 8080 | 8089 | /api/v1/admin/ |

### Frontend (Production)
| Service | Container Name | Internal Port | External Port | Nginx Route |
|---------|---------------|---------------|---------------|-------------|
| React UI | dalab-frontend | 80 | 3000 | / (root) |

## Nginx Configuration Explanation

### Why All Microservices Use Port 8080 Internally

**This is CORRECT and intentional!** Here's why:

1. **Container Internal Ports**: Each microservice runs on port 8080 inside its own container
2. **Docker Networking**: Containers communicate using container names (e.g., `da-discovery:8080`)
3. **Port Mapping**: External ports (8081, 8082, etc.) map to internal port 8080
4. **Nginx Upstream**: Nginx uses container names and internal ports for load balancing

### Nginx Upstream Configuration
```nginx
upstream api_discovery {
    server da-discovery:8080;    # Container name + internal port
    keepalive 32;
}
```

This configuration means:
- External request: `https://localhost/api/v1/discovery/stats`
- Nginx proxies to: `http://da-discovery:8080/api/v1/discovery/stats`
- Container `da-discovery` receives request on its internal port 8080

## Environment Configuration Matrix

### Keycloak Port Configuration

| Environment | Keycloak External Port | UI Configuration | Nginx Route |
|-------------|----------------------|-----------------|-------------|
| Development | 9080 | `localhost:9080` | Direct access |
| Production | 8180 | `localhost:8180` | `/auth/` route |

### API Base URLs by Environment

#### Development (Direct Service Access)
```typescript
// new-ui/frontend/src/utils/api.ts
const API_ENDPOINTS = {
  discovery: 'http://localhost:8081',
  catalog: 'http://localhost:8082',
  policyengine: 'http://localhost:8083',
  reporting: 'http://localhost:8084',
  autolabel: 'http://localhost:8085',
  autoarchival: 'http://localhost:8086',
  autodelete: 'http://localhost:8087',
  autocompliance: 'http://localhost:8088',
  admin: 'http://localhost:8089'
};
```

#### Production (Through Nginx Gateway)
```typescript
// new-ui/frontend/src/utils/api.ts
const API_BASE_URL = 'https://localhost'; // All APIs through nginx
const API_ENDPOINTS = {
  discovery: `${API_BASE_URL}/api/v1/discovery`,
  catalog: `${API_BASE_URL}/api/v1/catalog`,
  policyengine: `${API_BASE_URL}/api/v1/policyengine`,
  reporting: `${API_BASE_URL}/api/v1/reporting`,
  autolabel: `${API_BASE_URL}/api/v1/labeling`,
  autoarchival: `${API_BASE_URL}/api/v1/archival`,
  autodelete: `${API_BASE_URL}/api/v1/deletion`,
  autocompliance: `${API_BASE_URL}/api/v1/compliance`,
  admin: `${API_BASE_URL}/api/v1/admin`
};
```

## Port Conflict Resolution

### Current Issues Identified

1. **Kafka UI Port Conflict**: 
   - Development: Kafka UI uses port 8080
   - This conflicts with microservices internal port
   - **Solution**: Change Kafka UI to port 8090

2. **Keycloak Port Inconsistency**:
   - Development: Keycloak on port 9080
   - Production: Keycloak on port 8180  
   - UI expects: Port 8180
   - **Solution**: Standardize on port 8180 for both environments

## Recommended Port Changes

### Development Environment Updates Needed
```yaml
# Fix Kafka UI port conflict
kafka-ui:
  ports:
    - "8090:8080"  # Change from 8080:8080

# Standardize Keycloak port
keycloak:
  ports:
    - "8180:9080"  # Change from 9080:9080
```

### UI Configuration Updates Needed
```typescript
// Update default Keycloak URL to match standardized port
const keycloakConfig = {
  url: process.env.REACT_APP_KEYCLOAK_URL || 'http://localhost:8180',
  // ... rest of config
};
```

## Deployment Port Summary

### Access Points After Fixes
| Service | Development URL | Production URL |
|---------|-----------------|----------------|
| Frontend | http://localhost:3000 | https://localhost |
| Keycloak | http://localhost:8180 | https://localhost/auth |
| Kafka UI | http://localhost:8090 | N/A (internal only) |
| API Gateway | Direct service ports | https://localhost/api/v1/* |
| Monitoring | http://localhost:9090 (Prometheus) | Internal access only |
| Database | localhost:5432 | Internal access only |

## Security Considerations

### Production Port Security
- Only ports 80 (HTTP redirect) and 443 (HTTPS) exposed externally
- All API access through nginx SSL termination
- Database and internal services not exposed externally
- Monitoring services restricted to internal network

### Development Port Security
- All services exposed for debugging and development
- Use environment variables for sensitive configuration
- Keycloak admin interface secured with admin credentials
- Database access restricted to development use only

## Next Steps

1. **Fix Kafka UI Port**: Update to port 8090 to avoid conflicts
2. **Standardize Keycloak**: Use port 8180 in both environments
3. **Update UI Config**: Point to standardized Keycloak port
4. **Test Integration**: Verify all services communicate correctly
5. **Document Changes**: Update deployment guides with new ports 