# Using Spring Shell in DADiscovery

This consolidated guide explains how to use Spring Shell with the DADiscovery application across different environments.

## What is Spring Shell?

Spring Shell is a framework that enables you to build interactive command-line applications with Spring. In DADiscovery, it's used to provide command-line access to resource discovery operations, job management, and crawler inspection.

- Resource discovery operations
- Job management
- Crawler inspection 
- Log analysis

## Ways to Access Spring Shell

DADiscovery offers multiple methods to access Spring Shell commands:

### 1. Local Development Environment

```bash
# From the repository root: start the interactive shell
./gradlew :da-discovery:bootRun --args='shell'

# Run a specific command without interactive mode
./gradlew :da-discovery:bootRun --args='list-crawlers'
```

### 2. REST API (Any Environment)

The most versatile way to access shell commands is through the REST API:

```bash
# List registered crawlers
curl http://localhost:8080/api/shell/crawlers

# Create a discovery job
curl -X POST "http://localhost:8080/api/shell/create-job?provider=gcp&accountId=your-project-id&jobName=test-job&resourceIds=gcp_compute_instance,gcp_gcs_bucket"

# Execute a job
curl -X POST http://localhost:8080/api/shell/exec-job/your-job-uuid

# Get job details
curl http://localhost:8080/api/shell/get-job/your-job-uuid

# List jobs with filtering
curl "http://localhost:8080/api/shell/list-jobs?provider=gcp&status=PENDING"

# Trigger log analysis
curl -X POST "http://localhost:8080/api/shell/log-analyzer-test?provider=gcp&account=my-project-id"
```

### 3. Docker Container

```bash
# Run a specific command in the container
docker exec -it da-discovery-app-1 java -jar /app/app.jar list-crawlers

# Run interactive shell in the container
docker exec -it da-discovery-app-1 java -jar /app/app.jar shell
```

## Key Commands

Here are the most useful commands available:

### Resource Discovery

```bash
# List all registered resource crawlers
list-crawlers
```

**Output:**
```
Registered Resource Crawlers:
 - GcsResourceCrawler
 - ComputeInstanceCrawler
 - BigQueryDatasetCrawler
 - EC2InstanceCrawler
 - S3BucketCrawler
 - AzureVMCrawler
 - ...
```

```bash
# Test a crawler against a specific account
crawler-test --provider gcp --type gcp_compute_instance --account my-project-id

# Create a discovery job
create-job --provider gcp --account my-project-id --name "GCP VM Scan" --resources gcp_compute_instance,gcp_gcs_bucket
```

**Output:**
```
Successfully created job with ID: 550e8400-e29b-41d4-a716-446655440000
```

```bash
# Execute a job
exec-job --id 550e8400-e29b-41d4-a716-446655440000
```

**Output:**
```
Job 550e8400-e29b-41d4-a716-446655440000 execution requested successfully.
Current status: RUNNING
```

```bash
# List jobs with optional filtering
list-jobs --provider gcp --status COMPLETED
```

**Output:**
```
ID: 550e8400-e29b-41d4-a716-446655440000 | Name: GCP VM Scan | Provider: GCP | Account: my-project-id | Status: COMPLETED | Type: RESOURCE_CRAWLER
ID: 660e8400-e29b-41d4-a716-446655440001 | Name: GCP Storage Scan | Provider: GCP | Account: my-project-id | Status: COMPLETED | Type: RESOURCE_CRAWLER
```

```bash
# Get job details
get-job --id 550e8400-e29b-41d4-a716-446655440000
```

**Output:**
```
--- Job Details ---
ID:         550e8400-e29b-41d4-a716-446655440000
Name:       GCP VM Scan
Type:       RESOURCE_CRAWLER
Provider:   GCP
Account ID: my-project-id
Status:     COMPLETED
Created At: 2023-05-17T10:00:00
Updated At: 2023-05-17T10:15:23
Completed:  2023-05-17T10:15:20
Parameters: resourceTypesToCrawl=[gcp_compute_instance,gcp_gcs_bucket]
Progress:   100%
Resources:  214 discovered
```

