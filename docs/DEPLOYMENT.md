# Prebid Server – GCP Deployment

## Summary

**There is a single script:** `deploy-to-gcp.sh`  
It does **first-time setup** (VPC, firewall, service account, bucket, Artifact Registry, load balancer, etc.) and **every deploy** (Maven build, Docker build/push, config upload, instance template recreate). Existing resources are skipped or updated; only build and template are always redone. At the end it runs a **rolling update** so the instance group picks up the new image.

---

## Prerequisites

- **gcloud** CLI installed and logged in (`gcloud auth login`).
- **Docker** running (script skips build/push if Docker is down).
- **Optional:** `/tmp/empty-settings.xml` – minimal Maven settings (no corporate mirror). If present, the script uses it for `mvn` so the build works in restricted environments.

---

## Single command (full deploy)

From the project root:

```bash
./deploy-to-gcp.sh
```

What it does, in order:

| Step | What happens |
|------|-------------------------------|
| 1. Project | `gcloud config set project halo-ads` |
| 2. APIs | Enables Compute, Logging, Monitoring, Storage, Cloud Build, Artifact Registry, Secret Manager |
| 3. VPC | Creates `prebid-vpc` if missing (auto subnet) |
| 4. Firewall | Creates `allow-http` (8080), `allow-admin` (8060), `allow-health-check` (GCP health-check IPs), target tag `prebid-server` |
| 5. Service account | Creates `prebid-server@halo-ads.iam.gserviceaccount.com` if missing |
| 6. IAM | Grants that account: monitoring metricWriter, logging logWriter, storage objectViewer, artifactregistry reader |
| 7. GCS bucket | Creates `gs://prebid-server-configs` if missing |
| 8. Upload configs | `gsutil -m cp -r production/ gs://prebid-server-configs/` (idempotent) |
| 9. Bucket IAM | Gives the service account objectViewer on the bucket |
| 10. Artifact Registry | Creates repo `prebid-repo` in `us-central1` if missing |
| 11. Docker auth | `gcloud auth configure-docker us-central1-docker.pkg.dev` |
| 12. Build (if Docker up) | `mvn clean package` (with `-s /tmp/empty-settings.xml` if that file exists), then `docker build -t us-central1-docker.pkg.dev/halo-ads/prebid-repo/prebid-server:latest .` |
| 13. Push | `docker push us-central1-docker.pkg.dev/halo-ads/prebid-repo/prebid-server:latest` |
| 14. Health check | Creates `prebid-health-check` (HTTP :8080/status) if missing |
| 15. Instance template | **Deletes** `prebid-server-template` if it exists, then **creates** it again with: COS image, e2-micro, 20GB disk, tag `prebid-server`, service account, container spec that at boot downloads `prebid-config.yaml` from GCS to `/tmp/prebid-config/` and runs Prebid Server with `SPRING_CONFIG_LOCATION=file:/tmp/prebid-config/prebid-config.yaml` |
| 16. Instance group | If `prebid-server-group` exists: resize to 1 in `us-central1-a`. If not: create MIG with that template, size 1, health check, 300s initial delay |
| 17. Load balancer | Backend service `prebid-lb`, URL map `prebid-lb-map`, HTTP proxy `prebid-lb-proxy`, global forwarding rule `prebid-lb-rule` (port 80). Backend = the MIG in us-central1-a (skipped if that instance group is already a backend). |
| 18. Rolling update | Runs `gcloud compute instance-groups managed rolling-action start-update prebid-server-group --version=template=prebid-server-template --zone=us-central1-a` so running VMs are replaced with the new image. |
| 19. Done | Prints load balancer IP and test URLs |

So: **one script, one run** = build, push, config upload, GCP wiring, and rolling update. First run creates everything; later runs reuse most resources, rebuild, re-upload configs, recreate the instance template, and roll out the new template.

---

## Configuration

| Variable | Value | Meaning |
|----------|--------|---------|
| PROJECT_ID | halo-ads | GCP project |
| REGION / ZONE | us-central1 / us-central1-a | Where the MIG and registry live |
| VPC_NAME | prebid-vpc | Network for VMs |
| BUCKET_NAME | prebid-server-configs | Configs and `production/` upload target |
| REPO_NAME | prebid-repo | Artifact Registry repo for the image |
| IMAGE_NAME | prebid-server | Image name; tag used is `latest` |
| MACHINE_TYPE | e2-micro | VM size |
| INITIAL_INSTANCES | 1 | MIG size |

Main app config at runtime: **only** `production/configs/prebid-config.yaml` is loaded from GCS; the rest of `production/` is in the image (and also uploaded to GCS for reference). Stored requests/imps/responses paths in `prebid-config.yaml` point at `production/` inside the container.

---

## After running the script (code/config updates)

- The script **runs a rolling update** at the end, so the instance group recreates VMs from the new template and new code/config is live once the rollout finishes.
- To **only** roll out (e.g. you already ran the script and want to restart VMs on the same image):

  ```bash
  gcloud compute instance-groups managed rolling-action start-update prebid-server-group \
    --version=template=prebid-server-template \
    --zone=us-central1-a
  ```

- **Config-only changes** (e.g. `production/configs/prebid-config.yaml`): Re-run `./deploy-to-gcp.sh` so configs are re-uploaded to GCS; the rolling update will start new VMs that re-download config from GCS at boot.

---

## HTTPS / custom domains (done outside this script)

In the past, HTTPS and custom domains were set up separately:

- **Managed SSL certificate** in GCP (e.g. for `ads.haloads.io`, `sync.haloads.io`, `analytics.haloads.io`).
- **HTTPS proxy** and **target-https-proxies** / **url-maps** pointing to the same backend (prebid-lb).
- **DNS**: A records for those hostnames to the **load balancer IP** (the one printed at the end of `deploy-to-gcp.sh`).

The script itself only creates the **HTTP** global forwarding rule (port 80). Any HTTPS and host-based routing (e.g. `/bid`, `/event`, `/sync.png`) were added via separate gcloud/Console steps and are not in the script.

---

## Optional: build and push only (no GCP changes)

If you only want to build and push the image (e.g. to trigger another system, or to prepare for a manual rolling update later):

```bash
# Use empty Maven settings if you need to avoid corporate mirror
[ -f /tmp/empty-settings.xml ] && MVN_OPTS="-s /tmp/empty-settings.xml -DskipTests" || MVN_OPTS="-DskipTests"
mvn clean package $MVN_OPTS
docker build -t us-central1-docker.pkg.dev/halo-ads/prebid-repo/prebid-server:latest .
docker push us-central1-docker.pkg.dev/halo-ads/prebid-repo/prebid-server:latest
```

Then run the **rolling update** command above when you want the instance group to use the new image.

---

## Summary table

| Question | Answer |
|----------|--------|
| One script or many? | **One script:** `deploy-to-gcp.sh` |
| What does it do? | Build (Maven + Docker), push image, upload `production/` to GCS, create/update all GCP resources (VPC, firewall, SA, bucket, Artifact Registry, health check, instance template, MIG, HTTP load balancer). |
| Does it roll out new code? | Yes. At the end it runs a **rolling update** so the instance group uses the new template/image. |
| HTTPS / custom domains? | Not in the script; configured separately (managed cert, HTTPS proxy, DNS). |
