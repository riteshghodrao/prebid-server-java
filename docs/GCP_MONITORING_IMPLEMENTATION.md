# Google Cloud Monitoring Analytics Reporter Implementation

## Overview

A custom analytics reporter has been implemented for Prebid Server that sends analytics data to Google Cloud Monitoring. This allows you to track key metrics like:

- Total incoming requests by stored impression ID
- Requests by country
- Whether ads were served
- Bid prices
- Bid wins (via NotificationEvent)
- Ad renders (via NotificationEvent)

## Implementation Details

### Files Created

1. **GcpMonitoringAnalyticsReporter.java**
   - Main reporter implementation
   - Processes `AuctionEvent` and `NotificationEvent`
   - Extracts metrics and sends to Google Cloud Monitoring

2. **GcpMonitoringAnalyticsProperties.java**
   - Configuration properties model
   - Contains: `projectId`, `enabled`, `metricPrefix`

3. **MetricPoint.java**
   - Internal class representing metric points
   - Used for batching and sending metrics

4. **AnalyticsConfiguration.java** (updated)
   - Added `GcpMonitoringAnalyticsConfiguration` bean
   - Configures the reporter based on YAML config

### Metrics Tracked

The reporter tracks the following custom metrics in Google Cloud Monitoring:

1. **`custom.googleapis.com/prebid/requests`**
   - Counter: Total incoming requests
   - Labels: `stored_imp_id`, `country`, `account_id`

2. **`custom.googleapis.com/prebid/ad_served`**
   - Counter: Number of ads served
   - Labels: `stored_imp_id`, `country`, `account_id`

3. **`custom.googleapis.com/prebid/bid_price`**
   - Distribution: Winning bid prices
   - Labels: `stored_imp_id`, `country`, `account_id`

4. **`custom.googleapis.com/prebid/bid_won`**
   - Counter: Number of bids won (from NotificationEvent)
   - Labels: `bid_id`, `bidder`, `account_id`, `integration`

5. **`custom.googleapis.com/prebid/ad_rendered`**
   - Counter: Number of ads rendered (from NotificationEvent)
   - Labels: `bid_id`, `bidder`, `account_id`, `integration`

### Data Extraction

#### From AuctionEvent

- **Stored Impression IDs**: Extracted from `imp[].ext.prebid.storedimp.id` or `imp[].ext.prebid.storedrequest.id`
- **Country**: Extracted from `GeoInfo` (preferred) or `Device.geo.country`
- **Account ID**: From `AuctionContext.account.id`
- **Ad Served**: Determined by checking if bid response contains bids for the impression
- **Bid Prices**: Winning bid price per impression from bid response

#### From NotificationEvent

- **Event Type**: `win` or `imp`
- **Bid ID**: From event
- **Bidder**: From event
- **Account ID**: From event
- **Integration**: From event (pbjs, amp, etc.)

## Configuration

### Enable in Config

Add to `production/configs/prebid-config.yaml`:

```yaml
analytics:
  gcp-monitoring:
    enabled: true
    project-id: "your-gcp-project-id"
    metric-prefix: "custom.googleapis.com/prebid/"  # Optional
```

### GCP Setup

1. **Service Account**: Ensure the service account running Prebid Server has:
   - `roles/monitoring.metricWriter` - to write custom metrics
   - `roles/logging.logWriter` - to write logs (if using Cloud Logging)

2. **Application Default Credentials**: The reporter uses Application Default Credentials (ADC) for authentication:
   - On GCE/Cloud Run: Automatically available via metadata service
   - Local development: Use `gcloud auth application-default login`

3. **Metric Descriptors**: Custom metrics are automatically created when first sent

## Current Implementation Status

### ✅ Implemented

- Metric extraction from AuctionEvent
- Metric extraction from NotificationEvent
- Metric point creation with labels
- Configuration via YAML
- Integration with AnalyticsReporterDelegator

### ⚠️ Pending (TODO)

The current implementation creates the metric payload but **does not actually send HTTP requests** to Google Cloud Monitoring API yet. This is because:

