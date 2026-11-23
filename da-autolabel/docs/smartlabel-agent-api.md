# SmartLabel Agent Dashboard API Documentation

## Overview

The SmartLabel Agent Dashboard API provides endpoints for retrieving real-time metrics, statistics, and operational data for the AI-powered data labeling and classification system. This API is designed to support the SmartLabel Agent UI dashboard with comprehensive data visualization and monitoring capabilities.

## Base URL

```
http://localhost:8080/api/v1/label-agent
```

## Authentication

All endpoints require JWT authentication. Include the Authorization header with your requests:

```
Authorization: Bearer <your-jwt-token>
```

## Endpoints

### 1. Get Key Performance Indicators (KPIs)

Retrieves comprehensive Key Performance Indicators for the SmartLabel Agent dashboard.

**Endpoint:** `GET /kpis`

**Response:**
```json
{
  "activeModels": 5,
  "appliedLabelsTotal": 2847,
  "appliedLabelsLast24h": 156,
  "modelAccuracy": 94.7,
  "rulePoliciesActive": 12,
  "mlPoliciesActive": 5,
  "assetsProcessed": 1247,
  "assetsProcessedLast24h": 89,
  "averageProcessingTime": 2.3,
  "errorRate": 1.2
}
```

**Response Fields:**
- `activeModels`: Number of active ML models
- `appliedLabelsTotal`: Total number of labels applied across all time
- `appliedLabelsLast24h`: Number of labels applied in the last 24 hours
- `modelAccuracy`: ML model accuracy percentage
- `rulePoliciesActive`: Number of active rule-based policies
- `mlPoliciesActive`: Number of active ML-based policies
- `assetsProcessed`: Total number of assets processed
- `assetsProcessedLast24h`: Number of assets processed in the last 24 hours
- `averageProcessingTime`: Average processing time in seconds
- `errorRate`: Error rate percentage

### 2. Get Top Applied Labels

Retrieves the most frequently applied labels with their statistics.

**Endpoint:** `GET /top-labels`

**Query Parameters:**
- `limit` (optional): Maximum number of labels to return (default: 10)

**Example:** `GET /top-labels?limit=5`

**Response:**
```json
[
  {
    "name": "PII",
    "value": 487,
    "category": "Security",
    "color": "#ef4444"
  },
  {
    "name": "Financial",
    "value": 356,
    "category": "Classification",
    "color": "#3b82f6"
  },
  {
    "name": "Customer Data",
    "value": 298,
    "category": "Domain",
    "color": "#10b981"
  }
]
```

**Response Fields:**
- `name`: Name of the label
- `value`: Number of times this label was applied
- `category`: Category of the label (Security, Classification, Domain, etc.)
- `color`: Color code for UI display

### 3. Get Rule Activities

Retrieves rule activities and performance metrics.

**Endpoint:** `GET /rule-activities`

**Query Parameters:**
- `limit` (optional): Maximum number of rules to return (default: 10)

**Example:** `GET /rule-activities?limit=5`

**Response:**
```json
[
  {
    "ruleName": "Credit Card Detection",
    "category": "PII",
    "hitCount": 142,
    "successRate": 98.5,
    "lastTriggered": "15 minutes ago",
    "assetsAffected": 23
  },
  {
    "ruleName": "Email Pattern Matching",
    "category": "Security",
    "hitCount": 89,
    "successRate": 96.2,
    "lastTriggered": "2 hours ago",
    "assetsAffected": 15
  }
]
```

**Response Fields:**
- `ruleName`: Name of the rule
- `category`: Category of the rule (PII, Financial, Customer Data, etc.)
- `hitCount`: Number of times the rule was triggered
- `successRate`: Success rate percentage
- `lastTriggered`: When the rule was last triggered (human-readable)
- `assetsAffected`: Number of assets affected by this rule

### 4. Get Recent Label Activities

Retrieves recent labeling activities and events.

**Endpoint:** `GET /recent-activities`

**Query Parameters:**
- `limit` (optional): Maximum number of activities to return (default: 10)

**Example:** `GET /recent-activities?limit=5`

**Response:**
```json
[
  {
    "id": "1",
    "event": "Labels Applied",
    "assetName": "customer_payments.csv",
    "labelsApplied": ["PII", "Financial", "Customer Data"],
    "method": "ML Model",
    "confidence": 97.8,
    "status": "Success",
    "timestamp": "5 minutes ago",
    "details": "Detected credit card numbers and customer IDs"
  },
  {
    "id": "2",
    "event": "Rule Triggered",
    "assetName": "user_profiles.json",
    "labelsApplied": ["PII", "Personal Data"],
    "method": "Rule-Based",
    "confidence": null,
    "status": "Success",
    "timestamp": "12 minutes ago",
    "details": "Applied email pattern matching rule"
  }
]
```

