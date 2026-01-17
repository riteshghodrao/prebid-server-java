# Prebid Server Deployment on Google Cloud Platform

This guide covers deploying Prebid Server on GCP using VMs and Google Cloud Monitoring.

## Required GCP Services

### Core Infrastructure (Required)

1. **Compute Engine** - Virtual machines for Prebid Server instances
2. **Cloud Load Balancing** - Distribute traffic across multiple VMs
3. **VPC Network** - Virtual private cloud for secure networking
4. **Cloud Monitoring** - Metrics and alerting (you mentioned this)
5. **Cloud Logging** - Centralized log aggregation (essential with monitoring)
6. **Cloud Storage** - Store configuration files, stored requests/imps/responses
7. **Cloud Build** - CI/CD for building and deploying Docker images
8. **Container Registry / Artifact Registry** - Store Docker images
9. **Identity & Access Management (IAM)** - Service accounts and permissions
10. **Secret Manager** - Store sensitive credentials (API keys, passwords)

### Optional but Recommended

11. **Cloud SQL** - If using database-backed stored requests (instead of file system)
12. **Cloud Memorystore (Redis)** - Caching layer (optional but improves performance)
13. **Cloud Armor** - DDoS protection and security policies
14. **Cloud CDN** - Content delivery for static assets
15. **Cloud DNS** - Domain name management
16. **Cloud Endpoints** - API management (if needed)

## Architecture Overview

```
                    Internet
                       |
              Cloud Load Balancer
                       |
        ┌──────────────┼──────────────┐
        |              |              |
    VM Instance 1  VM Instance 2  VM Instance N
    (Prebid Server) (Prebid Server) (Prebid Server)
        |              |              |
        └──────────────┼──────────────┘
                       |
        ┌──────────────┼──────────────┐
        |              |              |
    Cloud Storage  Cloud SQL  Cloud Memorystore
  (Configs/Stored) (Optional)    (Cache)
```

## Step-by-Step Deployment Guide

### 1. Prerequisites

- GCP account with billing enabled
- `gcloud` CLI installed and authenticated
- Project created in GCP

```bash
# Set your project
export PROJECT_ID="your-prebid-project"
gcloud config set project $PROJECT_ID

# Enable required APIs
gcloud services enable compute.googleapis.com
gcloud services enable logging.googleapis.com
gcloud services enable monitoring.googleapis.com
gcloud services enable storage-component.googleapis.com
gcloud services enable cloudbuild.googleapis.com
gcloud services enable artifactregistry.googleapis.com
gcloud services enable secretmanager.googleapis.com
gcloud services enable sqladmin.googleapis.com  # If using Cloud SQL
gcloud services enable redis.googleapis.com      # If using Memorystore
```

### 2. Create VPC Network

```bash
# Create VPC
gcloud compute networks create prebid-vpc \
    --subnet-mode=auto \
    --bgp-routing-mode=regional

# Create firewall rules
gcloud compute firewall-rules create allow-http \
    --network=prebid-vpc \
    --allow tcp:8080 \
    --source-ranges=0.0.0.0/0 \
    --description="Allow HTTP traffic to Prebid Server"

gcloud compute firewall-rules create allow-admin \
    --network=prebid-vpc \
    --allow tcp:8060 \
    --source-ranges=0.0.0.0/0 \
    --target-tags=prebid-server \
    --description="Allow admin port access"

gcloud compute firewall-rules create allow-health-check \
    --network=prebid-vpc \
    --allow tcp:8080 \
    --source-ranges=130.211.0.0/22,35.191.0.0/16 \
    --target-tags=prebid-server \
    --description="Allow GCP health checks"
```

### 3. Create Cloud Storage Bucket for Configs

```bash
# Create bucket for configs and stored data
gsutil mb -p $PROJECT_ID -l us-central1 gs://prebid-server-configs

# Upload production configs
gsutil cp -r production/ gs://prebid-server-configs/
gsutil iam ch serviceAccount:prebid-server@$PROJECT_ID.iam.gserviceaccount.com:objectViewer \
    gs://prebid-server-configs
```

### 4. Create Service Account

