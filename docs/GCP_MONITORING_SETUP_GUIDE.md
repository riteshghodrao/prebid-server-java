# Google Cloud Monitoring Setup Guide

## What Was Implemented

✅ **Google Cloud Monitoring Client Library Integration**
- Added `google-cloud-monitoring` dependency (v3.30.0) to `pom.xml`
- Implemented `MetricServiceClient` for sending metrics
- Created async, non-blocking implementation using Vert.x worker threads
- Automatic client initialization with error handling

✅ **Complete Metric Tracking**
- **Request Metrics**: Total requests by stored impression ID, country, account
- **Ad Served Metrics**: Track when ads are served per impression
- **Bid Price Metrics**: Winning bid prices with labels
- **Win Events**: Bid wins tracked via NotificationEvent
- **Render Events**: Ad renders tracked via NotificationEvent

## What You Need to Configure

### 1. GCP Project Setup

**Enable the Monitoring API:**
```bash
gcloud services enable monitoring.googleapis.com --project=YOUR_PROJECT_ID
```

**Create or Use a Service Account:**
```bash
# If using existing service account
gcloud iam service-accounts list

# Create new service account (if needed)
gcloud iam service-accounts create prebid-monitoring \
    --display-name="Prebid Server Monitoring" \
    --project=YOUR_PROJECT_ID
```

**Grant Required Permissions:**
```bash
# Grant monitoring metric writer role
gcloud projects add-iam-policy-binding YOUR_PROJECT_ID \
    --member="serviceAccount:prebid-monitoring@YOUR_PROJECT_ID.iam.gserviceaccount.com" \
    --role="roles/monitoring.metricWriter"

# If using existing service account (prebid-server from deployment guide)
gcloud projects add-iam-policy-binding YOUR_PROJECT_ID \
    --member="serviceAccount:prebid-server@YOUR_PROJECT_ID.iam.gserviceaccount.com" \
    --role="roles/monitoring.metricWriter"
```

### 2. Authentication Setup

#### Option A: On Google Cloud (Recommended for Production)

**For Compute Engine VMs:**
The service account is automatically available via metadata service. No additional setup needed if:
- VM was created with the service account attached
- Service account has `roles/monitoring.metricWriter`

**Verify service account on VM:**
```bash
# SSH into VM
gcloud compute ssh prebid-server-XXXX --zone=us-central1-a

# Check metadata
curl "http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/email" \
    -H "Metadata-Flavor: Google"
```

#### Option B: Local Development

**Set Application Default Credentials:**
```bash
# Authenticate for local development
gcloud auth application-default login

# Or set service account key file
export GOOGLE_APPLICATION_CREDENTIALS=/path/to/service-account-key.json
```

**Create Service Account Key (for local dev only):**
```bash
# Create key file (download to local machine)
gcloud iam service-accounts keys create ~/prebid-monitoring-key.json \
    --iam-account=prebid-monitoring@YOUR_PROJECT_ID.iam.gserviceaccount.com

# Set environment variable
export GOOGLE_APPLICATION_CREDENTIALS=~/prebid-monitoring-key.json
```

⚠️ **Security Note**: Never commit service account keys to git. Use Secret Manager in production.

### 3. Update Configuration

**Update `production/configs/prebid-config.yaml`:**
```yaml
analytics:
  gcp-monitoring:
    enabled: true
    project-id: "your-gcp-project-id"  # Replace with your actual project ID
    metric-prefix: "custom.googleapis.com/prebid/"  # Optional: defaults to this
```

### 4. Build and Deploy

**Build the project:**
```bash
mvn clean package -DskipTests
```

The Google Cloud Monitoring client library is now included in the build.

**Verify dependency:**
```bash
mvn dependency:tree | grep google-cloud-monitoring
```

You should see:
```
[INFO] +- com.google.cloud:google-cloud-monitoring:jar:3.30.0:compile
```

## Testing the Implementation

### 1. Local Testing

**Start Prebid Server with GCP Monitoring enabled:**
```bash
java -jar target/prebid-server.jar \
  --spring.config.additional-location=production/configs/prebid-config.yaml \
  -DGOOGLE_APPLICATION_CREDENTIALS=~/prebid-monitoring-key.json
```

