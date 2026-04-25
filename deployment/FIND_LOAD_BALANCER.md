# How to Find Your Load Balancer IP and Endpoint

## Quick Commands

### Get Load Balancer IP
```bash
gcloud compute forwarding-rules describe prebid-lb-rule --global --format="value(IPAddress)"
```

### Get All Load Balancer Info
```bash
./deployment/get-load-balancer-info.sh
```

### Check if Load Balancer Exists
```bash
gcloud compute forwarding-rules list --global --filter="name~prebid"
```

## Where to Find in GCP Console

### Option 1: Network Services → Load Balancing
1. Go to: https://console.cloud.google.com/net-services/loadbalancing
2. Look for: `prebid-lb` or `prebid-lb-map`
3. Click on it to see:
   - **IP Address** (Frontend IP)
   - **Backend services**
   - **Health check status**

### Option 2: Compute Engine → Load Balancing
1. Go to: https://console.cloud.google.com/compute/loadbalancing
2. You'll see all load balancers listed
3. Click on your load balancer to see details

### Option 3: VPC Network → Load Balancing
1. Go to: https://console.cloud.google.com/networking/loadbalancing
2. Find your load balancer in the list

## Load Balancer Components

Your load balancer consists of:

1. **Forwarding Rule** (`prebid-lb-rule`)
   - **This is where the IP address is!**
   - Location: Global
   - Port: 80 (HTTP)

2. **HTTP Proxy** (`prebid-lb-proxy`)
   - Connects forwarding rule to URL map

3. **URL Map** (`prebid-lb-map`)
   - Routing rules (default: all traffic to backend)

4. **Backend Service** (`prebid-lb`)
   - Points to your instance group
   - Uses health check

5. **Health Check** (`prebid-health-check`)
   - Monitors `/status` endpoint

## If Load Balancer Doesn't Exist

If the load balancer wasn't created, check:

```bash
# Check if deployment completed
gcloud compute instance-groups managed list --zones=us-central1-a

# Check for errors in deployment
# Re-run the deployment script
./deploy-to-gcp.sh
```

## Manual Creation (if needed)

If the load balancer components exist but forwarding rule is missing:

```bash
# Create forwarding rule manually
gcloud compute forwarding-rules create prebid-lb-rule \
    --global \
    --target-http-proxy=prebid-lb-proxy \
    --ports=80
```

## DNS Setup (Optional)

Once you have the IP address:

1. **Get the IP:**
   ```bash
   LB_IP=$(gcloud compute forwarding-rules describe prebid-lb-rule --global --format="value(IPAddress)")
   echo $LB_IP
   ```

2. **Add DNS A Record:**
   - Go to your DNS provider (Cloud DNS, Route 53, etc.)
   - Add A record:
     - **Name**: `prebid` (or your subdomain)
     - **Type**: A
     - **Value**: `<LB_IP>`
     - **TTL**: 300

3. **Your endpoint becomes:**
   - `http://prebid.yourdomain.com/openrtb2/auction`

## Current Setup (HTTP Only)

**Note**: This deployment creates an **HTTP load balancer** (port 80). 

For production with HTTPS:
- You'll need to create an HTTPS load balancer
- Add SSL certificates
- Update the forwarding rule to use port 443

## Test Your Endpoint

Once you have the IP:

```bash
# Get IP
LB_IP=$(gcloud compute forwarding-rules describe prebid-lb-rule --global --format="value(IPAddress)")

# Test status
curl http://$LB_IP/status

# Test auction
curl -X POST http://$LB_IP/openrtb2/auction \
  -H "Content-Type: application/json" \
  -d @production/requests/test-stored-request.json
```


