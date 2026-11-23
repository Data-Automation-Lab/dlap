# Using Log Analyzers in DADiscovery

This guide explains how to use the log analyzer functionality in the DADiscovery application to monitor audit logs and detect changes in cloud resources.

## Architecture Overview

DADiscovery implements a multi-cloud log analysis system using an event-driven architecture. Log analyzers monitor audit, system, and application logs to detect resource creation, modification, and deletion events.

### Core Components

1. **ILogAnalyzer Interface**: Defines the contract for cloud-specific log analyzers

   - Provides methods to analyze logs from different time periods
   - Publishes events when resource changes are detected
   - Supports filtering by resource type and change type

2. **ILogAnalyzerRegistry Interface**: Registry for managing log analyzer implementations

   - Provides lookup of analyzers by cloud provider
   - Handles registration and unregistration of analyzers

3. **Provider-Specific Implementations**: Each cloud provider has its own implementation
   - GCP: Analyzes Cloud Audit Logs
   - AWS: Analyzes CloudTrail logs
   - Azure: Analyzes Azure Activity Logs
   - Oracle: Analyzes OCI Audit logs

### Event Flow

1. **Log Analysis Triggering**: Analysis can be initiated via API or scheduler
2. **Log Retrieval**: The analyzer retrieves logs from the cloud provider
3. **Log Processing**: Logs are processed to identify resource changes
4. **Event Publication**: Change events are published to Kafka
5. **Catalog Update**: A Kafka consumer updates the resource catalog with detected changes

## Integration with Job Execution Framework

Log analyzer functionality uses the same job execution framework as resource crawlers, providing consistent management capabilities:

### Shared Infrastructure

1. **IJobExecutor Interface**: Both resource crawlers and log analyzers are managed by the job executor framework

   - The same executor implementations (DEFAULT, NIFI, SPARK) are available for log analysis
   - This allows for appropriate execution strategies based on workload size and complexity

2. **DiscoveryJob Model**: Log analysis tasks use the common `DiscoveryJob` entity

   - Jobs are identified by type (`JobType.LOG_ANALYZER`)
   - This provides unified job lifecycle management, monitoring, and history

3. **JobType Enum**: The system explicitly supports two job types:
   - `RESOURCE_CRAWLER`: For cloud resource discovery
   - `LOG_ANALYZER`: For log analysis and change detection

### Execution Model

Log analyzer jobs are executed through `LogAnalyzerCallable` or `LogAnalysisCallable` classes, which:

- Implement the `Callable<Boolean>` interface
- Retrieve the appropriate log analyzer for the provider
- Handle checkpoint management to track progress
- Process logs within the requested time window
- Publish events for detected changes

### Benefits of Shared Framework

Using the common job execution framework provides several advantages:

- Consistent scheduling capabilities for both discovery and log analysis
- Common monitoring interface for all job types
- Unified API for job management (create, configure, execute, schedule)
- Scalable execution modes for handling large log volumes
- Reliable tracking of execution statistics and history

## Configuring Log Analyzers

Each cloud provider's log analyzer has specific configuration requirements:

### GCP Log Analyzer

GCP Log Analyzer can pull logs from different source types:

1. **API**: Directly queries the Cloud Logging API
2. **BigQuery**: Reads logs exported to BigQuery tables
3. **Cloud Storage**: Reads logs exported to Cloud Storage buckets

Configuration endpoints:

```bash
# Get source details for a specific account and source type
GET /api/v1/gcp/log-config/{accountId}/source/{sourceType}

# Get exclusion filters for a specific account
GET /api/v1/gcp/log-config/{accountId}/filters

# Configure source details (API, BIGQUERY, GCS)
POST /api/v1/gcp/log-config/{accountId}/source/{sourceType}
{
  "details": {
    "projectId": "my-gcp-project",
    "datasetId": "audit_logs",  # for BigQuery
    "tableId": "cloudaudit_data", # for BigQuery
    "bucketName": "audit-logs-bucket", # for GCS
    "objectPrefix": "cloudaudit/" # for GCS
  }
}

# Configure exclusion filters
POST /api/v1/gcp/log-config/{accountId}/filters
{
  "filters": [
    "protoPayload.methodName=~\"^storage.*.get$\"",
    "protoPayload.methodName=~\"^compute.*.list$\""
  ]
}
```

