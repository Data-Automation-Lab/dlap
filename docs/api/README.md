> # ⚠️ SUPERSEDED - do not use as a reference
>
> **Superseded on 2026-09-17.** This document describes the pre-rename **DALab**
> platform and has not tracked the code since January 2025. Verified defects: it
> names a `dalab` Keycloak realm (the realm is `dlap` -
> `deployment/platform/keycloak/keycloak-import/dlap-realm.json:3`), and its
> role model lists `ROLE_DATA_STEWARD` and `ROLE_USER`, **neither of which
> exists** - `DlapConstants.UserRole`
> (`da-protos/src/main/java/com/dlap/common/constants/DlapConstants.java:147-169`)
> declares `ADMIN`, `CDO`, `DIRECTOR`, `POLICY_MANAGER`, `DATA_ENGINEER`,
> `DATA_SCIENTIST`, `DATA_ANALYST`, `AUDITOR`, `VIEWER` and `SERVICE_ACCOUNT`.
> Service counts, ports and endpoint totals below are likewise stale.
>
> **Authoritative sources instead:**
> - [`docs/api-conventions.md`](../api-conventions.md) - platform-wide base
>   paths, ports, auth, error shape, pagination, OpenAPI locations.
> - **Per-service OpenAPI** - every service serves live, generated docs at
>   `/swagger-ui.html` and `/v3/api-docs` (api-conventions.md §OpenAPI). These
>   are generated from the running controllers and cannot drift.
> - [`docs/specs/README.md`](../specs/README.md) - the normative contracts.
>
> Retained unedited for historical reference only.

---

# DALab API Documentation

**Version**: 2.0  
**Last Updated**: January 27, 2025  
**Status**: Production Ready - All 77 Endpoints Documented

## 📋 **Table of Contents**

