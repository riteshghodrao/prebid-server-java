#!/bin/bash
# get-load-balancer-info.sh - Get load balancer IP and endpoint information

PROJECT_ID="halo-ads"
LOAD_BALANCER="prebid-lb"

echo "🔍 Finding Load Balancer Information..."
echo ""

# Get forwarding rule (this has the IP)
echo "📍 Forwarding Rule (Load Balancer IP):"
FORWARDING_RULE=$(gcloud compute forwarding-rules list --global --filter="name~prebid-lb" --format="value(name)" 2>/dev/null | head -1)

if [ -z "$FORWARDING_RULE" ]; then
    echo "❌ No forwarding rule found. Load balancer may not be fully created yet."
    echo ""
    echo "Checking all forwarding rules:"
    gcloud compute forwarding-rules list --global
else
    echo "   Name: $FORWARDING_RULE"
    LB_IP=$(gcloud compute forwarding-rules describe $FORWARDING_RULE --global --format="value(IPAddress)" 2>/dev/null)
    
    if [ -z "$LB_IP" ]; then
        echo "   ⚠️  IP not assigned yet (may still be provisioning)"
    else
        echo "   🌐 IP Address: $LB_IP"
        echo ""
        echo "═══════════════════════════════════════════════════════════"
        echo "✅ Your Prebid Server Endpoints:"
        echo "═══════════════════════════════════════════════════════════"
        echo ""
        echo "📍 Status Endpoint:"
        echo "   http://$LB_IP/status"
        echo ""
        echo "📍 Auction Endpoint:"
        echo "   http://$LB_IP/openrtb2/auction"
        echo ""
        echo "📍 Admin Endpoint:"
        echo "   http://$LB_IP:8060/logging/changelevel"
        echo ""
        echo "═══════════════════════════════════════════════════════════"
    fi
fi
echo ""

# Get URL map
echo "🗺️  URL Map:"
URL_MAP=$(gcloud compute url-maps list --filter="name~prebid-lb" --format="value(name)" 2>/dev/null | head -1)
if [ -z "$URL_MAP" ]; then
    echo "   ❌ Not found"
else
    echo "   ✅ $URL_MAP"
    gcloud compute url-maps describe $URL_MAP --format="yaml(defaultService)" 2>/dev/null | head -3
fi
echo ""

# Get backend service
echo "⚙️  Backend Service:"
BACKEND_SERVICE=$(gcloud compute backend-services list --global --filter="name~prebid-lb" --format="value(name)" 2>/dev/null | head -1)
if [ -z "$BACKEND_SERVICE" ]; then
    echo "   ❌ Not found"
else
    echo "   ✅ $BACKEND_SERVICE"
    echo "   Health Check:"
    gcloud compute backend-services describe $BACKEND_SERVICE --global --format="value(healthChecks[0])" 2>/dev/null
    echo "   Backends:"
    gcloud compute backend-services describe $BACKEND_SERVICE --global --format="value(backends[0].group)" 2>/dev/null
fi
echo ""

# Get HTTP proxy
echo "🔀 HTTP Proxy:"
HTTP_PROXY=$(gcloud compute target-http-proxies list --filter="name~prebid-lb" --format="value(name)" 2>/dev/null | head -1)
if [ -z "$HTTP_PROXY" ]; then
    echo "   ❌ Not found"
else
    echo "   ✅ $HTTP_PROXY"
fi
echo ""

# Check instance group status
echo "👥 Instance Group Status:"
INSTANCE_GROUP=$(gcloud compute instance-groups managed list --zones=us-central1-a --filter="name~prebid-server" --format="value(name)" 2>/dev/null | head -1)
if [ -z "$INSTANCE_GROUP" ]; then
    echo "   ❌ Not found"
else
    echo "   ✅ $INSTANCE_GROUP"
    echo "   Instances:"
    gcloud compute instance-groups managed list-instances $INSTANCE_GROUP --zone=us-central1-a --format="table(instance,status)" 2>/dev/null
fi
echo ""

# DNS Setup Information
if [ ! -z "$LB_IP" ]; then
    echo "═══════════════════════════════════════════════════════════"
    echo "🌐 DNS Setup (Optional):"
    echo "═══════════════════════════════════════════════════════════"
    echo ""
    echo "To set up a custom domain, add an A record in your DNS:"
    echo ""
    echo "   Type: A"
    echo "   Name: prebid (or your subdomain)"
    echo "   Value: $LB_IP"
    echo "   TTL: 300 (or your preference)"
    echo ""
    echo "Then your endpoint would be:"
    echo "   http://prebid.yourdomain.com/openrtb2/auction"
    echo ""
    echo "Note: For HTTPS, you'll need to set up an HTTPS load balancer"
    echo "      with SSL certificates (not included in this deployment)."
    echo ""
fi


