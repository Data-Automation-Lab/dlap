# Running Discovery Crawlers in DADiscovery

This guide explains how the resource discovery functionality is implemented in the DADiscovery application and provides instructions for using and testing it.

## Architecture Overview

DADiscovery implements a multi-cloud resource discovery system using a modular, event-driven architecture. Here's how the key components work together:

### Core Components

1. **IResourceCrawler Interface**: Defines the contract for cloud-specific resource crawlers

   - Each crawler is responsible for discovering specific resource types (VMs, databases, storage, etc.)
   - Crawlers publish events when resources are discovered

2. **IDiscoveryService Interface**: Higher-level service that coordinates discovery operations

   - Manages job creation, execution, and tracking
   - Integrates with the catalog system

3. **IJobExecutor Interface**: Responsible for executing discovery jobs

   - Supports different execution modes (DEFAULT, NIFI, SPARK)
   - Handles job lifecycle (created, execution, cancellation, pausing, resuming)

4. **IDiscoveryScheduler Interface**: Manages the scheduling of discovery jobs

   - Supports both immediate and scheduled (periodic) execution
   - Maintains a registry of job executors

5. **IResourceCrawlerRegistry Interface**: Registry for crawler implementations
   - Allows lookup of crawlers by provider, resource type, or service
   - Manages crawler registration and deregistration

### Provider-Specific Implementations

Each cloud provider has its own implementations of these interfaces:

- **GCP**: `GCPResourceCrawler`, `GCPDiscoveryServiceImpl`
- **AWS**: `AWSResourceCrawler`, `AWSDiscoveryServiceImpl`
- **Azure**: `AzureResourceCrawler`, `AzureDiscoveryServiceImpl`
- **Oracle Cloud**: `OCIResourceCrawler`, `OCIDiscoveryServiceImpl`

Each provider-specific crawler handles authentication, API calls, and resource mapping for that provider.

## Resource Discovery Flow

1. **Job Creation**: A discovery job is created via API or scheduler
2. **Job Configuration**: The job is configured with parameters (provider, account, resource types)
3. **Crawler Selection**: Appropriate crawlers are selected based on the provider and resource types
4. **Execution**: The job is executed by the appropriate executor
5. **Resource Discovery**: Crawlers discover resources and publish events to Kafka.
6. **Catalog Integration**: Discovered resources are stored in the catalog by a Kafka consumer.
7. **Completion**: Job status is updated and notifications are sent

## Using the Discovery API

### Creating a Discovery Job

To create a discovery job through the REST API:

```bash
# Example: Create a job to discover GCP compute instances
curl -X POST http://localhost:8080/api/v1/discovery/gcp/jobs \
  -H "Content-Type: application/json" \
  -d '{
    "accountId": "my-gcp-project-id",
    "resourceTypes": ["gcp_compute_instance"],
    "parameters": {
      "region": "us-central1"
    },
    "jobName": "GCP VM Discovery"
  }'
```

### Available Resource Types

The system supports various resource types for each provider:

- **GCP**:

  - `gcp_compute_instance`: Compute Engine VMs
  - `gcp_compute_disk`: Persistent Disks
  - `gcp_bigquery_dataset`: BigQuery Datasets
  - `gcp_bigquery_table`: BigQuery Tables

- **AWS**:

  - `aws_ec2_instance`: EC2 Instances
  - `aws_s3_bucket`: S3 Buckets
  - `aws_rds_instance`: RDS Instances
  - `aws_dynamodb_table`: DynamoDB Tables
  - `aws_lambda_function`: Lambda Functions

- **Azure**:

  - `azure_vm_instance`: Virtual Machines
  - `azure_blob_container`: Blob Storage Containers
  - `azure_sql_database`: SQL Databases

- **Oracle Cloud**:
  - `oracle_compute_instance`: Compute Instances
  - `oracle_block_volume`: Block Volumes

### Scheduling Jobs

To schedule a job to run periodically:

```bash
# Example: Schedule a job to run daily at midnight
curl -X POST http://localhost:8080/api/v1/discovery/scheduler/jobs \
  -H "Content-Type: application/json" \
  -d '{
    "jobId": "job-uuid-from-creation",
    "scheduleInfo": "0 0 * * *"
  }'
```

## Testing Discovery Functionality

### Prerequisites

1. Ensure you have the necessary credentials configured:

   - GCP: Service account key or application default credentials
   - AWS: Access key/secret or IAM role
   - Azure: Client ID, tenant ID, and client secret or managed identity
   - Oracle: Configuration file with tenancy OCID, user OCID, etc.

2. Configure the providers you want to enable in `application.yml`:

```yaml
cloud:
  provider:
    gcp:
      enabled: true # Enable/disable Google Cloud Platform
    aws:
      enabled: false # Enable/disable Amazon Web Services
    azure:
      enabled: false # Enable/disable Microsoft Azure
    oci:
      enabled: false # Enable/disable Oracle Cloud Infrastructure
```

### Testing Individual Crawlers

For development and testing, you can use the Spring Shell CLI to test individual crawlers:

1. Start the application with the shell profile:

   ```bash
   ./gradlew bootRun --args='--spring.profiles.active=dev,shell'
   ```

2. Use the shell commands to test crawlers:

   ```bash
   # List available crawlers
   discovery-shell:> crawler list

   # Test a specific crawler
   discovery-shell:> crawler test --provider gcp --type gcp_compute_instance --account my-project-id
   ```

### Troubleshooting

If you encounter issues with discovery:

1. **Check Credentials**: Ensure your cloud provider credentials are valid and have sufficient permissions
2. **Verify Connectivity**: Check if the application can connect to the cloud provider APIs
3. **Review Logs**: Look for errors in the logs, especially in the crawler-specific classes
4. **Check Job Status**: Use the job API to check the status of your discovery jobs

## Extending Discovery

### Adding New Resource Types

To add support for a new resource type:

1. Define the resource type constant in `ResourceTypeConstants`
2. Create a new crawler implementation that extends the provider-specific abstract crawler
3. Implement the required methods for discovery
4. Register the crawler in the appropriate configuration class

### Implementing Custom Job Executors

To implement a custom job executor (e.g., for distributed processing):

1. Create a class that implements `IJobExecutor`
2. Implement the required methods for job execution
3. Register the executor with the scheduler using `registerExecutor`

## Performance Considerations

- For large environments, consider using the NIFI or SPARK execution modes
- Use resource type filtering to limit discovery scope
- Schedule jobs during off-peak hours
- Consider region-specific jobs for global environments
