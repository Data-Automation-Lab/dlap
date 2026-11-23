# DALab AutoArchival Service - API Documentation

## Overview

The DALab AutoArchival Service provides comprehensive archival management capabilities through RESTful APIs. This document describes the archival overview endpoints that support the frontend archival agent dashboard.

## Base URL

```
http://localhost:8080/api/v1/archival
```

## Authentication

All endpoints require JWT authentication with Keycloak integration. The following roles are supported:

- `ROLE_ADMIN` - Full access to all endpoints
- `ROLE_DATA_STEWARD` - Access to archival operations and overview data
- `ROLE_USER` - Read-only access to overview data

## Archival Overview Endpoints

### 1. Get Comprehensive Overview Data

Retrieves all data needed for the archival agent dashboard in a single request.

**Endpoint:** `GET /api/v1/archival/overview`

**Authorization:** `ROLE_ADMIN`, `ROLE_DATA_STEWARD`, `ROLE_USER`

**Response:**
```json
{
  "kpis": {
    "activePolicies": 8,
    "assetsIdentified": 1842,
    "assetsArchived": 1256,
    "estimatedMonthlySavings": 12475.0,
    "totalSavingsToDate": 89320.0,
    "storageReclaimed": "847.2 TB",
    "pendingArchival": 156,
    "failedArchival": 23,
    "averageArchivalTime": 45.3,
    "lastPolicyRun": "2 hours ago"
  },
  "costTrends": [
    {
      "date": "2024-11-01",
      "monthlySavings": 8420.0,
      "cumulativeSavings": 52150.0,
      "storageReclaimed": 625.3
    }
  ],
  "archivalStatusData": [
    {
      "name": "Archived",
      "value": 1256,
      "color": "#10b981"
    }
  ],
  "recentActivities": [
    {
      "id": "1",
      "event": "Asset Archived",
      "assetName": "legacy_reports_2023.csv",
      "policy": "Quarterly Report Archival",
      "storageSize": "2.3 GB",
      "savingsAmount": 156.0,
      "status": "Success",
      "timestamp": "15 minutes ago",
      "details": "Archived to cold storage tier - no access in 180 days"
    }
  ],
  "storageConsumers": [
    {
      "name": "Old Analytics Datasets",
      "currentSize": "45.7 TB",
      "lastAccessed": "6 months ago",
      "cost": 2847.0,
      "recommendation": "Archive Now",
      "priority": "High"
    }
  ]
}
```

### 2. Get Key Performance Indicators

Retrieves KPI metrics for the archival system.

**Endpoint:** `GET /api/v1/archival/overview/kpis`

**Authorization:** `ROLE_ADMIN`, `ROLE_DATA_STEWARD`, `ROLE_USER`

**Response:**
```json
{
  "activePolicies": 8,
  "assetsIdentified": 1842,
  "assetsArchived": 1256,
  "estimatedMonthlySavings": 12475.0,
  "totalSavingsToDate": 89320.0,
  "storageReclaimed": "847.2 TB",
  "pendingArchival": 156,
  "failedArchival": 23,
  "averageArchivalTime": 45.3,
  "lastPolicyRun": "2 hours ago"
}
```

### 3. Get Cost Savings Trends

Retrieves historical cost savings and storage reclamation data.

**Endpoint:** `GET /api/v1/archival/overview/trends`

**Authorization:** `ROLE_ADMIN`, `ROLE_DATA_STEWARD`, `ROLE_USER`

**Query Parameters:**
- `weeks` (optional): Number of weeks of historical data (default: 7)

**Response:**
```json
[
  {
    "date": "2024-11-01",
    "monthlySavings": 8420.0,
    "cumulativeSavings": 52150.0,
    "storageReclaimed": 625.3
  }
]
```

### 4. Get Archival Status Distribution

Retrieves distribution of assets by archival status for chart visualization.

**Endpoint:** `GET /api/v1/archival/overview/status-distribution`

**Authorization:** `ROLE_ADMIN`, `ROLE_DATA_STEWARD`, `ROLE_USER`

**Response:**
```json
[
  {
    "name": "Archived",
    "value": 1256,
    "color": "#10b981"
  },
  {
    "name": "Identified for Archival",
    "value": 586,
    "color": "#f59e0b"
  }
]
```

### 5. Get Recent Activities

Retrieves recent archival activities and events.

**Endpoint:** `GET /api/v1/archival/overview/recent-activities`

**Authorization:** `ROLE_ADMIN`, `ROLE_DATA_STEWARD`, `ROLE_USER`

**Query Parameters:**
- `limit` (optional): Maximum number of activities to retrieve (default: 10)

**Response:**
```json
[
  {
    "id": "1",
    "event": "Asset Archived",
    "assetName": "legacy_reports_2023.csv",
    "policy": "Quarterly Report Archival",
    "storageSize": "2.3 GB",
    "savingsAmount": 156.0,
    "status": "Success",
    "timestamp": "15 minutes ago",
    "details": "Archived to cold storage tier - no access in 180 days"
  }
]
```

### 6. Get Storage Consumers

Retrieves top storage consumers with recommendations.

**Endpoint:** `GET /api/v1/archival/overview/storage-consumers`

**Authorization:** `ROLE_ADMIN`, `ROLE_DATA_STEWARD`, `ROLE_USER`

**Query Parameters:**
- `limit` (optional): Maximum number of consumers to retrieve (default: 10)

**Response:**
```json
[
  {
    "name": "Old Analytics Datasets",
    "currentSize": "45.7 TB",
    "lastAccessed": "6 months ago",
    "cost": 2847.0,
    "recommendation": "Archive Now",
    "priority": "High"
  }
]
```

### 7. Trigger Manual Archival Run

Initiates a manual archival run for pending assets.

**Endpoint:** `POST /api/v1/archival/overview/trigger-run`

**Authorization:** `ROLE_ADMIN`, `ROLE_DATA_STEWARD`

**Response:**
```json
true
```

**Error Response:**
```json
false
```

## Error Responses

### Standard Error Format

```json
{
  "timestamp": "2024-12-13T10:30:00",
  "status": 500,
  "error": "Internal Server Error",
  "message": "An unexpected error occurred",
  "path": "/api/v1/archival/overview"
}
```

### Validation Error Format

```json
{
  "timestamp": "2024-12-13T10:30:00",
  "status": 400,
  "error": "Validation Error",
  "message": "Request validation failed",
  "path": "/api/v1/archival/overview",
  "errors": {
    "fieldName": "Field is required"
  }
}
```

## HTTP Status Codes

- `200 OK` - Request successful
- `400 Bad Request` - Invalid request parameters
- `401 Unauthorized` - Authentication required
- `403 Forbidden` - Insufficient permissions
- `404 Not Found` - Resource not found
- `500 Internal Server Error` - Server error

## Rate Limiting

Currently, no rate limiting is implemented. Consider implementing rate limiting for production environments.

## CORS Configuration

The service supports CORS for cross-origin requests. Configure CORS settings in the application properties if needed.

## Notes

1. **Policy Configuration**: Policy configuration endpoints are not included in this service due to the given instructions. These would typically be handled by a separate policy management service.

2. **Mock Data**: The current implementation uses mock data. In production, these endpoints should integrate with actual database queries and external services.

3. **Real-time Updates**: For real-time updates, consider implementing WebSocket connections or server-sent events.

4. **Caching**: Consider implementing caching for frequently accessed data like KPIs and trends.

5. **Monitoring**: All endpoints include comprehensive logging for monitoring and debugging purposes. 