### AWS Log Analyzer

The discovery service has no live AWS log source and no AWS log-config endpoint.
`AWSLoggingAdapter` returns an empty list unless a CloudTrail replay file is configured, for
offline tests. Live CloudTrail ingest runs in da-logingest (`CloudTrailLogSource`, CloudTrail
LookupEvents), which reads management events only.

### Azure Log Analyzer

Not implemented. `AzureLogAnalyzer` logs a warning and reads nothing, and there is no Azure
log-config endpoint.

## Triggering Log Analysis

You can trigger log analysis manually through the API or set up scheduled analysis:

### Manual Trigger

```bash
# Example: Analyze GCP logs for a specific time range
POST /api/v1/logs/analyze
{
  "provider": "GCP",
  "accountId": "my-gcp-project",
  "startTime": "2023-01-01T00:00:00Z",
  "endTime": "2023-01-02T00:00:00Z",
  "options": {
    "resourceTypes": ["gcp_compute_instance", "gcp_compute_disk"],
    "changeTypes": ["CREATE", "UPDATE", "DELETE"],
    "filters": {
      "serviceAccount": "compute@developer.gserviceaccount.com",
      "minSeverity": "WARNING"
    },
    "batchSize": 100,
    "parallelism": 4,
    "includeMetadata": true,
    "logLevel": "INFO"
  }
}
```

### Scheduled Analysis

You can configure periodic log analysis using the scheduler:

```bash
# Schedule log analysis job
POST /api/v1/scheduler/jobs
{
  "jobType": "LOG_ANALYSIS",
  "provider": "AWS",
  "accountId": "123456789012",
  "scheduleInfo": "0 */6 * * *",  # Run every 6 hours
  "parameters": {
    "lookbackPeriod": "PT6H",      # Analyze logs from the last 6 hours
    "resourceTypes": ["aws_ec2_instance", "aws_s3_bucket"],
    "changeTypes": ["CREATE", "UPDATE", "DELETE"]
  }
}
```

## Analysis Options

The `AnalysisOptions` class provides several configuration options:

- **resourceTypes**: List of resource types to monitor (e.g., `gcp_compute_instance`)
- **changeTypes**: Types of changes to detect (`CREATE`, `UPDATE`, `DELETE`)
- **filters**: Provider-specific filters to apply to logs
- **batchSize**: Number of log entries to process in each batch
- **parallelism**: Number of parallel threads to use for processing
- **includeMetadata**: Whether to include additional metadata in results
- **logLevel**: Minimum log level to process (`INFO`, `WARNING`, `ERROR`)

## Testing Log Analyzers

For testing and development, you can use the Spring Shell CLI:

1. Start the application with the shell profile:

   ```bash
   ./gradlew bootRun --args='--spring.profiles.active=dev,shell'
   ```

2. Use the shell commands to test log analyzers:

   ```bash
   # List available log analyzers
   discovery-shell:> log-analyzer list

   # Test a specific log analyzer
   discovery-shell:> log-analyzer test --provider gcp --account my-project-id --start-time 2023-01-01T00:00:00Z --end-time 2023-01-02T00:00:00Z
   ```

## Monitoring and Troubleshooting

### Monitoring Log Analysis Activity

The application provides several ways to monitor log analysis activity:

1. **Metrics Dashboard**: Shows counts of changes detected by type and provider
2. **Audit Logs**: Records when log analysis jobs start and complete
3. **Job Status API**: Provides current status of running log analysis jobs

### Troubleshooting

If you encounter issues with log analyzers:

