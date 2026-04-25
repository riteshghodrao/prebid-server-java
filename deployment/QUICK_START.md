# Quick Start - Deploy to GCP

## Prerequisites Check

```bash
# Verify gcloud is installed and authenticated
gcloud --version
gcloud auth list

# Verify Docker is running
docker --version

# Verify Maven is installed
mvn --version
```

## Deploy

```bash
# From project root
./deploy-to-gcp.sh
```

The script will:
1. ✅ Enable required GCP APIs
2. ✅ Create VPC network and firewall rules
3. ✅ Create service account with permissions
4. ✅ Create Cloud Storage bucket and upload configs
5. ✅ Create Artifact Registry repository
6. ✅ Build and push Docker image
7. ✅ Create health check
8. ✅ Create instance template (e2-micro)
9. ✅ Create managed instance group (1 instance)
10. ✅ Create load balancer

**Time**: ~10-15 minutes (mostly building Docker image)

## Verify

```bash
# Run verification script
./deployment/verify-deployment.sh

# Or manually test
LB_IP=$(gcloud compute forwarding-rules describe prebid-lb-rule --global --format="value(IPAddress)")
curl http://$LB_IP/status
```

## Test Auction Endpoint

```bash
LB_IP=$(gcloud compute forwarding-rules describe prebid-lb-rule --global --format="value(IPAddress)")

curl -X POST http://$LB_IP/openrtb2/auction \
  -H "Content-Type: application/json" \
  -d @production/requests/test-stored-request.json
```

## View Logs

```bash
# Recent logs
gcloud logging read "resource.type=gce_instance" --limit=20

# Filter by instance name
gcloud logging read "resource.type=gce_instance AND resource.labels.instance_name:prebid-server" --limit=20
```

## View Metrics

Open: https://console.cloud.google.com/monitoring

Look for custom metrics under: `custom.googleapis.com/prebid/*`

## Common Issues

### Instance not starting
```bash
# Check instance status
gcloud compute instance-groups managed list-instances prebid-server-group --zone=us-central1-a

# View serial console logs
INSTANCE_NAME=$(gcloud compute instance-groups managed list-instances prebid-server-group --zone=us-central1-a --format="value(instance)" | head -1)
gcloud compute instances get-serial-port-output $INSTANCE_NAME --zone=us-central1-a
```

### Health check failing
```bash
# Check health check details
gcloud compute health-checks describe prebid-health-check

# Test endpoint directly from instance
gcloud compute ssh $INSTANCE_NAME --zone=us-central1-a --command="curl http://localhost:8080/status"
```

### Config not loading
```bash
# Verify configs are in bucket
gsutil ls -r gs://prebid-server-configs/production/

# Re-upload configs
gsutil -m cp -r production/ gs://prebid-server-configs/

# Restart instance
gcloud compute instance-groups managed rolling-action restart prebid-server-group --zone=us-central1-a
```


