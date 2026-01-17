# Custom Analytics Reporter for Google Cloud Monitoring

## What Prebid Server Supports Out of the Box

Prebid Server has built-in support for analytics reporting through the `AnalyticsReporter` interface. Here's what's available:

### Events Supported

1. **AuctionEvent** - Fired for every `/openrtb2/auction` request
   - Contains: `AuctionContext`, `BidResponse`, `HttpRequestContext`, status, errors
   - Available data:
     - Bid request (including stored impression IDs)
     - Bid response (bids received, prices)
     - Account information
     - Country (from `GeoInfo` or `Device.geo`)
     - All impression data
     - Bidder responses and errors

2. **NotificationEvent** - Fired when Prebid JS calls `/event` endpoint
   - Types: `win` (bid won) and `imp` (impression rendered)
   - Contains: `bidId`, `bidder`, `account`, `timestamp`, `integration`, `HttpRequestContext`

### What's NOT Supported by Prebid Server

Prebid Server does **NOT** directly track these Prebid JS events:
- `onTimeout` - Timeout events are tracked at bidder level but not exposed as analytics events
- `onBidderError` - Bidder errors are tracked in `AuctionEvent.errors` but not as separate events
- `onSetTargeting` - Not tracked (client-side only)
- `onAdRenderSucceeded` - Use `NotificationEvent` with type `imp` instead

### Current Analytics Reporters

Prebid Server includes these built-in reporters:
- `LogAnalyticsReporter` - Logs events to application logs
- `PubstackAnalyticsReporter` - Sends to Pubstack
- `AgmaAnalyticsReporter` - Sends to Agma
- `GreenbidsAnalyticsReporter` - Sends to Greenbids
- `LiveIntentAnalyticsReporter` - Sends to LiveIntent

## Creating a Custom Google Cloud Monitoring Reporter

You'll need to create a custom `AnalyticsReporter` implementation that sends data to Google Cloud Monitoring.

### Steps

1. **Create the reporter class** implementing `AnalyticsReporter`
2. **Add Google Cloud Monitoring client dependency**
3. **Configure it in `AnalyticsConfiguration`**
4. **Add configuration properties**

### Example Implementation

Here's a basic structure for a Google Cloud Monitoring reporter:

```java
package org.prebid.server.analytics.reporter.gcp;

import com.google.cloud.monitoring.v3.MetricServiceClient;
import com.google.monitoring.v3.Point;
import com.google.monitoring.v3.TimeSeries;
import com.google.monitoring.v3.TimeInterval;
import io.vertx.core.Future;
import org.prebid.server.analytics.AnalyticsReporter;
import org.prebid.server.analytics.model.AuctionEvent;
import org.prebid.server.analytics.model.NotificationEvent;
import org.prebid.server.auction.model.AuctionContext;

public class GcpMonitoringAnalyticsReporter implements AnalyticsReporter {
    
    private final MetricServiceClient metricServiceClient;
    private final String projectId;
    
    @Override
    public <T> Future<Void> processEvent(T event) {
        if (event instanceof AuctionEvent auctionEvent) {
            return processAuctionEvent(auctionEvent);
        } else if (event instanceof NotificationEvent notificationEvent) {
            return processNotificationEvent(notificationEvent);
        }
        return Future.succeededFuture();
    }
    
    private Future<Void> processAuctionEvent(AuctionEvent event) {
        AuctionContext context = event.getAuctionContext();
        
        // Extract metrics
        String country = extractCountry(context);
        String storedImpId = extractStoredImpId(context);
        boolean servedAd = event.getBidResponse() != null && 
                          !event.getBidResponse().getSeatbid().isEmpty();
        
        // Log metrics to Google Cloud Monitoring
        // - Total incoming requests by stored imp id
        // - By country
        // - Whether ad was served
        
        return Future.succeededFuture();
    }
    
    private Future<Void> processNotificationEvent(NotificationEvent event) {
        // Extract metrics for win/imp events
        // - Bid won (type == win)
        // - Ad rendered (type == imp)
        // - Price (from bidId lookup if stored)
        
        return Future.succeededFuture();
    }
    
    @Override
    public int vendorId() {
        return 0; // Set appropriate vendor ID for TCF
    }
    
    @Override
    public String name() {
        return "gcp-monitoring";
    }
}
```

