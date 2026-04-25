#!/bin/bash
# deploy-to-gcp.sh - Build, push, and deploy Prebid Server to GCP
#
# Usage:
#   ./deploy-to-gcp.sh              # Full build + deploy
#   SKIP_BUILD=1 ./deploy-to-gcp.sh # Skip Maven/Docker, just update GCP resources + rolling update

set -e

# Configuration
PROJECT_ID="halo-ads"
REGION="us-central1"
ZONE="us-central1-a"
VPC_NAME="prebid-vpc"
SERVICE_ACCOUNT="prebid-server"
INSTANCE_GROUP="prebid-server-group"
HEALTH_CHECK="prebid-health-check"
LOAD_BALANCER="prebid-lb"
BUCKET_NAME="prebid-server-configs"
REPO_NAME="prebid-repo"
IMAGE_NAME="prebid-server"
MACHINE_TYPE="e2-small"
INITIAL_INSTANCES=1

echo "=========================================="
echo "  Prebid Server GCP Deploy"
echo "=========================================="
echo "Project:   $PROJECT_ID"
echo "Region:    $REGION"
echo "Machine:   $MACHINE_TYPE"
echo "Instances: $INITIAL_INSTANCES"
echo ""

gcloud config set project $PROJECT_ID

# ---------- APIs ----------
echo "--- Enabling GCP APIs ---"
gcloud services enable compute.googleapis.com --quiet
gcloud services enable logging.googleapis.com --quiet
gcloud services enable monitoring.googleapis.com --quiet
gcloud services enable storage-component.googleapis.com --quiet
gcloud services enable cloudbuild.googleapis.com --quiet
gcloud services enable artifactregistry.googleapis.com --quiet
gcloud services enable secretmanager.googleapis.com --quiet
echo "APIs enabled"
echo ""

# ---------- VPC ----------
echo "--- VPC ---"
if gcloud compute networks describe $VPC_NAME &>/dev/null; then
    echo "VPC already exists"
else
    gcloud compute networks create $VPC_NAME --subnet-mode=auto --bgp-routing-mode=regional
    echo "VPC created"
fi
echo ""

# ---------- Firewall ----------
echo "--- Firewall rules ---"
for RULE_NAME in allow-http allow-admin allow-health-check; do
    if gcloud compute firewall-rules describe $RULE_NAME &>/dev/null; then
        echo "$RULE_NAME already exists"
    else
        case $RULE_NAME in
            allow-http)
                gcloud compute firewall-rules create $RULE_NAME \
                    --network=$VPC_NAME --allow tcp:8080 \
                    --source-ranges=0.0.0.0/0 --target-tags=prebid-server \
                    --description="Allow HTTP traffic to Prebid Server" 2>/dev/null || true
                ;;
            allow-admin)
                gcloud compute firewall-rules create $RULE_NAME \
                    --network=$VPC_NAME --allow tcp:8060 \
                    --source-ranges=0.0.0.0/0 --target-tags=prebid-server \
                    --description="Allow admin port access" 2>/dev/null || true
                ;;
            allow-health-check)
                gcloud compute firewall-rules create $RULE_NAME \
                    --network=$VPC_NAME --allow tcp:8080 \
                    --source-ranges=130.211.0.0/22,35.191.0.0/16 --target-tags=prebid-server \
                    --description="Allow GCP health checks" 2>/dev/null || true
                ;;
        esac
        echo "$RULE_NAME created (or already existed)"
    fi
done
echo ""

# ---------- Service account ----------
echo "--- Service account ---"
SA_EMAIL="${SERVICE_ACCOUNT}@${PROJECT_ID}.iam.gserviceaccount.com"
if gcloud iam service-accounts describe $SA_EMAIL &>/dev/null; then
    echo "Service account already exists"
else
    gcloud iam service-accounts create $SERVICE_ACCOUNT --display-name="Prebid Server Service Account"
    echo "Service account created"
fi

for ROLE in roles/monitoring.metricWriter roles/logging.logWriter roles/storage.objectViewer roles/artifactregistry.reader; do
    gcloud projects add-iam-policy-binding $PROJECT_ID \
        --member="serviceAccount:$SA_EMAIL" --role="$ROLE" --condition=None &>/dev/null || true
done
echo "Permissions granted"
echo ""

# ---------- GCS bucket + config upload ----------
echo "--- GCS bucket ---"
if gsutil ls -b gs://$BUCKET_NAME &>/dev/null; then
    echo "Bucket already exists"
else
    gsutil mb -p $PROJECT_ID -l $REGION gs://$BUCKET_NAME
    echo "Bucket created"
fi
gsutil iam ch "serviceAccount:${SA_EMAIL}:objectViewer" gs://$BUCKET_NAME 2>/dev/null || true

