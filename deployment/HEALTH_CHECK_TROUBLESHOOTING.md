# Health Check Troubleshooting Guide

## Health Check Configuration

The health check is configured as follows:
- **URL**: `http://<instance-ip>:8080/status`
- **Path**: `/status`
- **Port**: `8080`
- **Timeout**: `5 seconds`
- **Check Interval**: `10 seconds`
- **Healthy Threshold**: `2 consecutive successful checks`
- **Unhealthy Threshold**: `3 consecutive failed checks`

## Common Issues and Solutions

### 1. Container Not Starting

**Symptom**: Health check times out, container not responding

**Check**:
```bash
# Get instance name
INSTANCE_NAME=$(gcloud compute instances list --filter="name~prebid-server" --format="value(name)" | head -1)

# Check container logs
gcloud compute ssh $INSTANCE_NAME --zone=us-central1-a \
  --command="sudo journalctl -u konlet-startup -n 50 --no-pager"
```

**Common Causes**:
- **Missing Artifact Registry permissions**: Service account can't pull Docker image
  - **Fix**: Grant `roles/artifactregistry.reader` to service account
  - **Command**: 
    ```bash
    gcloud projects add-iam-policy-binding halo-ads \
      --member="serviceAccount:prebid-server@halo-ads.iam.gserviceaccount.com" \
      --role="roles/artifactregistry.reader"
    ```
- **Config file not accessible**: Can't read config from Cloud Storage
  - **Fix**: Ensure config file exists and service account has `roles/storage.objectViewer`
- **Container crashes on startup**: Check application logs

### 2. Port Not Accessible

**Symptom**: Health check can't connect to port 8080

**Check**:
```bash
# Test from instance itself
gcloud compute ssh $INSTANCE_NAME --zone=us-central1-a \
  --command="curl -v http://localhost:8080/status"
```

**Common Causes**:
- **Firewall blocking**: Health check IPs need access
  - **Fix**: GCP health checks use specific IP ranges (automatically allowed)
- **Application not listening**: Prebid Server not started
  - **Fix**: Check container logs and application startup

### 3. Wrong Status Endpoint

**Symptom**: Health check gets 404 or wrong response

**Check**:
```bash
# Test status endpoint
curl http://<instance-ip>:8080/status
```

**Expected Response**:
- **Status Code**: `200 OK`
- **Content**: JSON with health status or `204 No Content` if no health checkers configured

**Common Causes**:
- **Wrong path**: Should be `/status` not `/health` or `/ping`
- **Application not configured**: Check `status-response` in config

### 4. Application Not Starting

**Symptom**: Container starts but application doesn't respond

**Check**:
```bash
# Check Docker container status
gcloud compute ssh $INSTANCE_NAME --zone=us-central1-a \
  --command="sudo docker ps -a"

# Check container logs
gcloud compute ssh $INSTANCE_NAME --zone=us-central1-a \
  --command="sudo docker logs <container-id>"
```

**Common Causes**:
- **Config file error**: Invalid YAML or missing required fields
- **Missing dependencies**: Can't access Cloud Storage or other services
- **Memory issues**: e2-micro might be too small (512MB RAM)
- **Java startup errors**: Check JAVA_OPTS and application logs

### 5. Health Check IP Ranges

GCP health checks come from specific IP ranges. These are automatically allowed, but if you have custom firewall rules, ensure these ranges are allowed:

- **130.211.0.0/22**
- **35.191.0.0/16**

## Quick Diagnostic Commands

```bash
# 1. Check health check status
gcloud compute health-checks describe prebid-health-check

# 2. Check instance status
gcloud compute instance-groups managed list-instances prebid-server-group \
  --zone=us-central1-a

# 3. Check backend service health
gcloud compute backend-services get-health prebid-lb --global

# 4. Test status endpoint from instance
INSTANCE_NAME=$(gcloud compute instances list --filter="name~prebid-server" --format="value(name)" | head -1)
gcloud compute ssh $INSTANCE_NAME --zone=us-central1-a \
  --command="curl -v http://localhost:8080/status"

# 5. Check container logs
gcloud compute ssh $INSTANCE_NAME --zone=us-central1-a \
  --command="sudo journalctl -u konlet-startup -n 100 --no-pager"

# 6. Check if config file exists
gsutil ls gs://prebid-server-configs/production/configs/prebid-config.yaml
```

## Fixing the Current Issue

Based on the error logs, the issue is:

**Error**: `Permission 'artifactregistry.repositories.downloadArtifacts' denied`

**Solution**:
```bash
# Grant Artifact Registry reader permission
gcloud projects add-iam-policy-binding halo-ads \
  --member="serviceAccount:prebid-server@halo-ads.iam.gserviceaccount.com" \
  --role="roles/artifactregistry.reader"

# Restart the instance group to pull new image
gcloud compute instance-groups managed rolling-action restart \
  prebid-server-group \
  --zone=us-central1-a
```

After fixing permissions, the container should start and the health check should pass.