1. **Check Permissions**: Grant the Reader role from
   [`docs/guides/cloud-permissions.md`](../../../docs/guides/cloud-permissions.md). Each permission
   there names the call that needs it. For the GCP log analyzer:

   - API source: `logging.logEntries.list` and, for Data Access audit logs,
     `logging.privateLogEntries.list` on the project (`GCPLoggingAdapter`).
   - BigQuery source: `bigquery.jobs.create` on the project and `bigquery.tables.getData` on the
     sink dataset only (`BigQueryLogFetcherStrategy`).
   - Cloud Storage source: `storage.objects.list` and `storage.objects.get` on the sink bucket
     only (`GcsLogFetcherStrategy`).

   The AWS and Azure analyzers make no cloud call, so they need no permission.

2. **Verify Log Export Configuration**:

   - For BigQuery or Cloud Storage sources, verify logs are being exported correctly
   - For CloudTrail, ensure logging is enabled and logs are being delivered to S3

3. **Check Log Retention**:

   - Cloud providers may have default retention periods for logs
   - Ensure logs are available for the time period you're analyzing

4. **Review Application Logs**:
   - Check the application logs for any errors or warnings from the log analyzers
   - Increase log level to DEBUG for more detailed information

## Performance Considerations

- For large environments with high log volume, consider using BigQuery/S3 export rather than API calls
- Use resource type filtering to limit the scope of log analysis
- Set appropriate batch sizes to balance throughput and memory usage
- Schedule analysis during off-peak hours
- Consider region-specific analysis for global deployments

## Security Considerations

- Log data may contain sensitive information; ensure appropriate access controls
- Use encryption for log storage and transmission
- Regularly audit who has access to log analysis results
- Be aware of data residency requirements when configuring log exports

## Configuring Operation Mappings

The application uses a configurable mapping system to translate cloud provider operation names (like "create", "update", "delete") to standardized change types used internally.

### Default Mappings

The default operation mappings are defined in `src/main/resources/config/log-analyzers.yml`
under the cloud-neutral `dlap.log-analyzer` prefix (renamed from the
legacy `gcp.log-analyzer` prefix per R-P1-7 / finding N18; the mapping
applies to every `ILogAnalyzer` implementation, not just GCP):

```yaml
dlap:
  log-analyzer:
    operation-mappings:
      # CREATE operations
      - operation: 'create'
        change-type: 'CREATE'
      - operation: 'insert'
        change-type: 'CREATE'

      # UPDATE operations
      - operation: 'update'
        change-type: 'UPDATE'
      - operation: 'patch'
        change-type: 'UPDATE'

      # DELETE operations
      - operation: 'delete'
        change-type: 'DELETE'

      # ACCESS operations
      - operation: 'read'
        change-type: 'ACCESS'
      - operation: 'get'
        change-type: 'ACCESS'
      - operation: 'list'
        change-type: 'ACCESS'

      # PERMISSION operations
      - operation: 'iam'
        change-type: 'PERMISSION'
      - operation: 'setiam'
        change-type: 'PERMISSION'
```

### Customizing Mappings

To customize the operation mappings:

1. Create a new YAML file with your custom mappings
2. Import the file in your application configuration:

```yaml
spring:
  config:
    import: classpath:/config/custom-log-mappings.yml
```

3. Define your custom mappings in the imported file:

```yaml
dlap:
  log-analyzer:
    operation-mappings:
      - operation: 'customOperation'
        change-type: 'CREATE'
      # Add more mappings as needed
```

The valid change types are:

- `CREATE`: Resource was created
- `UPDATE`: Resource was modified
- `DELETE`: Resource was deleted
- `ACCESS`: Resource was accessed/read
- `PERMISSION`: Resource permissions were changed
- `UNKNOWN`: Catchall for unrecognized operations

### Implementation

The mappings are managed by the `LogAnalyzerProperties` class, which reads the configuration at application startup. These mappings are then used by the `GcpLogAnalyzer` to determine the appropriate change type based on the operation string in the cloud provider's audit logs.

```java
// Example of how the mapping is used
ResourceChange.ChangeType changeType = OPERATION_TO_CHANGE_TYPE.getOrDefault(
  operationString.toLowerCase(),
  ResourceChange.ChangeType.UNKNOWN
);

```