1. **Authentication**: Requires OAuth2 token from Application Default Credentials
2. **HTTP Client**: Needs to add authentication headers to HttpClient calls
3. **Error Handling**: Needs proper error handling for API calls

### Recommended Next Steps

#### Option 1: Use Google Cloud Monitoring Java Client Library (Recommended)

Add dependency to `pom.xml`:

```xml
<dependency>
    <groupId>com.google.cloud</groupId>
    <artifactId>google-cloud-monitoring</artifactId>
    <version>3.30.0</version>
</dependency>
```

Then update `sendMetricsToCloudMonitoring` method to use the client library.

#### Option 2: Implement HTTP Client with OAuth2

1. Use `GoogleCredentials.getApplicationDefault()` to get credentials
2. Use credentials to create access token
3. Add `Authorization: Bearer <token>` header to HTTP requests
4. Send to Cloud Monitoring REST API

#### Option 3: Use Cloud Monitoring Agent

Deploy the [Cloud Monitoring Agent](https://cloud.google.com/monitoring/agent) on your VMs and send metrics via Prometheus format, then configure Cloud Monitoring to scrape Prometheus endpoints.

## Testing

### Local Testing

1. Set `GOOGLE_APPLICATION_CREDENTIALS` environment variable:
   ```bash
   export GOOGLE_APPLICATION_CREDENTIALS=/path/to/service-account-key.json
   ```

2. Update config with your project ID:
   ```yaml
   analytics:
     gcp-monitoring:
       enabled: true
       project-id: "your-project-id"
   ```

3. Run Prebid Server and check logs for metric payloads (currently logged at DEBUG level)

### Verify Metrics in Cloud Monitoring

1. Go to [Cloud Monitoring Console](https://console.cloud.google.com/monitoring)
2. Navigate to **Metrics Explorer**
3. Select metric type: `custom.googleapis.com/prebid/requests`
4. Group by labels: `stored_imp_id`, `country`

## Example Queries

### MQL (Monitoring Query Language)

```
fetch gce_instance
| metric 'custom.googleapis.com/prebid/requests'
| group_by 1m, [value_requests_sum: sum(value.requests)]
| every 1m
```

### Filter by Stored Impression ID

```
fetch gce_instance
| metric 'custom.googleapis.com/prebid/requests'
| filter resource.instance_id == 'INSTANCE_ID'
| filter metric.stored_imp_id == 'banner_300x250'
| group_by 1m, [value_requests_sum: sum(value.requests)]
| every 1m
```

### Bid Win Rate

```
{
  fetch gce_instance
    | metric 'custom.googleapis.com/prebid/bid_won'
    | group_by 1m, [value_wins_sum: sum(value.bid_won)]
}
/
{
  fetch gce_instance
    | metric 'custom.googleapis.com/prebid/requests'
    | group_by 1m, [value_requests_sum: sum(value.requests)]
}
```

## Performance Considerations

- Metrics are sent asynchronously (non-blocking)
- Batching should be implemented for high-volume deployments
- Consider rate limits: [Cloud Monitoring quotas](https://cloud.google.com/monitoring/quotas)
- For production, implement buffering and retry logic

## Troubleshooting

### Metrics not appearing

1. Check service account permissions
2. Verify project ID is correct
3. Check application logs for errors
4. Ensure metrics are being sent (check DEBUG logs)
5. Verify Application Default Credentials are configured

### Authentication errors

1. Verify service account key is valid
2. Check `GOOGLE_APPLICATION_CREDENTIALS` environment variable
3. On GCE, ensure service account is attached to VM
4. Check service account has required roles

## Future Enhancements

1. **Batching**: Buffer metrics and send in batches
2. **Retry Logic**: Implement exponential backoff for failed requests
3. **Rate Limiting**: Respect Cloud Monitoring rate limits
4. **Metric Caching**: Cache metric descriptors
5. **Alert Policies**: Auto-create alert policies for key metrics
6. **Dashboard Templates**: Create Cloud Monitoring dashboard templates