echo "Uploading production configs to GCS..."
gsutil -m cp -r production/ gs://$BUCKET_NAME/ 2>/dev/null || true
echo "Configs uploaded"
echo ""

# ---------- Artifact Registry ----------
echo "--- Artifact Registry ---"
if gcloud artifacts repositories describe $REPO_NAME --location=$REGION &>/dev/null; then
    echo "Repository already exists"
else
    gcloud artifacts repositories create $REPO_NAME \
        --repository-format=docker --location=$REGION --description="Prebid Server Docker images"
    echo "Repository created"
fi
gcloud auth configure-docker ${REGION}-docker.pkg.dev --quiet
echo ""

# ---------- Build + Push ----------
IMAGE_TAG="${REGION}-docker.pkg.dev/${PROJECT_ID}/${REPO_NAME}/${IMAGE_NAME}:latest"

if [ -n "$SKIP_BUILD" ]; then
    echo "--- Skipping build/push (SKIP_BUILD is set) ---"
    echo ""
else
    echo "--- Docker check ---"
    if ! docker info &>/dev/null; then
        echo "ERROR: Docker is not running. Start Docker and try again."
        exit 1
    fi

    echo "--- Maven build (tests skipped) ---"
    MVN_OPTS="-DskipTests -Dmaven.test.skip=true"
    [ -f /tmp/empty-settings.xml ] && MVN_OPTS="-s /tmp/empty-settings.xml $MVN_OPTS"
    mvn clean package $MVN_OPTS

    echo "--- Docker build (linux/amd64) ---"
    docker build --platform linux/amd64 -t $IMAGE_TAG .

    echo "--- Docker push ---"
    docker push $IMAGE_TAG
    echo "Image pushed: $IMAGE_TAG"
    echo ""
fi

# ---------- Health check ----------
echo "--- Health check ---"
if gcloud compute health-checks describe $HEALTH_CHECK &>/dev/null; then
    echo "Health check already exists"
else
    gcloud compute health-checks create http $HEALTH_CHECK \
        --port=8080 --request-path=/status \
        --check-interval=10s --timeout=5s \
        --healthy-threshold=2 --unhealthy-threshold=3
    echo "Health check created"
    echo "Waiting for health check to be ready..."
    sleep 5
fi
echo ""

# ---------- Instance template (alternate a/b to avoid in-use delete error) ----------
echo "--- Instance template ---"
CURRENT_TEMPLATE=""
if gcloud compute instance-groups managed describe $INSTANCE_GROUP --zone=$ZONE &>/dev/null; then
    CURRENT_TEMPLATE=$(gcloud compute instance-groups managed describe $INSTANCE_GROUP --zone=$ZONE \
        --format="value(instanceTemplate)" 2>/dev/null | sed 's|.*/||' || true)
fi

if [ "$CURRENT_TEMPLATE" = "prebid-server-template-a" ]; then
    NEW_TEMPLATE="prebid-server-template-b"
else
    NEW_TEMPLATE="prebid-server-template-a"
fi

# Delete the new template name if it already exists (leftover from a previous deploy)
if gcloud compute instance-templates describe $NEW_TEMPLATE &>/dev/null; then
    gcloud compute instance-templates delete $NEW_TEMPLATE --quiet 2>/dev/null || true
fi

# Container spec: NO command/args override — the image ENTRYPOINT (run.sh) runs directly.
# Config is baked into the image at /app/prebid-server/production/configs/prebid-config.yaml.
# JAVA_OPTS tells Spring Boot to import it.
CONTAINER_DECL_FILE="/tmp/prebid-container-decl-${PROJECT_ID}.yaml"
cat > $CONTAINER_DECL_FILE <<'OUTER'
spec:
  containers:
    - name: prebid-server
      image: IMAGE_TAG_PLACEHOLDER
      stdin: false
      tty: false
      env:
        - name: JAVA_OPTS
          value: "-Xmx512m -Xms256m -Dspring.config.import=file:/app/prebid-server/production/configs/prebid-config.yaml -Dvertx.options.blockedThreadCheckInterval=30000 -Dvertx.options.warningExceptionTime=15000"
      ports:
        - name: http
          containerPort: 8080
        - name: admin
          containerPort: 8060
  restartPolicy: Always
OUTER
sed -i.bak "s|IMAGE_TAG_PLACEHOLDER|${IMAGE_TAG}|" $CONTAINER_DECL_FILE && rm -f ${CONTAINER_DECL_FILE}.bak

gcloud compute instance-templates create $NEW_TEMPLATE \
    --machine-type=$MACHINE_TYPE \
    --image-family=cos-stable \
    --image-project=cos-cloud \
    --boot-disk-size=20GB \
    --boot-disk-type=pd-standard \
    --tags=prebid-server,http-server \
    --service-account=$SA_EMAIL \
    --scopes=https://www.googleapis.com/auth/cloud-platform \
    --metadata-from-file=gce-container-declaration=$CONTAINER_DECL_FILE