### Available Data in AuctionEvent

From `AuctionEvent.auctionContext.bidRequest`:
- **Stored Impression IDs**: Available in `imp[].ext.prebid.storedimp.id`
- **Country**: `device.geo.country` or `geoInfo.countryCode`
- **Impression data**: `imp[]` array with all impression details
- **Bidder requests**: All bidder calls made

From `AuctionEvent.bidResponse`:
- **Bids received**: `seatbid[].bid[]` with prices
- **Winning bids**: Can be determined from bid prices
- **Bidder names**: `seatbid[].seat`

### Available Data in NotificationEvent

- **Event type**: `win` or `imp`
- **Bid ID**: `bidId` (can be used to lookup bid details)
- **Bidder**: `bidder` name
- **Account**: `account.id`
- **Timestamp**: `timestamp`

## Prebid JS Implementation

### Events You Need to Implement

1. **onBidWon** ✅ - Already supported via `/event` endpoint (type=win)
   ```javascript
   onBidWon: function(bid) {
       // Prebid Server automatically creates win URLs in bid response
       // They are fired automatically when bid wins
   }
   ```

2. **onAdRenderSucceeded** ✅ - Use `NotificationEvent` type=imp
   ```javascript
   onAdRenderSucceeded: function(bid) {
       // Call Prebid Server /event endpoint with type=imp
       // URL is available in bid.events
   }
   ```

3. **onTimeout** ⚠️ - Need custom endpoint
   ```javascript
   onTimeout: function(data) {
       // You'll need to create a custom endpoint on Prebid Server
       // Or send to your analytics collector
       fetch('/analytics/timeout', {
           method: 'POST',
           body: JSON.stringify({
               bidder: data.bidder,
               bidId: data.bidId,
               storedImpId: data.storedImpId,
               // ... other data
           })
       });
   }
   ```

4. **onBidderError** ⚠️ - Need custom endpoint
   ```javascript
   onBidderError: function({ error, bidderRequest }) {
       fetch('/analytics/bidder-error', {
           method: 'POST',
           body: JSON.stringify({
               bidder: bidderRequest.bidder,
               error: error.message,
               storedImpId: bidderRequest.storedImpId,
               // ... other data
           })
       });
   }
   ```

5. **onSetTargeting** - Client-side only, no server event needed

### Prebid JS Configuration

Configure Prebid JS to enable analytics:

```javascript
pbjs.que.push(function() {
    pbjs.setConfig({
        analytics: {
            adapter: 'prebidServer', // Use Prebid Server adapter
            prebidServerAnalytics: {
                endpoint: 'https://your-prebid-server.com/event'
            }
        }
    });
});
```

## Configuration

Add to your `prebid-config.yaml`:

```yaml
analytics:
  gcp-monitoring:
    enabled: true
    project-id: "your-gcp-project-id"
    # ... other GCP configuration
```

## Recommendations

1. **Use AuctionEvent for server-side metrics**:
   - Total requests by stored imp ID
   - Requests by country
   - Ad served rate
   - Bid prices

2. **Use NotificationEvent for client-side metrics**:
   - Bid wins (already tracked)
   - Ad renders (already tracked)

3. **Create custom endpoints for missing events**:
   - Timeout tracking
   - Bidder error tracking
   - These can be simple HTTP handlers that log to Google Cloud Monitoring

4. **Store bid metadata**:
   - When bids are returned, store metadata (bidId → price, storedImpId, etc.)
   - Use this in NotificationEvent handlers to enrich win/imp events

## Next Steps

1. Implement `GcpMonitoringAnalyticsReporter`
2. Add Google Cloud Monitoring dependencies to `pom.xml`
3. Configure reporter in `AnalyticsConfiguration`
4. Implement custom endpoints for timeout/bidder error if needed
5. Update Prebid JS configuration to send events
