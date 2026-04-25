# Quick Setup Checklist for Local GCP Monitoring

## ✅ Step-by-Step Setup (5 minutes)

### 1. Create Service Account (if not exists)

```bash
export PROJECT_ID="halo-ads"

# Create service account
gcloud iam service-accounts create prebid-monitoring \
    --display-name="Prebid Server Monitoring" \
    --project=$PROJECT_ID
```

### 2. Grant Permissions

```bash
# Grant monitoring metric writer role
gcloud projects add-iam-policy-binding $PROJECT_ID \
    --member="serviceAccount:prebid-monitoring@${PROJECT_ID}.iam.gserviceaccount.com" \
    --role="roles/monitoring.metricWriter"
```

### 3. Create and Download Key File

```bash
# Create key file
gcloud iam service-accounts keys create ~/prebid-monitoring-key.json \
    --iam-account=prebid-monitoring@${PROJECT_ID}.iam.gserviceaccount.com

# Secure it
chmod 600 ~/prebid-monitoring-key.json
```

### 4. Set Environment Variable

**macOS/Linux:**
```bash
# Add to ~/.zshrc or ~/.bashrc
export GOOGLE_APPLICATION_CREDENTIALS="$HOME/prebid-monitoring-key.json"

# Reload
source ~/.zshrc
```

**Or set it when running:**
```bash
GOOGLE_APPLICATION_CREDENTIALS=~/prebid-monitoring-key.json \
  java -jar target/prebid-server.jar \
  --spring.config.additional-location=production/configs/prebid-config.yaml
```

### 5. Enable Monitoring API

```bash
gcloud services enable monitoring.googleapis.com --project=$PROJECT_ID
```

### 6. Verify Config

Your `production/configs/prebid-config.yaml` already has:
```yaml
analytics:
  gcp-monitoring:
    enabled: true
    project-id: "halo-ads"  # ✅ Already set!
```

### 7. Test It

```bash
# Build
mvn clean package -DskipTests

# Run (with credentials)
GOOGLE_APPLICATION_CREDENTIALS=~/prebid-monitoring-key.json \
  java -jar target/prebid-server.jar \
  --spring.config.additional-location=production/configs/prebid-config.yaml

# Look for this in logs:
# "GCP Monitoring MetricServiceClient initialized successfully" ✅
```

## 🔍 Quick Verification

```bash
# 1. Check key file exists
ls -la ~/prebid-monitoring-key.json

# 2. Check environment variable
echo $GOOGLE_APPLICATION_CREDENTIALS

# 3. Test authentication
gcloud auth activate-service-account --key-file=~/prebid-monitoring-key.json

# 4. Verify permissions
gcloud projects get-iam-policy $PROJECT_ID \
    --flatten="bindings[].members" \
    --filter="bindings.members:serviceAccount:prebid-monitoring@${PROJECT_ID}.iam.gserviceaccount.com"
```

## ⚠️ Common Issues

**"Failed to initialize" error:**
- ✅ Check `GOOGLE_APPLICATION_CREDENTIALS` is set
- ✅ Verify key file path is correct
- ✅ Ensure project ID in config matches key file project

**"Permission denied":**
- ✅ Re-run the grant permissions command (step 2)

**Metrics not appearing:**
- ✅ Wait 1-3 minutes
- ✅ Check logs for initialization message
- ✅ Verify API is enabled

## 📝 What You Need

1. ✅ GCP project: `halo-ads` (already have)
2. ✅ Service account: `prebid-monitoring@halo-ads.iam.gserviceaccount.com` (create)
3. ✅ Key file: `~/prebid-monitoring-key.json` (create)
4. ✅ Environment variable: `GOOGLE_APPLICATION_CREDENTIALS` (set)
5. ✅ Monitoring API: Enabled (enable)

That's it! Once these are done, Prebid Server will automatically send metrics to Cloud Monitoring.