1. [🎯 Overview](#-overview)
2. [🔐 Authentication & Authorization](#-authentication--authorization)
3. [📊 API Statistics](#-api-statistics)
4. [🔗 Service Documentation](#-service-documentation)
5. [📝 Integration Examples](#-integration-examples)
6. [🚨 Error Handling](#-error-handling)
7. [🔧 Development Tools](#-development-tools)

---

## 🎯 **Overview**

The DALab platform provides a comprehensive REST API across 9 microservices with 77 endpoints for complete data lifecycle management. All APIs follow consistent patterns with JWT authentication, role-based access control, and comprehensive OpenAPI documentation.

### **API Design Principles**
- **RESTful Design**: Standard HTTP methods and status codes
- **Consistent Patterns**: `/api/v1/{service}/` base paths across all services
- **JWT Authentication**: Bearer token authentication with Keycloak integration
- **Role-Based Access**: Spring Security `@PreAuthorize` annotations
- **Pagination**: Spring Data Pageable support with configurable page sizes
- **OpenAPI Documentation**: Swagger UI available for all services

### **Base URLs**
- **Development**: `http://localhost:8081-8089`
- **Test**: `http://localhost:8181-8188`
- **Production**: `https://your-domain.com:8081-8089`

---

## 🔐 **Authentication & Authorization**

### **JWT Token Authentication**
All API endpoints require JWT token authentication via the `Authorization` header:

```bash
Authorization: Bearer <your-jwt-token>
```

### **Token Acquisition**
1. **Keycloak Login**: `POST /realms/dalab/protocol/openid-connect/token`
2. **Social Login**: Google/Facebook OAuth2 integration available
3. **Token Refresh**: `POST /realms/dalab/protocol/openid-connect/token` with refresh_token

### **Role-Based Access Control**
- **ROLE_ADMIN**: Full system access and administration
- **ROLE_CDO**: Chief Data Officer - governance and compliance oversight
- **ROLE_DIRECTOR**: Director - cost analysis and reporting
- **ROLE_DATA_ENGINEER**: Data Engineer - asset management and policy configuration
- **ROLE_DATA_STEWARD**: Data Steward - catalog and labeling management
- **ROLE_USER**: Basic user access to view and interact with data

### **Authorization Examples**
```java
@PreAuthorize("hasAnyRole('ADMIN', 'DATA_STEWARD')")
@PreAuthorize("hasRole('ADMIN')")
@PreAuthorize("isAuthenticated()")
```

---

## 📊 **API Statistics**

### **Service Breakdown**
| Service | Endpoints | Base Path | Port |
|---------|-----------|-----------|------|
| **da-discovery** | 8 | `/api/v1/discovery` | 8081 |
| **da-catalog** | 8 | `/api/v1/catalog` | 8082 |
| **da-policyengine** | 12 | `/api/v1/policyengine` | 8083 |
| **da-reporting** | 6 | `/api/v1/reporting` | 8084 |
| **da-autolabel** | 8 | `/api/v1/autolabel` | 8085 |
| **da-autoarchival** | 7 | `/api/v1/autoarchival` | 8086 |
| **da-autodelete** | 7 | `/api/v1/autodelete` | 8087 |
| **da-autocompliance** | 9 | `/api/v1/autocompliance` | 8088 |
| **da-admin-service** | 12 | `/api/v1/admin` | 8089 |

**Total**: **77 endpoints** across **9 microservices**

### **HTTP Method Distribution**
- **GET**: 45 endpoints (58%) - Data retrieval and queries
- **POST**: 20 endpoints (26%) - Resource creation and actions
- **PUT**: 8 endpoints (10%) - Resource updates
- **DELETE**: 4 endpoints (5%) - Resource deletion

### **Response Time Performance**
- **Target**: <2 seconds for all endpoints
- **Average**: 1.2 seconds across all services
- **99th Percentile**: 1.8 seconds

---

## 🔗 **Service Documentation**

### **Core Services**

#### **1. [Asset Discovery Service](da-discovery-api.md)**
- **Purpose**: Multi-cloud asset discovery and scanning
- **Key Features**: Cloud provider integration, configurable scanning, statistics
- **Endpoints**: 8 total
- **Swagger UI**: `http://localhost:8081/swagger-ui.html`

#### **2. [Data Catalog Service](da-catalog-api.md)**
- **Purpose**: Central data catalog with asset management
- **Key Features**: Asset CRUD, search, labeling, lineage tracking
- **Endpoints**: 8 total
- **Swagger UI**: `http://localhost:8082/swagger-ui.html`

#### **3. [Policy Engine Service](da-policyengine-api.md)**
- **Purpose**: Policy management and rule engine
- **Key Features**: Policy CRUD, evaluation, draft workflow, Event Center
- **Endpoints**: 12 total
- **Swagger UI**: `http://localhost:8083/swagger-ui.html`

#### **4. [Reporting Service](da-reporting-api.md)**
- **Purpose**: Analytics, dashboards, and notifications
- **Key Features**: Cross-service aggregation, role-based dashboards
- **Endpoints**: 6 total
- **Swagger UI**: `http://localhost:8084/swagger-ui.html`

### **Automation Services**

#### **5. [Auto-Labeling Service](da-autolabel-api.md)**
- **Purpose**: AI-powered data labeling
- **Key Features**: LLM integration, batch processing, confidence scoring
- **Endpoints**: 8 total
- **Swagger UI**: `http://localhost:8085/swagger-ui.html`

#### **6. [Auto-Archival Service](da-autoarchival-api.md)**
- **Purpose**: Smart data archival and storage optimization
- **Key Features**: Multi-cloud storage, cost estimation, restoration
- **Endpoints**: 7 total
- **Swagger UI**: `http://localhost:8086/swagger-ui.html`

#### **7. [Auto-Delete Service](da-autodelete-api.md)**
- **Purpose**: Secure data deletion and compliance
- **Key Features**: Compliance validation, secure erasure, audit trails
- **Endpoints**: 7 total
- **Swagger UI**: `http://localhost:8087/swagger-ui.html`

#### **8. [Auto-Compliance Service](da-autocompliance-api.md)**
- **Purpose**: Regulatory compliance monitoring
- **Key Features**: Multi-regulation support, violation detection, risk assessment
- **Endpoints**: 9 total
- **Swagger UI**: `http://localhost:8088/swagger-ui.html`

### **Administration Services**

#### **9. [Admin Service](da-admin-api.md)**
- **Purpose**: System administration and user management
- **Key Features**: Keycloak integration, cloud connections, job aggregation
- **Endpoints**: 12 total
- **Swagger UI**: `http://localhost:8089/swagger-ui.html`

---

## 📝 **Integration Examples**

### **Java/Spring Boot Integration**
```java
@RestController
public class DataIntegrationController {
    
    @Autowired
    private RestTemplate restTemplate;
    
    @GetMapping("/assets")
    public ResponseEntity<Page<AssetOutputDTO>> getAssets(
            @RequestHeader("Authorization") String token) {
        
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", token);
        
        HttpEntity<String> entity = new HttpEntity<>(headers);
        
        return restTemplate.exchange(
            "http://localhost:8082/api/v1/catalog/assets",
            HttpMethod.GET,
            entity,
            new ParameterizedTypeReference<Page<AssetOutputDTO>>() {}
        );
    }
}
```

### **Python Integration**
```python
import requests
import json

class DALabClient:
    def __init__(self, base_url, token):
        self.base_url = base_url
        self.headers = {
            'Authorization': f'Bearer {token}',
            'Content-Type': 'application/json'
        }
    
    def get_assets(self, page=0, size=20):
        url = f"{self.base_url}/api/v1/catalog/assets"
        params = {'page': page, 'size': size}
        
        response = requests.get(url, headers=self.headers, params=params)
        response.raise_for_status()
        
        return response.json()
    
    def create_policy(self, policy_data):
        url = f"{self.base_url}/api/v1/policyengine/policies"
        
        response = requests.post(url, headers=self.headers, json=policy_data)
        response.raise_for_status()
        
        return response.json()

# Usage
client = DALabClient('http://localhost:8082', 'your-jwt-token')
assets = client.get_assets()
```

### **cURL Examples**
```bash
# Get all assets
curl -X GET "http://localhost:8082/api/v1/catalog/assets" \
  -H "Authorization: Bearer $JWT_TOKEN" \
  -H "Content-Type: application/json"

# Create a new policy
curl -X POST "http://localhost:8083/api/v1/policyengine/policies" \
  -H "Authorization: Bearer $JWT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Data Retention Policy",
    "description": "Automatically archive data older than 1 year",
    "rules": ["age > 365 days"]
  }'

# Get governance dashboard
curl -X GET "http://localhost:8084/api/v1/reporting/dashboards/governance" \
  -H "Authorization: Bearer $JWT_TOKEN" \
  -H "Content-Type: application/json"
```

---

## 🚨 **Error Handling**

### **Standard Error Response Format**
```json
{
  "timestamp": "2025-01-27T10:30:00Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Validation failed for field 'assetName'",
  "path": "/api/v1/catalog/assets",
  "details": {
    "field": "assetName",
    "constraint": "must not be blank"
  }
}
```

### **HTTP Status Codes**
- **200 OK**: Successful GET, PUT operations
- **201 Created**: Successful POST operations
- **202 Accepted**: Async operations (job creation)
- **204 No Content**: Successful DELETE operations
- **400 Bad Request**: Validation errors, malformed requests
- **401 Unauthorized**: Missing or invalid authentication
- **403 Forbidden**: Insufficient permissions
- **404 Not Found**: Resource not found
- **409 Conflict**: Resource conflicts (duplicate names, etc.)
- **422 Unprocessable Entity**: Business rule violations
- **500 Internal Server Error**: Unexpected server errors

### **Common Error Scenarios**
1. **Authentication Errors**: Invalid or expired JWT tokens
2. **Authorization Errors**: Insufficient role permissions
3. **Validation Errors**: Invalid request payloads
4. **Resource Not Found**: Requested entities don't exist
5. **Business Rule Violations**: Policy conflicts, constraint violations

---

## 🔧 **Development Tools**

### **Swagger UI Access**
Each service provides interactive API documentation:

| Service | Swagger URL | OpenAPI JSON |
|---------|-------------|--------------|
| da-discovery | http://localhost:8081/swagger-ui.html | http://localhost:8081/v3/api-docs |
| da-catalog | http://localhost:8082/swagger-ui.html | http://localhost:8082/v3/api-docs |
| da-policyengine | http://localhost:8083/swagger-ui.html | http://localhost:8083/v3/api-docs |
| da-reporting | http://localhost:8084/swagger-ui.html | http://localhost:8084/v3/api-docs |
| da-autolabel | http://localhost:8085/swagger-ui.html | http://localhost:8085/v3/api-docs |
| da-autoarchival | http://localhost:8086/swagger-ui.html | http://localhost:8086/v3/api-docs |
| da-autodelete | http://localhost:8087/swagger-ui.html | http://localhost:8087/v3/api-docs |
| da-autocompliance | http://localhost:8088/swagger-ui.html | http://localhost:8088/v3/api-docs |
| da-admin-service | http://localhost:8089/swagger-ui.html | http://localhost:8089/v3/api-docs |

### **Health Check Endpoints**
```bash
# Service health checks
curl http://localhost:8081/actuator/health  # da-discovery
curl http://localhost:8082/actuator/health  # da-catalog
# ... etc for all services

# Infrastructure health checks
curl http://localhost:8180/health/ready     # Keycloak
curl http://localhost:9090/-/healthy        # Prometheus
curl http://localhost:3001/api/health       # Grafana
```

### **Testing Tools**
- **JMeter Scripts**: Load testing scenarios
- **k6 Scripts**: Performance testing
- **TestContainers**: Integration testing

---

## 📚 **Additional Resources**

- **[Error Handling Guide](error-handling-guide.md)**: Comprehensive error scenarios
- **[Performance Guide](performance-guide.md)**: Optimization and best practices

---

**Last Updated**: January 27, 2025  
**Version**: 2.0 - Complete API Documentation  
**Author**: DALab Development Team 