**Response Fields:**
- `id`: Unique identifier for the activity
- `event`: Type of event (Labels Applied, Rule Triggered, etc.)
- `assetName`: Name of the asset being processed
- `labelsApplied`: List of labels applied to the asset
- `method`: Method used for labeling (ML Model, Rule-Based, Manual)
- `confidence`: Confidence score for ML-based labeling (null for rule-based)
- `status`: Status of the labeling operation
- `timestamp`: Human-readable timestamp
- `details`: Additional details about the activity

### 5. Trigger Manual Labeling Run

Triggers a manual labeling run for immediate processing.

**Endpoint:** `POST /trigger-labeling`

**Request Body:** None required

**Response (Success):**
```json
{
  "success": true,
  "message": "Labeling run triggered successfully",
  "timestamp": "2024-01-15T10:30:00"
}
```

**Response (Failure):**
```json
{
  "success": false,
  "message": "Failed to trigger labeling run",
  "timestamp": "2024-01-15T10:30:00"
}
```

### 6. Get Complete Dashboard Data

Retrieves all dashboard data in a single request for efficiency.

**Endpoint:** `GET /dashboard`

**Query Parameters:**
- `limit` (optional): Maximum number of items to return for each category (default: 10)

**Example:** `GET /dashboard?limit=5`

**Response:**
```json
{
  "kpis": {
    "activeModels": 5,
    "appliedLabelsTotal": 2847,
    "appliedLabelsLast24h": 156,
    "modelAccuracy": 94.7,
    "rulePoliciesActive": 12,
    "mlPoliciesActive": 5,
    "assetsProcessed": 1247,
    "assetsProcessedLast24h": 89,
    "averageProcessingTime": 2.3,
    "errorRate": 1.2
  },
  "topLabels": [
    {
      "name": "PII",
      "value": 487,
      "category": "Security",
      "color": "#ef4444"
    }
  ],
  "ruleActivities": [
    {
      "ruleName": "Credit Card Detection",
      "category": "PII",
      "hitCount": 142,
      "successRate": 98.5,
      "lastTriggered": "15 minutes ago",
      "assetsAffected": 23
    }
  ],
  "recentActivities": [
    {
      "id": "1",
      "event": "Labels Applied",
      "assetName": "customer_payments.csv",
      "labelsApplied": ["PII", "Financial", "Customer Data"],
      "method": "ML Model",
      "confidence": 97.8,
      "status": "Success",
      "timestamp": "5 minutes ago",
      "details": "Detected credit card numbers and customer IDs"
    }
  ],
  "timestamp": "2024-01-15T10:30:00"
}
```

## Error Responses

All endpoints return standard HTTP status codes:

- `200 OK`: Request successful
- `400 Bad Request`: Invalid request parameters
- `401 Unauthorized`: Authentication required
- `403 Forbidden`: Insufficient permissions
- `404 Not Found`: Resource not found
- `500 Internal Server Error`: Server error

**Error Response Format:**
```json
{
  "timestamp": "2024-01-15T10:30:00",
  "status": 400,
  "error": "Bad Request",
  "message": "Invalid parameter value",
  "path": "/api/v1/label-agent/kpis"
}
```

## Rate Limiting

- **Standard Rate Limit:** 100 requests per minute per user
- **Burst Rate Limit:** 10 requests per second per user
- **Dashboard Endpoint:** 50 requests per minute per user (optimized for efficiency)

## Data Freshness

- **KPIs:** Updated in real-time based on completed labeling jobs
- **Top Labels:** Aggregated from all completed jobs
- **Rule Activities:** Based on recent job executions
- **Recent Activities:** Real-time from job completion events

## Performance Considerations

- Use the `/dashboard` endpoint for initial page load to reduce API calls
- Implement client-side caching for static data (top labels, rule activities)
- Use polling with appropriate intervals for real-time updates
- Consider implementing WebSocket connections for live activity feeds

## Integration Examples

### JavaScript/TypeScript Example

```typescript
// Fetch dashboard data
const response = await fetch('/api/v1/label-agent/dashboard?limit=10', {
  headers: {
    'Authorization': `Bearer ${token}`,
    'Content-Type': 'application/json'
  }
});

const dashboardData = await response.json();
console.log('KPIs:', dashboardData.kpis);
console.log('Top Labels:', dashboardData.topLabels);
```

### cURL Example

```bash
# Get KPIs
curl -X GET "http://localhost:8080/api/v1/label-agent/kpis" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN"

# Trigger labeling run
curl -X POST "http://localhost:8080/api/v1/label-agent/trigger-labeling" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN"
```

## Database Schema

The API is built on top of the following database tables:

- `dalab_autolabel_job`: Stores labeling job information
- `dalab_autolabel_feedback`: Stores user feedback on labeling results

## Monitoring and Logging

All API calls are logged with:
- Request timestamp
- User ID (from JWT)
- Endpoint accessed
- Response status
- Processing time
- Error details (if applicable)

## Security Considerations

- All endpoints require JWT authentication
- User context is extracted from JWT token
- Input validation on all parameters
- SQL injection protection via JPA/Hibernate
- CORS configuration for frontend integration 