rm -f $CONTAINER_DECL_FILE
echo "Instance template created: $NEW_TEMPLATE"
echo ""

# ---------- Managed instance group ----------
echo "--- Managed instance group ---"
if gcloud compute instance-groups managed describe $INSTANCE_GROUP --zone=$ZONE &>/dev/null; then
    echo "Instance group already exists"
else
    gcloud compute instance-groups managed create $INSTANCE_GROUP \
        --base-instance-name=prebid-server \
        --size=$INITIAL_INSTANCES \
        --template=$NEW_TEMPLATE \
        --zone=$ZONE \
        --health-check=$HEALTH_CHECK \
        --initial-delay=300
    echo "Instance group created"
fi
echo ""

# ---------- Load balancer ----------
echo "--- Load balancer ---"
if gcloud compute backend-services describe $LOAD_BALANCER --global &>/dev/null; then
    echo "Backend service already exists"
else
    echo "Creating backend service..."
    i=1
    while [ $i -le 5 ]; do
        if gcloud compute backend-services create $LOAD_BALANCER \
            --protocol=HTTP --port-name=http \
            --health-checks=$HEALTH_CHECK --global 2>/dev/null; then
            echo "Backend service created"
            break
        fi
        if [ $i -eq 5 ]; then
            echo "ERROR: Failed to create backend service after 5 attempts"
            exit 1
        fi
        echo "  Retrying ($i/5)..."
        sleep 5
        i=$((i + 1))
    done
fi

# Add instance group as backend (only if not already there)
EXISTING_BACKENDS=$(gcloud compute backend-services describe $LOAD_BALANCER --global \
    --format="value(backends[].group)" 2>/dev/null || true)
if echo "$EXISTING_BACKENDS" | grep -q "$INSTANCE_GROUP"; then
    echo "Backend already has instance group"
else
    gcloud compute backend-services add-backend $LOAD_BALANCER \
        --instance-group=$INSTANCE_GROUP --instance-group-zone=$ZONE --global
    echo "Backend added"
fi

if gcloud compute url-maps describe ${LOAD_BALANCER}-map &>/dev/null; then
    echo "URL map already exists"
else
    gcloud compute url-maps create ${LOAD_BALANCER}-map --default-service=$LOAD_BALANCER
    echo "URL map created"
fi

if gcloud compute target-http-proxies describe ${LOAD_BALANCER}-proxy &>/dev/null; then
    echo "HTTP proxy already exists"
else
    gcloud compute target-http-proxies create ${LOAD_BALANCER}-proxy --url-map=${LOAD_BALANCER}-map
    echo "HTTP proxy created"
fi

if gcloud compute forwarding-rules describe ${LOAD_BALANCER}-rule --global &>/dev/null; then
    echo "Forwarding rule already exists"
    LB_IP=$(gcloud compute forwarding-rules describe ${LOAD_BALANCER}-rule --global --format="value(IPAddress)")
else
    gcloud compute forwarding-rules create ${LOAD_BALANCER}-rule \
        --global --target-http-proxy=${LOAD_BALANCER}-proxy --ports=80
    sleep 5
    LB_IP=$(gcloud compute forwarding-rules describe ${LOAD_BALANCER}-rule --global --format="value(IPAddress)")
    echo "Forwarding rule created"
fi
echo ""

# ---------- Rolling update ----------
echo "--- Rolling update (template: $NEW_TEMPLATE) ---"
gcloud compute instance-groups managed rolling-action start-update $INSTANCE_GROUP \
    --version=template=$NEW_TEMPLATE \
    --zone=$ZONE

# Clean up the old template (it's no longer referenced after the rolling update starts)
if [ -n "$CURRENT_TEMPLATE" ] && [ "$CURRENT_TEMPLATE" != "$NEW_TEMPLATE" ]; then
    echo "Deleting old template: $CURRENT_TEMPLATE"
    gcloud compute instance-templates delete $CURRENT_TEMPLATE --quiet 2>/dev/null || echo "  (old template still in use, will be cleaned up next deploy)"
fi

echo ""
echo "=========================================="
echo "  DONE"
echo "=========================================="
echo "Load Balancer IP: $LB_IP"
echo "Test:   curl http://$LB_IP/status"
echo "Auction: http://$LB_IP/openrtb2/auction"
echo ""
echo "Rolling update started. Wait 2-3 min for the new VM to come up."
echo "Check progress: gcloud compute instance-groups managed list-instances $INSTANCE_GROUP --zone=$ZONE"
echo ""
