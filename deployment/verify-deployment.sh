#!/bin/bash
# verify-deployment.sh - Verify Prebid Server deployment

PROJECT_ID="halo-ads"
LOAD_BALANCER="prebid-lb"
ZONE="us-central1-a"

echo "🔍 Verifying Prebid Server deployment..."
echo ""

# Get load balancer IP
LB_IP=$(gcloud compute forwarding-rules describe ${LOAD_BALANCER}-rule --global --format="value(IPAddress)" 2>/dev/null)

if [ -z "$LB_IP" ]; then
    echo "❌ Load balancer not found. Deployment may not be complete."
    exit 1
fi

echo "📍 Load Balancer IP: $LB_IP"
echo ""

# Check health check
echo "🏥 Checking health check..."
gcloud compute health-checks describe prebid-health-check &>/dev/null
if [ $? -eq 0 ]; then
    echo "✅ Health check exists"
else
    echo "❌ Health check not found"
fi
echo ""

# Check instance group
echo "👥 Checking instance group..."
INSTANCES=$(gcloud compute instance-groups managed list-instances prebid-server-group --zone=$ZONE --format="value(instance)" 2>/dev/null | wc -l)
if [ "$INSTANCES" -gt 0 ]; then
    echo "✅ Instance group has $INSTANCES instance(s)"
    gcloud compute instance-groups managed list-instances prebid-server-group --zone=$ZONE
else
    echo "❌ No instances found"
fi
echo ""

# Test status endpoint
echo "📊 Testing status endpoint..."
STATUS=$(curl -s -o /dev/null -w "%{http_code}" http://$LB_IP/status --max-time 10)
if [ "$STATUS" = "200" ]; then
    echo "✅ Status endpoint responding (HTTP $STATUS)"
    curl -s http://$LB_IP/status | head -5
else
    echo "⚠️  Status endpoint returned HTTP $STATUS (may still be starting)"
fi
echo ""

# Check logs
echo "📝 Recent logs (last 5 entries):"
gcloud logging read "resource.type=gce_instance AND resource.labels.instance_name:prebid-server" --limit=5 --format="table(timestamp,textPayload)" 2>/dev/null || echo "No logs found yet"
echo ""

echo "✅ Verification complete!"
echo ""
echo "🔗 Endpoints:"
echo "   Status: http://$LB_IP/status"
echo "   Auction: http://$LB_IP/openrtb2/auction"


