# DALab Monitoring & Observability Setup

**Created:** January 2, 2025  
**Status:** PRODUCTION READY  
**Components:** Prometheus, Grafana, Alertmanager, Node Exporter

## 📋 **Table of Contents**

1. [🎯 Overview](#-overview)
2. [🏗️ Architecture](#️-architecture)
3. [📊 Monitoring Components](#-monitoring-components)
4. [🔧 Configuration](#-configuration)
5. [📈 Dashboards](#-dashboards)
6. [🚨 Alerting](#-alerting)
7. [📝 Metrics Collection](#-metrics-collection)
8. [🔍 Troubleshooting](#-troubleshooting)
9. [📚 Best Practices](#-best-practices)

---

## 🎯 **Overview**

The DALab monitoring stack provides comprehensive observability across all microservices, infrastructure components, and business metrics. The stack includes:

- **Prometheus**: Metrics collection and storage
- **Grafana**: Visualization and dashboards
- **Alertmanager**: Alert routing and notification
- **Node Exporter**: System-level metrics
- **Custom Business Metrics**: Data governance, compliance, and cost tracking

## 🏗️ **Architecture**

```
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│   DALab Apps    │    │  Infrastructure │    │   Business      │
│                 │    │                 │    │   Metrics       │
│ • da-discovery  │    │ • PostgreSQL    │    │ • Governance    │
│ • da-catalog    │    │ • Kafka         │    │ • Compliance    │
│ • da-policy     │    │ • Redis         │    │ • Cost Analysis │
│ • da-reporting  │    │ • Keycloak      │    │ • Data Quality  │
│ • da-autolabel  │    │ • Nginx         │    │ • Event Metrics │
│ • da-archival   │    │                 │    │                 │
│ • da-delete     │    │                 │    │                 │
│ • da-compliance │    │                 │    │                 │
│ • da-admin      │    │                 │    │                 │
└─────────┬───────┘    └─────────┬───────┘    └─────────┬───────┘
          │                      │                      │
          └──────────────────────┼──────────────────────┘
                                 │
                    ┌─────────────▼─────────────┐
                    │      Prometheus           │
                    │   • Metrics Collection    │
                    │   • Alert Rules           │
                    │   • Recording Rules       │
                    │   • Service Discovery     │
                    └─────────────┬─────────────┘
                                  │
                    ┌─────────────▼─────────────┐
                    │     Alertmanager          │
                    │   • Alert Routing         │
                    │   • Notification          │
                    │   • Deduplication         │
                    └─────────────┬─────────────┘
                                  │
                    ┌─────────────▼─────────────┐
                    │       Grafana             │
                    │   • Dashboards            │
                    │   • Visualizations        │
                    │   • Alert Management      │
                    └───────────────────────────┘
```

## 📊 **Monitoring Components**

### **Prometheus Configuration**

**File**: `deployment/platform/prometheus/prometheus.yml`

**Key Features**:
- **Service Discovery**: Automatic discovery of all 9 microservices
- **Infrastructure Monitoring**: PostgreSQL, Kafka, Redis, Keycloak
- **System Metrics**: Node Exporter for host-level metrics
- **Alert Rules**: Comprehensive alerting for all components
- **Recording Rules**: Pre-computed metrics for performance optimization

**Scrape Targets**:
- **Microservices**: All 9 DALab services on port 8080
- **Infrastructure**: PostgreSQL, Kafka, Redis, Keycloak
- **System**: Node Exporter, Docker daemon
- **Monitoring**: Prometheus, Grafana, Alertmanager

### **Grafana Dashboards**

**Location**: `deployment/platform/grafana/provisioning/dashboards/`

**Available Dashboards**:
1. **DALab System Overview** (`dalab-overview.json`)
   - System health status
   - CPU, memory, disk usage
   - HTTP request rates and response times
   - JVM heap usage
   - Infrastructure metrics

2. **DALab Business Metrics** (`dalab-business-metrics.json`)
   - Data governance score
   - Compliance violations
   - Policy evaluation success rates
   - Asset discovery and labeling rates
   - Storage usage and costs
   - Data quality metrics

### **Alertmanager Configuration**

**File**: `deployment/platform/alertmanager/alertmanager.yml`

**Alert Routing**:
- **Critical Alerts**: Email to admin@dalab.com + Slack
- **Warning Alerts**: Email to ops@dalab.com
- **Webhook Integration**: Custom webhook endpoint
- **Deduplication**: Prevents alert spam

## 🔧 **Configuration**

### **Environment Variables**

Add to your `.env` file:

```bash
# Grafana Configuration
GRAFANA_ADMIN_PASSWORD=your_secure_password

# Alertmanager Configuration
ALERTMANAGER_SMTP_HOST=your_smtp_host
ALERTMANAGER_SMTP_PORT=587
ALERTMANAGER_SMTP_USER=alerts@dalab.com
ALERTMANAGER_SMTP_PASSWORD=your_smtp_password

# Slack Integration (optional)
SLACK_WEBHOOK_URL=https://hooks.slack.com/services/YOUR/SLACK/WEBHOOK
```

### **Service Configuration**

All microservices include Prometheus metrics via Spring Boot Actuator:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  metrics:
    export:
      prometheus:
        enabled: true
  endpoint:
    health:
      show-details: always
```

## 📈 **Dashboards**

### **System Overview Dashboard**

**Key Panels**:
- **Service Health**: Real-time status of all services
- **Resource Usage**: CPU, memory, disk utilization
- **Performance Metrics**: Response times, error rates
- **Infrastructure**: Database connections, Kafka lag
- **Business Metrics**: Governance score, compliance violations

**Access**: http://localhost:3001 (Grafana)

### **Business Metrics Dashboard**

**Key Panels**:
- **Data Governance Score**: Real-time governance assessment
- **Compliance Monitoring**: Violation tracking and trends
- **Policy Performance**: Evaluation success rates
- **Asset Management**: Discovery and labeling rates
- **Cost Analysis**: Storage and processing costs
- **Data Quality**: Quality scores and issues

## 🚨 **Alerting**

### **Alert Categories**

1. **System Alerts**
   - High CPU/Memory/Disk usage
   - Service unavailability
   - Container OOM kills

2. **Application Alerts**
   - High response times (>2s)
   - High error rates (>5%)
   - JVM heap issues
   - High GC time

3. **Infrastructure Alerts**
   - Database connection issues
   - Kafka consumer lag
   - Redis memory usage
   - Keycloak service down

4. **Business Alerts**
   - High compliance violations
   - Low governance score
   - Policy evaluation failures
   - Data quality issues

5. **Security Alerts**
   - Authentication failures
   - Unauthorized access attempts
   - Keycloak service issues

### **Alert Severity Levels**

- **Critical**: Immediate attention required
- **Warning**: Monitor and investigate
- **Info**: Informational alerts

## 📝 **Metrics Collection**

### **Custom Business Metrics**

The DALab platform exposes custom business metrics:

```java
// Example: Compliance violations counter
@CounterMetric(name = "compliance_violations_total", 
               description = "Total number of compliance violations")
public void recordViolation(String regulation, String severity) {
    // Implementation
}

// Example: Data governance score gauge
@GaugeMetric(name = "data_governance_score", 
             description = "Current data governance score")
public double getGovernanceScore() {
    // Implementation
}
```

### **Key Metrics**

**System Metrics**:
- `up`: Service availability (0/1)
- `http_server_requests_seconds`: Request duration
- `jvm_memory_used_bytes`: Memory usage
- `process_cpu_usage`: CPU utilization

**Business Metrics**:
- `compliance_violations_total`: Compliance violations
- `data_governance_score`: Governance score
- `policy_evaluations_total`: Policy evaluations
- `assets_discovered_total`: Asset discovery
- `storage_cost_total`: Storage costs

## 🔍 **Troubleshooting**

### **Common Issues**

1. **Prometheus Not Scraping**
   ```bash
   # Check Prometheus targets
   curl http://localhost:9090/api/v1/targets
   
   # Check service health
   curl http://localhost:8080/actuator/health
   ```

2. **Grafana No Data**
   ```bash
   # Check datasource connection
   curl http://localhost:3001/api/datasources
   
   # Check Prometheus connectivity
   curl http://localhost:9090/api/v1/query?query=up
   ```

3. **Alerts Not Firing**
   ```bash
   # Check alert rules
   curl http://localhost:9090/api/v1/rules
   
   # Check Alertmanager
   curl http://localhost:9093/api/v1/alerts
   ```

### **Health Checks**

All monitoring components include health checks:

```bash
# Prometheus
curl http://localhost:9090/-/healthy

# Grafana
curl http://localhost:3001/api/health

# Alertmanager
curl http://localhost:9093/-/healthy

# Node Exporter
curl http://localhost:9100/-/healthy
```

## 📚 **Best Practices**

### **Performance Optimization**

1. **Recording Rules**: Use recording rules for expensive queries
2. **Scrape Intervals**: Adjust based on metric volatility
3. **Retention**: Configure appropriate retention periods
4. **Label Cardinality**: Avoid high cardinality labels

### **Alert Design**

1. **Clear Thresholds**: Set meaningful alert thresholds
2. **Descriptive Messages**: Include actionable information
3. **Escalation Paths**: Define clear escalation procedures
4. **Testing**: Regularly test alert delivery

### **Dashboard Design**

1. **Consistent Layout**: Use consistent panel layouts
2. **Color Coding**: Use consistent color schemes
3. **Time Ranges**: Set appropriate default time ranges
4. **Refresh Rates**: Configure appropriate refresh intervals

### **Security**

1. **Authentication**: Enable Grafana authentication
2. **Network Security**: Use internal networks
3. **Credential Management**: Secure all credentials
4. **Access Control**: Implement role-based access

---

## 🚀 **Quick Start**

1. **Start Monitoring Stack** (quickstart `monitoring` profile):
   ```bash
   cd deployment/environments/dev
   docker compose --profile monitoring up -d prometheus grafana
   ```
   For production monitoring, see [`PRODUCTION.md`](../PRODUCTION.md) §6.

2. **Access Dashboards**:
   - Grafana: http://localhost:3001 (admin/admin)
   - Prometheus: http://localhost:9090
   - Alertmanager: http://localhost:9093

3. **Verify Metrics**:
   ```bash
   # Check Prometheus targets
   curl http://localhost:9090/api/v1/targets
   
   # Check basic metrics
   curl http://localhost:9090/api/v1/query?query=up
   ```

4. **Import Dashboards**:
   - Navigate to Grafana
   - Import dashboard JSON files from `deployment/platform/grafana/provisioning/dashboards/`

---

**Last Updated**: January 2, 2025  
**Version**: 1.0  
**Author**: DALab Development Team 