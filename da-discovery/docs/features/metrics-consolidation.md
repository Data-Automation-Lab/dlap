# Metrics Consolidation Using Event-Driven Architecture

This document outlines the plan to consolidate various metrics collection mechanisms in the DADiscovery service by leveraging our existing event-driven architecture.

## Current State

We currently have several fragmented metrics collection mechanisms at the processor level:

1. **LogMonitoringProcessor**: 
   - Processes LogEvents
   - In-memory metrics only
   - No persistence
   - Duplicates metrics collection logic

2. **ResourceMonitoringProcessor**:
   - Processes ResourceEvents
   - Uses shared MetricsCollector
   - In-memory metrics only
   - No persistence

3. **MetricsCollector**:
   - Shared collector used by processors
   - In-memory storage only
   - Basic logging of metrics

4. **CloudResourceUsageStats**:
   - JPA Entity for resource-specific stats
   - Proper persistence
   - Time-windowed metrics

## Goals

1. Consolidate metrics processing at the event processor level
2. Implement proper persistence for all metrics
3. Standardize metrics collection and reporting
4. Enable real-time monitoring and historical analysis
5. Remove duplicate metrics collection logic

## Architecture Overview

### 1. Unified Event Processors

Replace existing monitoring processors with unified versions:

```java
@Component
public class UnifiedMetricsProcessor implements IEventProcessor<Event> {
    private final MeterRegistry meterRegistry;
    private final MetricsRepository metricsRepository;
    
    @Override
    public boolean canProcess(Event event) {
        return event instanceof ResourceEvent || event instanceof LogEvent;
    }
    
    @Override
    public void process(Event event) {
        if (event instanceof ResourceEvent) {
            processResourceEvent((ResourceEvent) event);
        } else if (event instanceof LogEvent) {
            processLogEvent((LogEvent) event);
        }
    }
    
    private void processResourceEvent(ResourceEvent event) {
        // Extract metrics from ResourceEvent
        // Update both real-time and persistent metrics
    }
    
    private void processLogEvent(LogEvent event) {
        // Extract metrics from LogEvent
        // Update both real-time and persistent metrics
    }
    
    @Override
    public int getPriority() {
        return 200; // After main business logic
    }
}
```

### 2. Metrics Storage

#### Real-time Metrics (MeterRegistry)
```java
@Component
public class MetricsService {
    private final MeterRegistry meterRegistry;
    
    public void recordResourceMetric(String resourceId, String metricName, double value, Tags tags) {
        meterRegistry.gauge(
            "discovery.resource." + metricName,
            tags.and(Tag.of("resourceId", resourceId)),
            value
        );
    }
    
    public void recordEventMetric(String eventType, String provider, String changeType) {
        meterRegistry.counter(
            "discovery.events",
            Tags.of(
                Tag.of("eventType", eventType),
                Tag.of("provider", provider),
                Tag.of("changeType", changeType)
            )
        ).increment();
    }
}
```

#### Persistent Metrics (JPA)
```java
@Entity
@Table(name = "metrics")
public class MetricEntity {
    @Id
    private UUID id;
    
    @Column(name = "metric_name")
    private String metricName;
    
    @Column(name = "metric_value")
    private double value;
    
    @Column(name = "timestamp")
    private Instant timestamp;
    
    @ElementCollection
    private Map<String, String> tags;
    
    // Time window support
    @Column(name = "window_start")
    private Instant windowStart;
    
    @Column(name = "window_end")
    private Instant windowEnd;
}
```

### 3. Integration Points

#### Event Processing Chain
```java
ResourceEvent/LogEvent -> UnifiedMetricsProcessor -> MetricsService -> MeterRegistry/MetricEntity
```

#### Resource-Specific Stats
```java
@Component
public class ResourceStatsProcessor implements IEventProcessor<ResourceEvent> {
    private final CloudResourceUsageStats usageStats;
    
    @Override
    public void process(ResourceEvent event) {
        // Update resource-specific usage stats
        // This preserves the existing CloudResourceUsageStats functionality
        // but integrates it with the unified metrics system
    }
}
```

## Implementation Plan

### Phase 1: Core Infrastructure
1. Create UnifiedMetricsProcessor
2. Implement MetricsService with MeterRegistry support
3. Create MetricEntity and repository

### Phase 2: Event Processing
1. Implement ResourceEvent processing in UnifiedMetricsProcessor
2. Implement LogEvent processing in UnifiedMetricsProcessor
3. Add ResourceStatsProcessor for resource-specific stats

### Phase 3: Cleanup
1. Remove LogMonitoringProcessor
2. Remove ResourceMonitoringProcessor
3. Remove standalone MetricsCollector

## Benefits

1. **Centralized Processing**: All metrics processing happens in one place
2. **Proper Persistence**: All metrics are properly stored
3. **Real-time Monitoring**: Through MeterRegistry
4. **Historical Analysis**: Through persistent storage
5. **Resource-Specific Stats**: Maintained through dedicated processor
6. **Reduced Complexity**: Eliminates duplicate logic

## Example Metrics

1. **Resource Metrics**:
   - Resource counts by type
   - Resource counts by provider
   - Resource state changes
   - Resource access patterns

2. **Event Metrics**:
   - Event counts by type
   - Event processing times
   - Event success/failure rates
   - Event source distribution

3. **Performance Metrics**:
   - Processing latency
   - Event queue size
   - Processor throughput
   - Resource utilization

## Monitoring and Alerting

1. **Dashboards**:
   - Resource discovery metrics
   - Event processing metrics
   - System performance metrics
   - Provider-specific metrics

2. **Alerts**:
   - Abnormal event patterns
   - Resource state changes
   - Performance degradation
   - Processing failures

## Future Enhancements

1. Advanced metric aggregations
2. Custom metric types
3. Metric data export
4. Advanced analytics integration 