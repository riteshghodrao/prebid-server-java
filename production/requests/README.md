# Sample Request Files

This directory contains sample request bodies for testing stored requests and stored impressions.

## Files

1. **test-stored-request.json** - Uses the stored request `main-banner-request` (includes all 4 ad sizes)
2. **test-single-imp.json** - Uses a single stored impression `banner_300x250`
3. **test-multiple-imps.json** - Uses multiple stored impressions (320x50 and 300x250)
4. **test-complete-request.json** - Complete request with all optional fields

## Quick Test

### Using Stored Request (All 4 Ad Sizes)

```bash
curl -X POST http://localhost:8080/openrtb2/auction \
  -H "Content-Type: application/json" \
  -d @production/requests/test-stored-request.json
```

### Using Single Stored Impression

```bash
curl -X POST http://localhost:8080/openrtb2/auction \
  -H "Content-Type: application/json" \
  -d @production/requests/test-single-imp.json
```

### Using Multiple Stored Impressions

```bash
curl -X POST http://localhost:8080/openrtb2/auction \
  -H "Content-Type: application/json" \
  -d @production/requests/test-multiple-imps.json
```

## What Happens

1. **Stored Request**: Prebid Server loads `main-banner-request.json` which references 4 stored impressions
2. **Stored Impressions**: Each impression is loaded from `production/stored-imps/` directory
3. **Stored Responses**: For testing, stored responses are returned (configured in stored impressions)
4. **Real Bidders**: In production, remove stored response references to get real bids

## Account ID

Make sure the `publisher.id` in your request matches an account in `production/configs/app-settings.yaml` (currently `1001`).

