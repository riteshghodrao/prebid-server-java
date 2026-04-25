# Prebid Server GCP Deployment Guide

## Prerequisites

1. GCP account with billing enabled
2. `gcloud` CLI installed and authenticated
3. Docker installed locally
4. Maven installed (for building)

## Quick Start

1. **Update configuration** in `deploy-to-gcp.sh` if needed:
   - `PROJECT_ID`: Your GCP project ID (default: halo-ads)
   - `REGION`: Preferred region (default: us-central1)
   - `MACHINE_TYPE`: Instance size (default: e2-micro)
   - `INITIAL_INSTANCES`: Number of instances (default: 1)

2. **Run deployment**:
   ```bash
   chmod +x deploy-to-gcp.sh
   ./deploy-to-gcp.sh
   ```

3. **Wait for instance** to start (2-3 minutes)

4. **Test deployment**:
   ```bash
   # Get load balancer IP
   LB_IP=$(gcloud compute forwarding-rules describe prebid-lb-rule --global --format="value(IPAddress)")
   
   # Test status
   curl http://$LB_IP/status
   
   # Test auction endpoint
   curl -X POST http://$LB_IP/openrtb2/auction \
     -H "Content-Type: application/json" \
     -d @production/requests/test-stored-request.json
   ```

## What Gets Created

- **VPC Network**: `prebid-vpc`
- **Firewall Rules**: HTTP (8080), Admin (8060), Health checks
- **Service Account**: `prebid-server@PROJECT_ID.iam.gserviceaccount.com`
- **Cloud Storage Bucket**: `prebid-server-configs`
- **Artifact Registry**: `prebid-repo` (Docker images)
- **Health Check**: `prebid-health-check`
- **Instance Template**: `prebid-server-template` (e2-micro)
- **Managed Instance Group**: `prebid-server-group` (1 instance)
- **Load Balancer**: `prebid-lb` (HTTP)

## Configuration

The deployment uses:
- **Instance Type**: e2-micro (smallest, cost-effective)
- **Initial Instances**: 1 (single instance)
- **Region**: us-central1
- **No Prebid Cache**: Cache is disabled in config

## Updating Deployment

### Update Config Files

```bash
# Upload updated configs
gsutil -m cp -r production/ gs://prebid-server-configs/

# Restart instance to pick up new configs
gcloud compute instance-groups managed rolling-action restart prebid-server-group --zone=us-central1-a
```

### Update Docker Image

```bash
# Rebuild and push
mvn clean package -DskipTests
docker build -t us-central1-docker.pkg.dev/halo-ads/prebid-repo/prebid-server:latest .
docker push us-central1-docker.pkg.dev/halo-ads/prebid-repo/prebid-server:latest

# Update instance template with new image
# Then restart instance group
gcloud compute instance-groups managed rolling-action restart prebid-server-group --zone=us-central1-a
```

### Scale Instances

```bash
# Manual scaling
gcloud compute instance-groups managed resize prebid-server-group \
  --size=2 \
  --zone=us-central1-a

# Enable auto-scaling (optional)
gcloud compute instance-groups managed set-autoscaling prebid-server-group \
  --zone=us-central1-a \
  --max-num-replicas=5 \
  --min-num-replicas=1 \
  --target-cpu-utilization=0.7
```

## Monitoring

- **Logs**: `gcloud logging read "resource.type=gce_instance" --limit=50`
- **Metrics**: https://console.cloud.google.com/monitoring
- **Custom Metrics**: `custom.googleapis.com/prebid/*`

## Troubleshooting

### Check Instance Status
```bash
gcloud compute instance-groups managed list-instances prebid-server-group --zone=us-central1-a
```

### View Instance Logs
```bash
gcloud compute instances get-serial-port-output INSTANCE_NAME --zone=us-central1-a
```

### Check Health Check Status
```bash
gcloud compute health-checks describe prebid-health-check
```

### SSH into Instance
```bash
gcloud compute ssh INSTANCE_NAME --zone=us-central1-a
```

## Cleanup

To remove all resources:
```bash
# Delete load balancer
gcloud compute forwarding-rules delete prebid-lb-rule --global --quiet
gcloud compute target-http-proxies delete prebid-lb-proxy --quiet
gcloud compute url-maps delete prebid-lb-map --quiet
gcloud compute backend-services delete prebid-lb --global --quiet

# Delete instance group
gcloud compute instance-groups managed delete prebid-server-group --zone=us-central1-a --quiet

# Delete instance template
gcloud compute instance-templates delete prebid-server-template --quiet

# Delete health check
gcloud compute health-checks delete prebid-health-check --quiet

# Delete firewall rules
gcloud compute firewall-rules delete allow-http allow-admin allow-health-check --quiet

# Delete VPC (if not used elsewhere)
gcloud compute networks delete prebid-vpc --quiet

# Delete bucket
gsutil rm -r gs://prebid-server-configs

# Delete Artifact Registry
gcloud artifacts repositories delete prebid-repo --location=us-central1 --quiet
```

## Notes

- Config files are stored in Cloud Storage and loaded at startup
- Service account uses Application Default Credentials (no key file needed)
- Health checks run every 10 seconds
- Instance uses e2-micro (0.6-1 vCPU, 1GB RAM) - suitable for testing/light traffic
- Java heap is set to 512MB max, 256MB min for e2-micro instance