```bash
# Create service account
gcloud iam service-accounts create prebid-server \
    --display-name="Prebid Server Service Account"

# Grant permissions
gcloud projects add-iam-policy-binding $PROJECT_ID \
    --member="serviceAccount:prebid-server@$PROJECT_ID.iam.gserviceaccount.com" \
    --role="roles/monitoring.metricWriter"

gcloud projects add-iam-policy-binding $PROJECT_ID \
    --member="serviceAccount:prebid-server@$PROJECT_ID.iam.gserviceaccount.com" \
    --role="roles/logging.logWriter"

gcloud projects add-iam-policy-binding $PROJECT_ID \
    --member="serviceAccount:prebid-server@$PROJECT_ID.iam.gserviceaccount.com" \
    --role="roles/storage.objectViewer"

# Grant secret accessor if using Secret Manager
gcloud projects add-iam-policy-binding $PROJECT_ID \
    --member="serviceAccount:prebid-server@$PROJECT_ID.iam.gserviceaccount.com" \
    --role="roles/secretmanager.secretAccessor"
```

### 5. Store Secrets in Secret Manager

```bash
# Store Prebid Cache credentials (example)
echo -n "your-cache-api-key" | gcloud secrets create prebid-cache-api-key \
    --data-file=- \
    --replication-policy="automatic"

# Store database password (if using Cloud SQL)
echo -n "your-db-password" | gcloud secrets create prebid-db-password \
    --data-file=- \
    --replication-policy="automatic"
```

### 6. Build and Push Docker Image

```bash
# Create Artifact Registry repository
gcloud artifacts repositories create prebid-repo \
    --repository-format=docker \
    --location=us-central1 \
    --description="Prebid Server Docker images"

# Configure Docker authentication
gcloud auth configure-docker us-central1-docker.pkg.dev

# Build Docker image (from project root)
docker build -t us-central1-docker.pkg.dev/$PROJECT_ID/prebid-repo/prebid-server:latest .

# Push to Artifact Registry
docker push us-central1-docker.pkg.dev/$PROJECT_ID/prebid-repo/prebid-server:latest
```

### 7. Create Instance Template

Create `instance-template.yaml`:

```yaml
name: prebid-server-template
properties:
  machineType: e2-medium  # or e2-standard-2 for production
  tags:
    items:
      - prebid-server
      - http-server
  serviceAccounts:
    - email: prebid-server@PROJECT_ID.iam.gserviceaccount.com
      scopes:
        - https://www.googleapis.com/auth/cloud-platform
  disks:
    - boot: true
      autoDelete: true
      initializeParams:
        sourceImage: projects/cos-cloud/global/images/family/cos-stable
        diskSizeGb: 20
  metadata:
    items:
      - key: gce-container-declaration
        value: |
          spec:
            containers:
              - name: prebid-server
                image: us-central1-docker.pkg.dev/PROJECT_ID/prebid-repo/prebid-server:latest
                stdin: false
                tty: false
                env:
                  - name: JAVA_OPTS
                    value: "-Xmx1g -Xms1g"
                  - name: SPRING_CONFIG_LOCATION
                    value: "gs://prebid-server-configs/production/configs/prebid-config.yaml"
                ports:
                  - name: http
                    containerPort: 8080
                  - name: admin
                    containerPort: 8060
                volumeMounts:
                  - name: tmp
                    mountPath: /var/tmp
            restartPolicy: Always
            volumes:
              - name: tmp
                emptyDir: {}
```

```bash
# Create instance template
gcloud compute instance-templates create prebid-server-template \
    --source-instance-template-file=instance-template.yaml \
    --service-account=prebid-server@$PROJECT_ID.iam.gserviceaccount.com
```

### 8. Create Managed Instance Group

```bash
# Create health check
gcloud compute health-checks create http prebid-health-check \
    --port=8080 \
    --request-path=/status \
    --check-interval=10s \
    --timeout=5s \
    --healthy-threshold=2 \
    --unhealthy-threshold=3

# Create managed instance group
gcloud compute instance-groups managed create prebid-server-group \
    --base-instance-name=prebid-server \
    --size=2 \
    --template=prebid-server-template \
    --zone=us-central1-a \
    --health-check=prebid-health-check \
    --initial-delay=300 \
    --min-num-replicas=2 \
    --max-num-replicas=10 \
    --target-cpu-utilization=0.75
```

### 9. Create Load Balancer

