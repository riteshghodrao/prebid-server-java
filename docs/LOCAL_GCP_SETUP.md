# Local GCP Monitoring Setup Guide

This guide walks you through setting up a service account and configuring it for local development.

## Step 1: Create Service Account in GCP

### 1.1 Create the Service Account

```bash
# Set your project ID
export PROJECT_ID="your-gcp-project-id"

# Create service account
gcloud iam service-accounts create prebid-monitoring \
    --display-name="Prebid Server Monitoring" \
    --description="Service account for Prebid Server analytics and monitoring" \
    --project=$PROJECT_ID
```

**Output:**
```
Created service account [prebid-monitoring].
```

### 1.2 Grant Required Permissions

```bash
# Grant monitoring metric writer role (required for sending metrics)
gcloud projects add-iam-policy-binding $PROJECT_ID \
    --member="serviceAccount:prebid-monitoring@${PROJECT_ID}.iam.gserviceaccount.com" \
    --role="roles/monitoring.metricWriter"

# Optional: Grant logging writer role (if you want to send logs too)
gcloud projects add-iam-policy-binding $PROJECT_ID \
    --member="serviceAccount:prebid-monitoring@${PROJECT_ID}.iam.gserviceaccount.com" \
    --role="roles/logging.logWriter"
```

**Expected output:**
```
Updated IAM policy for project [your-project-id].
bindings:
- members:
  - serviceAccount:prebid-monitoring@your-project-id.iam.gserviceaccount.com
  role: roles/monitoring.metricWriter
```

### 1.3 Verify Service Account

```bash
# List service accounts to verify
gcloud iam service-accounts list --project=$PROJECT_ID

# Check permissions
gcloud projects get-iam-policy $PROJECT_ID \
    --flatten="bindings[].members" \
    --filter="bindings.members:serviceAccount:prebid-monitoring@${PROJECT_ID}.iam.gserviceaccount.com" \
    --format="table(bindings.role)"
```

## Step 2: Create and Download Service Account Key

### 2.1 Create Key File

```bash
# Create key file (this downloads a JSON key)
gcloud iam service-accounts keys create ~/prebid-monitoring-key.json \
    --iam-account=prebid-monitoring@${PROJECT_ID}.iam.gserviceaccount.com \
    --project=$PROJECT_ID
```

**Output:**
```
created key [1234567890abcdef] of type [json] as [~/prebid-monitoring-key.json] for [prebid-monitoring@your-project-id.iam.gserviceaccount.com]
```

### 2.2 Verify Key File

```bash
# Check the key file exists
ls -la ~/prebid-monitoring-key.json

# View the key structure (don't share this!)
cat ~/prebid-monitoring-key.json | jq '.type, .project_id, .client_email'
```

**Expected structure:**
```json
{
  "type": "service_account",
  "project_id": "your-project-id",
  "private_key_id": "...",
  "private_key": "-----BEGIN PRIVATE KEY-----\n...",
  "client_email": "prebid-monitoring@your-project-id.iam.gserviceaccount.com",
  "client_id": "...",
  "auth_uri": "https://accounts.google.com/o/oauth2/auth",
  "token_uri": "https://oauth2.googleapis.com/token",
  ...
}
```

### 2.3 Secure the Key File

```bash
# Set restrictive permissions (only you can read)
chmod 600 ~/prebid-monitoring-key.json

# Verify permissions
ls -la ~/prebid-monitoring-key.json
# Should show: -rw------- (600)
```

## Step 3: Enable Required APIs

```bash
# Enable Monitoring API
gcloud services enable monitoring.googleapis.com --project=$PROJECT_ID

# Verify it's enabled
gcloud services list --enabled --project=$PROJECT_ID | grep monitoring
```

**Expected output:**
```
monitoring.googleapis.com    Cloud Monitoring API
```

## Step 4: Configure Local Environment

### 4.1 Set Environment Variable

**For macOS/Linux:**
```bash
# Add to your shell profile (~/.zshrc or ~/.bashrc)
export GOOGLE_APPLICATION_CREDENTIALS="$HOME/prebid-monitoring-key.json"

# Reload shell configuration
source ~/.zshrc  # or source ~/.bashrc

# Verify it's set
echo $GOOGLE_APPLICATION_CREDENTIALS
```

**For Windows (PowerShell):**
```powershell
# Set environment variable for current session
$env:GOOGLE_APPLICATION_CREDENTIALS = "$env:USERPROFILE\prebid-monitoring-key.json"

# Or set permanently
[System.Environment]::SetEnvironmentVariable('GOOGLE_APPLICATION_CREDENTIALS', "$env:USERPROFILE\prebid-monitoring-key.json", 'User')
```

**For Windows (CMD):**
```cmd
setx GOOGLE_APPLICATION_CREDENTIALS "%USERPROFILE%\prebid-monitoring-key.json"
```

### 4.2 Alternative: Set in IDE/Application

If you don't want to set it globally, you can set it when running Prebid Server:

```bash
GOOGLE_APPLICATION_CREDENTIALS=~/prebid-monitoring-key.json \
  java -jar target/prebid-server.jar \
  --spring.config.additional-location=production/configs/prebid-config.yaml
```

Or in your IDE (IntelliJ IDEA):
1. Run → Edit Configurations
2. Select your Prebid Server run configuration
3. Environment variables → Add
4. Name: `GOOGLE_APPLICATION_CREDENTIALS`
5. Value: `/Users/your-username/prebid-monitoring-key.json`

## Step 5: Update Prebid Server Configuration

### 5.1 Update Config File

Edit `production/configs/prebid-config.yaml`:

```yaml
analytics:
  gcp-monitoring:
    enabled: true
    project-id: "your-gcp-project-id"  # Replace with your actual project ID
    metric-prefix: "custom.googleapis.com/prebid/"  # Optional
```

### 5.2 Verify Project ID

Make sure the project ID in the config matches the project where you created the service account:

```bash
# Get your current project
gcloud config get-value project

# Or list all projects
gcloud projects list
```

## Step 6: Test the Setup

### 6.1 Verify Authentication

```bash
# Test that credentials are working
gcloud auth activate-service-account \
    --key-file=~/prebid-monitoring-key.json

# Test API access
gcloud monitoring time-series list \
    --project=$PROJECT_ID \
    --limit=1
```

### 6.2 Test with Prebid Server

**Build the project:**
```bash
mvn clean package -DskipTests
```

**Run Prebid Server:**
```bash
# Make sure GOOGLE_APPLICATION_CREDENTIALS is set
echo $GOOGLE_APPLICATION_CREDENTIALS

# Start Prebid Server
java -jar target/prebid-server.jar \
  --spring.config.additional-location=production/configs/prebid-config.yaml
```

**Check logs for:**
```
GCP Monitoring MetricServiceClient initialized successfully
```

If you see this, authentication is working! ✅

**Send a test request:**
```bash
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

### 6.3 Verify Metrics in Cloud Monitoring

1. Go to [Cloud Monitoring Console](https://console.cloud.google.com/monitoring)
2. Select your project
3. Navigate to **Metrics Explorer**
4. Select metric type: `custom.googleapis.com/prebid/requests`
5. You should see data points appearing within 1-3 minutes

## Troubleshooting

### Issue: "Failed to initialize GCP Monitoring MetricServiceClient"

**Check 1: Environment variable is set**
```bash
echo $GOOGLE_APPLICATION_CREDENTIALS
# Should show: /Users/your-username/prebid-monitoring-key.json
```

**Check 2: Key file exists and is readable**
```bash
ls -la ~/prebid-monitoring-key.json
cat ~/prebid-monitoring-key.json | jq '.project_id'
```

**Check 3: Project ID matches**
```bash
# Get project ID from key file
cat ~/prebid-monitoring-key.json | jq -r '.project_id'

# Compare with config file
grep project-id production/configs/prebid-config.yaml
```

**Check 4: Permissions are correct**
```bash
# Verify service account has the role
gcloud projects get-iam-policy $PROJECT_ID \
    --flatten="bindings[].members" \
    --filter="bindings.members:serviceAccount:prebid-monitoring@${PROJECT_ID}.iam.gserviceaccount.com"
```

### Issue: "Permission denied" or "403 Forbidden"

**Solution:**
```bash
# Re-grant the role
gcloud projects add-iam-policy-binding $PROJECT_ID \
    --member="serviceAccount:prebid-monitoring@${PROJECT_ID}.iam.gserviceaccount.com" \
    --role="roles/monitoring.metricWriter"
```

### Issue: "API not enabled"

**Solution:**
```bash
# Enable the API
gcloud services enable monitoring.googleapis.com --project=$PROJECT_ID

# Wait a minute, then verify
gcloud services list --enabled --project=$PROJECT_ID | grep monitoring
```

### Issue: Metrics not appearing

**Check:**
1. Wait 1-3 minutes (metrics can take time to appear)
2. Check application logs for errors
3. Verify metrics are being sent (check logs for "MetricServiceClient initialized")
4. Try a different metric type in Metrics Explorer

## Quick Reference Commands

```bash
# Set project
export PROJECT_ID="your-project-id"

# Create service account
gcloud iam service-accounts create prebid-monitoring \
    --display-name="Prebid Server Monitoring" \
    --project=$PROJECT_ID

# Grant permissions
gcloud projects add-iam-policy-binding $PROJECT_ID \
    --member="serviceAccount:prebid-monitoring@${PROJECT_ID}.iam.gserviceaccount.com" \
    --role="roles/monitoring.metricWriter"

# Create key
gcloud iam service-accounts keys create ~/prebid-monitoring-key.json \
    --iam-account=prebid-monitoring@${PROJECT_ID}.iam.gserviceaccount.com

# Set environment variable
export GOOGLE_APPLICATION_CREDENTIALS="$HOME/prebid-monitoring-key.json"

# Enable API
gcloud services enable monitoring.googleapis.com --project=$PROJECT_ID

# Test authentication
gcloud auth activate-service-account --key-file=~/prebid-monitoring-key.json
```

## Security Best Practices

1. **Never commit the key file to git**
   ```bash
   # Add to .gitignore
   echo "prebid-monitoring-key.json" >> .gitignore
   ```

2. **Use restrictive file permissions**
   ```bash
   chmod 600 ~/prebid-monitoring-key.json
   ```

3. **Rotate keys periodically**
   ```bash
   # List existing keys
   gcloud iam service-accounts keys list \
       --iam-account=prebid-monitoring@${PROJECT_ID}.iam.gserviceaccount.com
   
   # Delete old keys
   gcloud iam service-accounts keys delete KEY_ID \
       --iam-account=prebid-monitoring@${PROJECT_ID}.iam.gserviceaccount.com
   ```

4. **Use different keys for different environments**
   - One key for local development
   - Different key for staging
   - Different key for production (stored in Secret Manager)

## Next Steps

Once local setup is working:
1. ✅ Test sending metrics
2. ✅ Verify metrics appear in Cloud Monitoring
3. ✅ Create dashboards for key metrics
4. ✅ Set up alerting policies
5. ✅ Configure production deployment (use service account attached to VM, not key files)