### Log Analysis

```bash
# List available log analyzers
log-analyzer-list
```

**Output:**
```
Available Log Analyzers:
 - GcpLogAnalyzer (Cloud Provider: GCP)
 - AwsLogAnalyzer (Cloud Provider: AWS)
 - AzureLogAnalyzer (Cloud Provider: AZURE)
 - OciLogAnalyzer (Cloud Provider: ORACLE)
```

```bash
# Test a log analyzer against specific time range
log-analyzer-test --provider gcp --account my-project-id --start-time 2023-01-01T00:00:00Z --end-time 2023-01-02T00:00:00Z

# Create a log analysis job
create-job --type LOG_ANALYZER --provider aws --account 123456789012 --name "AWS Log Analysis"
```

### System Information

```bash
# Show application status
app-status
```

**Output:**
```
--- Application Status ---
Name:           da-discovery
Version:        1.2.3
Git Commit:     abc1234
Build Time:     2023-04-15T13:45:30Z
Active Profiles: dev
Uptime:         12h 34m 56s
Crawlers:       23 registered
Jobs:           5 running, 32 completed, 2 failed
```

```bash
# Show active Spring profiles
profiles
```

**Output:**
```
Active Spring Profiles:
 - dev
 - shell
```

## Best Practices

Here are some recommended practices for using Spring Shell:

1. **In Production**: Use the REST API rather than direct shell access for better security and integration
2. **For Automation**: Create scripts that use the REST API to automate discovery tasks
3. **For Debugging**: Use interactive shell mode in development for exploring and debugging
4. **For Reports**: Combine shell commands with output processing tools like `jq` to generate reports
5. **For CI/CD**: Integrate shell commands into pipelines to verify discovery functionality

## How Spring Shell Works in DADiscovery

DADiscovery implements a custom terminal configuration that allows Spring Shell to work in any environment:

1. **Custom Terminal**: The `ShellTerminalConfig` creates a "dumb" terminal that functions without a real TTY, making it container-friendly
2. **Command Handling**: The application detects shell commands from arguments or REST requests
3. **Execution Modes**:
   - **Interactive**: Provides a command prompt for entering multiple commands
   - **Single Command**: Executes one command and exits
   - **REST API**: Executes commands via HTTP endpoints

## Troubleshooting

If you encounter issues:

1. **Command not found**:
   - Verify Spring Shell is enabled (check logs for "Started ShellApplication")
   - Ensure you're using the correct command syntax

2. **Connection issues**:
   - For REST API, verify the application is running and the port is correct
   - For Docker, make sure you're connecting to the right container

3. **Terminal errors**:
   - In containerized environments, ensure you're using the dumb terminal configuration

4. **Permission errors**:
   - Check authentication requirements for the REST API

## Use Cases

Spring Shell is particularly useful for:

1. **Administrative tasks**: Managing jobs and crawlers
2. **Testing**: Quick verification of discovery functionality
3. **Automation**: Scripts can use REST API for scheduled tasks
4. **Development**: Exploring application capabilities directly

## Example: Complete Discovery Workflow

```bash
# 1. List available crawlers
list-crawlers

# 2. Create a discovery job
create-job --provider gcp --account my-project --resources gcp_compute_instance --name "VM Discovery"

# 3. Execute the job
exec-job --id <job-id-from-previous-step>

# 4. Check job status
get-job --id <job-id>

# 5. List discovered resources
list-resources --provider gcp --type gcp_compute_instance

# 6. Create a log analysis job
create-job --type LOG_ANALYZER --provider gcp --account my-project --name "GCP Log Analysis"


1. Ensure your Spring Boot application is running with the correct profile
2. Check that Spring Shell is not disabled in your configuration
3. Verify you have the required permissions to execute commands
4. For REST API, check your authentication and correct endpoint URL 
# 7. Execute the log analysis
exec-job --id <log-job-id>
``` 