```bash
# Create backend service
gcloud compute backend-services create prebid-backend \
    --protocol=HTTP \
    --health-checks=prebid-health-check \
    --global \
    --load-balancing-scheme=EXTERNAL

# Add instance group to backend
gcloud compute backend-services add-backend prebid-backend \
    --instance-group=prebid-server-group \
    --instance-group-zone=us-central1-a \
    --global

# Create URL map
gcloud compute url-maps create prebid-url-map \
    --default-service=prebid-backend

# Create HTTP proxy
gcloud compute target-http-proxies create prebid-http-proxy \
    --url-map=prebid-url-map

# Create forwarding rule
gcloud compute forwarding-rules create prebid-forwarding-rule \
    --global \
    --target-http-proxy=prebid-http-proxy \
    --ports=80

# Create HTTPS forwarding rule (recommended for production)
gcloud compute ssl-certificates create prebid-ssl-cert \
    --domains=prebid.yourdomain.com

gcloud compute target-https-proxies create prebid-https-proxy \
    --url-map=prebid-url-map \
    --ssl-certificates=prebid-ssl-cert

gcloud compute forwarding-rules create prebid-https-forwarding-rule \
    --global \
    --target-https-proxy=prebid-https-proxy \
    --ports=443
```

### 10. Configure Google Cloud Monitoring

#### Enable Monitoring Agent on VMs

The monitoring agent is automatically enabled on Container-Optimized OS (COS) images.

#### Update Prebid Config for Monitoring

Update `production/configs/prebid-config.yaml`:

```yaml
metrics:
  prefix: prebid
  # Enable Prometheus metrics endpoint (Cloud Monitoring can scrape this)
  prometheus:
    enabled: true
    port: 8070  # Prometheus metrics port
```

#### Create Custom Dashboard

Create `monitoring-dashboard.json`:

```json
{
  "displayName": "Prebid Server Dashboard",
  "mosaicLayout": {
    "columns": 12,
    "tiles": [
      {
        "width": 6,
        "height": 4,
        "widget": {
          "title": "Request Rate",
          "xyChart": {
            "dataSets": [
              {
                "timeSeriesQuery": {
                  "timeSeriesFilter": {
                    "filter": "metric.type=\"custom.googleapis.com/prebid/requests\""
                  }
                }
              }
            ]
          }
        }
      }
    ]
  }
}
```

### 11. Configure Cloud Logging

#### Update Logging Configuration

Create `production/configs/logback-gcp.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <include resource="org/springframework/boot/logging/logback/defaults.xml"/>
    
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder class="ch.qos.logback.core.encoder.LayoutWrappingEncoder">
            <layout class="ch.qos.logback.contrib.json.classic.JsonLayout">
                <jsonFormatter class="ch.qos.logback.contrib.jackson.JacksonJsonFormatter">
                    <prettyPrint>false</prettyPrint>
                </jsonFormatter>
                <timestampFormat>yyyy-MM-dd'T'HH:mm:ss.SSSX</timestampFormat>
                <appendLineSeparator>true</appendLineSeparator>
            </layout>
        </encoder>
    </appender>

    <logger name="org.prebid" level="INFO"/>
    <logger name="org.prebid.server.analytics" level="DEBUG"/>
    
    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
    </root>
</configuration>
```

### 12. Optional: Cloud SQL Setup (for Database-backed Stored Requests)

```bash
# Create Cloud SQL instance
gcloud sql instances create prebid-db \
    --database-version=MYSQL_8_0 \
    --tier=db-f1-micro \
    --region=us-central1 \
    --root-password=$(gcloud secrets versions access latest --secret=prebid-db-password)

# Create database
gcloud sql databases create pbs --instance=prebid-db

# Create user
gcloud sql users create prebid \
    --instance=prebid-db \
    --password=$(gcloud secrets versions access latest --secret=prebid-db-password)

# Get connection name for config
gcloud sql instances describe prebid-db --format="value(connectionName)"
```

Update config to use Cloud SQL:

```yaml
settings:
  database:
    type: mysql
    host: /cloudsql/PROJECT_ID:us-central1:prebid-db
    port: 3306
    dbname: pbs
    user: prebid
    password: ${PREBID_DB_PASSWORD}  # From Secret Manager
```

### 13. Optional: Cloud Memorystore (Redis) for Caching