**Send a test request:**
```bash
# Use your stored request
curl -X POST http://localhost:8080/openrtb2/auction \
  -H "Content-Type: application/json" \
  -d '{
    "ext": {
      "prebid": {
        "storedrequest": {
          "id": "main-banner-request"
        }
      }
    }
  }'
```

**Check logs for:**
```
GCP Monitoring MetricServiceClient initialized successfully
```

**Verify metrics in Cloud Monitoring:**
1. Go to [Cloud Monitoring Console](https://console.cloud.google.com/monitoring)
2. Navigate to **Metrics Explorer**
3. Select metric type: `custom.googleapis.com/prebid/requests`
4. You should see data points appearing

### 2. On GCP (Production)

**Deploy with service account attached:**
- The service account should be attached to the VM when creating the instance template
- Or set in the managed instance group configuration

**Verify metrics are being sent:**
```bash
# Check application logs
gcloud logging read "resource.type=gce_instance AND textPayload:Monitoring" \
    --limit=50 \
    --project=YOUR_PROJECT_ID
```

## Troubleshooting

### Issue: "Failed to initialize GCP Monitoring MetricServiceClient"

**Possible causes:**
1. **Missing credentials**: Application Default Credentials not configured
   - **Solution**: Set `GOOGLE_APPLICATION_CREDENTIALS` or ensure service account is attached to VM

2. **Missing permissions**: Service account doesn't have `monitoring.metricWriter` role
   - **Solution**: Grant the role as shown in step 1

3. **Wrong project ID**: Project ID in config doesn't match credentials
   - **Solution**: Verify project ID in config matches the project where credentials are from

### Issue: Metrics not appearing in Cloud Monitoring

**Check:**
1. Metrics are being created (check logs for "MetricServiceClient initialized")
2. Metrics are being sent (check logs for errors during send)
3. Wait a few minutes - metrics can take 1-3 minutes to appear
4. Verify project ID is correct in config

### Issue: "Permission denied" errors

**Solution:**
```bash
# Verify service account permissions
gcloud projects get-iam-policy YOUR_PROJECT_ID \
    --flatten="bindings[].members" \
    --filter="bindings.members:serviceAccount:prebid-*@YOUR_PROJECT_ID.iam.gserviceaccount.com"
```

## Monitoring Metrics

Once configured, you can monitor:

### In Cloud Monitoring Console

1. **Metrics Explorer**:
   - `custom.googleapis.com/prebid/requests` - Request count
   - `custom.googleapis.com/prebid/ad_served` - Ads served
   - `custom.googleapis.com/prebid/bid_price` - Bid prices
   - `custom.googleapis.com/prebid/bid_won` - Bid wins
   - `custom.googleapis.com/prebid/ad_rendered` - Ad renders

2. **Filter by Labels**:
   - `stored_imp_id` - Filter by impression ID
   - `country` - Filter by country
   - `account_id` - Filter by account

3. **Create Dashboards**:
   - Group metrics by stored impression ID
   - Track win rates by country
   - Monitor bid prices over time

### Example MQL Query

```sql
fetch gce_instance
| metric 'custom.googleapis.com/prebid/requests'
| group_by 1m, [value_requests_sum: sum(value.requests)]
| filter metric.stored_imp_id == 'banner_300x250'
| every 1m
```

## Next Steps

1. ✅ **Set up authentication** (service account with permissions)
2. ✅ **Update config** with your project ID
3. ✅ **Build and deploy** Prebid Server
4. ✅ **Verify metrics** are appearing in Cloud Monitoring
5. **Create dashboards** for key metrics
6. **Set up alerting** for error rates, low win rates, etc.

## Additional Resources

- [Google Cloud Monitoring Documentation](https://cloud.google.com/monitoring/docs)
- [Monitoring API Client Libraries](https://cloud.google.com/monitoring/docs/reference/libraries#client-libraries)
- [Application Default Credentials](https://cloud.google.com/docs/authentication/application-default-credentials)

## Support

If you encounter issues:
1. Check the troubleshooting section above
2. Review application logs for error messages
3. Verify service account permissions
4. Ensure Monitoring API is enabled
5. Check that project ID matches credentials project