```bash
# Create Redis instance
gcloud redis instances create prebid-cache \
    --size=1 \
    --region=us-central1 \
    --redis-version=redis_7_0
```

## Configuration for GCP Production

Update `production/configs/prebid-config.yaml`:

```yaml
status-response: "ok"

adapters:
  appnexus:
    enabled: true
  # ... other adapters

metrics:
  prefix: prebid
  prometheus:
    enabled: true
    port: 8070

cache:
  scheme: http
  host: your-prebid-cache-host
  path: /cache
  query: uuid=

settings:
  enforce-valid-account: true
  generate-storedrequest-bidrequest-id: true
  filesystem:
    # Load from Cloud Storage (mounted or downloaded at startup)
    settings-filename: /app/prebid-server/production/configs/app-settings.yaml
    stored-requests-dir: /app/prebid-server/production/stored-requests
    stored-imps-dir: /app/prebid-server/production/stored-imps
    stored-responses-dir: /app/prebid-server/production/stored-responses

server:
  port: 8080
  admin-port: 8060

logging:
  config: classpath:logback-gcp.xml

# Google Cloud Monitoring integration
analytics:
  log:
    enabled: true  # Enable log analytics
  # Your custom GCP Monitoring reporter will be added here
  gcp-monitoring:
    enabled: true
    project-id: ${GCP_PROJECT_ID}
```

## Deployment Script

Create `deploy.sh`:

```bash
#!/bin/bash
set -e

PROJECT_ID="your-prebid-project"
ZONE="us-central1-a"
IMAGE="us-central1-docker.pkg.dev/$PROJECT_ID/prebid-repo/prebid-server:latest"

echo "Building Docker image..."
docker build -t $IMAGE .

echo "Pushing to Artifact Registry..."
docker push $IMAGE

echo "Updating instance group..."
gcloud compute instance-groups managed rolling-action start-update prebid-server-group \
    --version=template=prebid-server-template \
    --zone=$ZONE

echo "Deployment initiated. Monitor with:"
echo "gcloud compute instance-groups managed list-instances prebid-server-group --zone=$ZONE"
```

## Monitoring and Alerting

### Create Alert Policies

```bash
# High error rate alert
gcloud alpha monitoring policies create \
    --notification-channels=CHANNEL_ID \
    --display-name="Prebid Server High Error Rate" \
    --condition-threshold-value=10 \
    --condition-threshold-duration=300s \
    --condition-filter='metric.type="custom.googleapis.com/prebid/requests" 
                       resource.type="gce_instance"'
```

### Key Metrics to Monitor

1. **Request Rate**: Total requests per second
2. **Error Rate**: Failed requests percentage
3. **Response Time**: P95/P99 latency
4. **Bid Win Rate**: Percentage of bids won
5. **Cache Hit Rate**: Prebid Cache hit percentage
6. **VM Health**: CPU, memory, disk usage
7. **Ad Served Rate**: Percentage of requests with served ads

## Cost Optimization Tips

1. **Use Preemptible VMs** for non-critical environments (60-80% savings)
2. **Right-size instances** based on actual load
3. **Use committed use discounts** for predictable workloads
4. **Enable auto-scaling** to scale down during low traffic
5. **Use Cloud Storage** instead of expensive block storage for configs
6. **Monitor and set budgets** to avoid unexpected costs

## Security Best Practices

1. **Use service accounts** with least privilege
2. **Enable VPC firewall rules** to restrict access
3. **Use Secret Manager** for all credentials
4. **Enable Cloud Armor** for DDoS protection
5. **Use HTTPS** for all external traffic
6. **Regular security updates** on VM images
7. **Enable audit logging** for compliance

## Next Steps

1. Set up CI/CD pipeline with Cloud Build
2. Configure Cloud Monitoring dashboards
3. Set up alerting policies
4. Implement custom GCP Monitoring analytics reporter
5. Set up log aggregation and analysis
6. Configure backup and disaster recovery

## Troubleshooting

### View Logs
```bash
gcloud logging read "resource.type=gce_instance" --limit=50
```

### Check VM Status
```bash
gcloud compute instances list
gcloud compute instance-groups managed list-instances prebid-server-group
```

### SSH into VM
```bash
gcloud compute ssh prebid-server-XXXX --zone=us-central1-a
```

