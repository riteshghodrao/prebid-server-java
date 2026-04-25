package org.prebid.server.analytics.reporter.gcpmonitoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.api.MonitoredResource;
import com.google.cloud.monitoring.v3.MetricServiceClient;
import com.google.monitoring.v3.CreateTimeSeriesRequest;
import com.google.monitoring.v3.Point;
import com.google.monitoring.v3.ProjectName;
import com.google.monitoring.v3.TimeInterval;
import com.google.monitoring.v3.TimeSeries;
import com.google.monitoring.v3.TypedValue;
import com.google.protobuf.Timestamp;
import com.iab.openrtb.request.BidRequest;
import com.iab.openrtb.request.Device;
import com.iab.openrtb.request.Geo;
import com.iab.openrtb.request.Imp;
import com.iab.openrtb.response.BidResponse;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import org.apache.commons.collections4.CollectionUtils;
import org.prebid.server.analytics.AnalyticsReporter;
import org.prebid.server.analytics.model.AmpEvent;
import org.prebid.server.analytics.model.AuctionEvent;
import org.prebid.server.analytics.model.CookieSyncEvent;
import org.prebid.server.analytics.model.NotificationEvent;
import org.prebid.server.analytics.model.SetuidEvent;
import org.prebid.server.analytics.model.VideoEvent;
import org.prebid.server.analytics.reporter.gcpmonitoring.model.GcpMonitoringAnalyticsProperties;
import org.prebid.server.auction.model.AuctionContext;
import org.prebid.server.geolocation.model.GeoInfo;
import org.prebid.server.json.JacksonMapper;
import org.prebid.server.log.Logger;
import org.prebid.server.log.LoggerFactory;
import org.prebid.server.vertx.httpclient.HttpClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * {@link AnalyticsReporter} implementation that sends analytics data to Google Cloud Monitoring.
 * Supports custom metrics for tracking auction events, notification events, and bid performance.
 */
public class GcpMonitoringAnalyticsReporter implements AnalyticsReporter {

    private static final Logger logger = LoggerFactory.getLogger(GcpMonitoringAnalyticsReporter.class);

    private static final String METRIC_TYPE_PREFIX = "custom.googleapis.com/prebid/";
    private static final String METRIC_REQUESTS = METRIC_TYPE_PREFIX + "requests";
    private static final String METRIC_BID_PRICE = METRIC_TYPE_PREFIX + "bid_price";
    private static final String METRIC_AD_SERVED = METRIC_TYPE_PREFIX + "ad_served";
    private static final String METRIC_BID_WON = METRIC_TYPE_PREFIX + "bid_won";
    private static final String METRIC_AD_RENDERED = METRIC_TYPE_PREFIX + "ad_rendered";

    private final GcpMonitoringAnalyticsProperties properties;
    private final HttpClient httpClient;
    private final JacksonMapper mapper;
    private final Vertx vertx;
    private volatile MetricServiceClient metricServiceClient;
    private volatile boolean clientInitialized;

    public GcpMonitoringAnalyticsReporter(GcpMonitoringAnalyticsProperties properties,
                                          HttpClient httpClient,
                                          JacksonMapper mapper,
                                          Vertx vertx) {
        this.properties = Objects.requireNonNull(properties);
        this.httpClient = Objects.requireNonNull(httpClient);
        this.mapper = Objects.requireNonNull(mapper);
        this.vertx = Objects.requireNonNull(vertx);
        this.clientInitialized = false;
    }

    @Override
    public <T> Future<Void> processEvent(T event) {
        return switch (event) {
            case AuctionEvent auctionEvent -> processAuctionEvent(auctionEvent);
            case NotificationEvent notificationEvent -> processNotificationEvent(notificationEvent);
            case AmpEvent ampEvent -> processAuctionEvent(toAuctionEvent(ampEvent));
            case VideoEvent videoEvent -> processAuctionEvent(toAuctionEvent(videoEvent));
            case CookieSyncEvent cookieSyncEvent -> Future.succeededFuture(); // Not tracking for now
            case SetuidEvent setuidEvent -> Future.succeededFuture(); // Not tracking for now
            case null, default -> Future.succeededFuture();
        };
    }

    private Future<Void> processAuctionEvent(AuctionEvent event) {
        try {
            final AuctionContext auctionContext = event.getAuctionContext();
            final BidResponse bidResponse = event.getBidResponse();

            if (auctionContext == null || auctionContext.getBidRequest() == null) {
                return Future.succeededFuture();
            }

            final BidRequest bidRequest = auctionContext.getBidRequest();
            final List<MetricPoint> metricPoints = new ArrayList<>();

            // Extract country
            final String country = extractCountry(bidRequest, auctionContext);

            // Extract stored impression IDs from impressions
            final List<String> storedImpIds = extractStoredImpIds(bidRequest);

            // Process each impression
            if (CollectionUtils.isNotEmpty(bidRequest.getImp())) {
                for (Imp imp : bidRequest.getImp()) {
                    final String storedImpId = extractStoredImpId(imp);

                    // Track incoming requests by stored imp ID and country
                    final Map<String, String> requestLabels = new HashMap<>();
                    requestLabels.put("stored_imp_id", storedImpId != null ? storedImpId : "unknown");
                    requestLabels.put("country", country != null ? country : "unknown");
                    requestLabels.put("account_id", auctionContext.getAccount() != null
                            ? auctionContext.getAccount().getId() : "unknown");

                    metricPoints.add(createMetricPoint(METRIC_REQUESTS, 1L, requestLabels));

                    // Check if ad was served for this impression
                    final boolean adServed = isAdServedForImp(imp, bidResponse);
                    if (adServed) {
                        final Map<String, String> adServedLabels = new HashMap<>(requestLabels);
                        metricPoints.add(createMetricPoint(METRIC_AD_SERVED, 1L, adServedLabels));

                        // Extract winning bid price
                        final Optional<Double> winningPrice = getWinningBidPrice(imp, bidResponse);
                        winningPrice.ifPresent(price -> {
                            final Map<String, String> priceLabels = new HashMap<>(requestLabels);
                            metricPoints.add(createMetricPoint(METRIC_BID_PRICE, price, priceLabels));
                        });
                    }
                }
            }

            // Send metrics to Cloud Monitoring
            if (CollectionUtils.isNotEmpty(metricPoints)) {
                return sendMetricsToCloudMonitoring(metricPoints);
            }

            return Future.succeededFuture();
        } catch (Exception e) {
            logger.warn("Failed to process auction event for GCP Monitoring: {}", e.getMessage());
            return Future.succeededFuture(); // Don't fail the request if analytics fails
        }
    }

    private Future<Void> processNotificationEvent(NotificationEvent event) {
        try {
            final List<MetricPoint> metricPoints = new ArrayList<>();

            final Map<String, String> labels = new HashMap<>();
            labels.put("bid_id", event.getBidId() != null
                    ? event.getBidId() : "unknown");
            labels.put("bidder", event.getBidder() != null
                    ? event.getBidder() : "unknown");
            labels.put("account_id", event.getAccount() != null
                    ? event.getAccount().getId() : "unknown");
            labels.put("integration", event.getIntegration() != null
                    ? event.getIntegration() : "unknown");

            enrichLabelsFromQueryParams(event, labels);

            if (event.getType() == NotificationEvent.Type.win) {
                metricPoints.add(createMetricPoint(
                        METRIC_BID_WON, 1L, labels));
            } else if (event.getType() == NotificationEvent.Type.imp) {
                metricPoints.add(createMetricPoint(
                        METRIC_AD_RENDERED, 1L, labels));
            }

            if (CollectionUtils.isNotEmpty(metricPoints)) {
                return sendMetricsToCloudMonitoring(metricPoints);
            }

            return Future.succeededFuture();
        } catch (Exception e) {
            logger.warn(
                    "Failed to process notification event"
                            + " for GCP Monitoring: {}",
                    e.getMessage());
            return Future.succeededFuture();
        }
    }

    private static void enrichLabelsFromQueryParams(
            NotificationEvent event, Map<String, String> labels) {

        if (event.getHttpContext() == null
                || event.getHttpContext().getQueryParams() == null) {
            return;
        }

        final var queryParams = event.getHttpContext().getQueryParams();

        final String tag = queryParams.get("tag");
        if (tag != null && !tag.isBlank()) {
            labels.put("stored_imp_id", tag);
        }

        final String size = queryParams.get("size");
        if (size != null && !size.isBlank()) {
            labels.put("size", size);
        }

        final String price = queryParams.get("p");
        if (price != null && !price.isBlank()) {
            labels.put("price", price);
        }

        final String mtype = queryParams.get("mtype");
        if (mtype != null && !mtype.isBlank()) {
            labels.put("media_type", mtype);
        }
    }

    private Future<Void> sendMetricsToCloudMonitoring(List<MetricPoint> metricPoints) {
        final String projectId = properties.getProjectId();
        if (projectId == null || projectId.isBlank()) {
            logger.warn("GCP Monitoring project ID not configured, skipping metric upload");
            return Future.succeededFuture();
        }

        if (CollectionUtils.isEmpty(metricPoints)) {
            return Future.succeededFuture();
        }

        // Initialize client lazily (runs on background thread to avoid blocking)
        return ensureMetricServiceClient()
                .compose(ignored -> {
                    try {
                        final ProjectName projectName = ProjectName.of(projectId);
                        final List<TimeSeries> timeSeriesList = convertToTimeSeries(metricPoints, projectId);

                        if (CollectionUtils.isEmpty(timeSeriesList)) {
                            return Future.succeededFuture();
                        }

                        final CreateTimeSeriesRequest request = CreateTimeSeriesRequest.newBuilder()
                                .setName(projectName.toString())
                                .addAllTimeSeries(timeSeriesList)
                                .build();

                        // Execute on worker thread to avoid blocking event loop
                        final Promise<Void> promise = Promise.promise();
                        vertx.executeBlocking(
                                blockingPromise -> {
                                    try {
                                        metricServiceClient.createTimeSeries(request);
                                        blockingPromise.complete();
                                    } catch (Exception e) {
                                        blockingPromise.fail(e);
                                    }
                                },
                                false,
                                result -> {
                                    if (result.succeeded()) {
                                        promise.complete();
                                    } else {
                                        logger.warn("Failed to send metrics to GCP Monitoring: {}",
                                                result.cause().getMessage());
                                        promise.complete(); // Don't fail the request if analytics fails
                                    }
                                });

                        return promise.future();
                    } catch (Exception e) {
                        logger.warn("Failed to create time series request for GCP Monitoring: {}", e.getMessage());
                        return Future.succeededFuture(); // Don't fail the request if analytics fails
                    }
                })
                .otherwise(throwable -> {
                    logger.warn("Error in GCP Monitoring metric upload: {}", throwable.getMessage());
                    return null; // Don't fail the request if analytics fails
                });
    }

    private Future<Void> ensureMetricServiceClient() {
        if (clientInitialized && metricServiceClient != null) {
            return Future.succeededFuture();
        }

        // Initialize on worker thread to avoid blocking event loop
        final Promise<Void> promise = Promise.promise();
        vertx.executeBlocking(
                blockingPromise -> {
                    try {
                        synchronized (this) {
                            if (!clientInitialized) {
                                metricServiceClient = MetricServiceClient.create();
                                clientInitialized = true;
                                logger.info("GCP Monitoring MetricServiceClient initialized successfully");
                            }
                        }
                        blockingPromise.complete();
                    } catch (Exception e) {
                        logger.warn("Failed to initialize GCP Monitoring MetricServiceClient: {}. "
                                        + "Metrics will not be sent. Ensure Application Default Credentials are "
                                        + "configured.",
                                e.getMessage());
                        // Still complete to avoid blocking, but metrics won't be sent
                        blockingPromise.complete();
                    }
                },
                false,
                result -> {
                    if (result.succeeded()) {
                        promise.complete();
                    } else {
                        logger.warn("Error initializing GCP Monitoring client: {}", result.cause().getMessage());
                        promise.complete(); // Don't fail the request if analytics fails
                    }
                });

        return promise.future();
    }

    private List<TimeSeries> convertToTimeSeries(List<MetricPoint> metricPoints, String projectId) {
        final List<TimeSeries> timeSeriesList = new ArrayList<>();
        final long currentTimeMillis = System.currentTimeMillis();
        final Timestamp timestamp = Timestamp.newBuilder()
                .setSeconds(currentTimeMillis / 1000)
                .setNanos((int) ((currentTimeMillis % 1000) * 1000000))
                .build();

        final TimeInterval interval = TimeInterval.newBuilder()
                .setEndTime(timestamp)
                .build();

        // Get instance ID from metadata (for GCE instances)
        // On GCE, this is available from metadata service
        // For now, we'll use a generic resource type
        final MonitoredResource resource = MonitoredResource.newBuilder()
                .setType("gce_instance")
                // Instance ID and zone can be added if available from metadata
                .build();

        for (MetricPoint metricPoint : metricPoints) {
            try {
                // Create metric descriptor path
                final com.google.api.Metric metric = com.google.api.Metric.newBuilder()
                        .setType(metricPoint.getMetricType())
                        .putAllLabels(metricPoint.getLabels())
                        .build();

                // Create typed value
                final TypedValue value;
                if ("int64Value".equals(metricPoint.getValueType())) {
                    value = TypedValue.newBuilder()
                            .setInt64Value(metricPoint.getValue().longValue())
                            .build();
                } else {
                    value = TypedValue.newBuilder()
                            .setDoubleValue(metricPoint.getValue().doubleValue())
                            .build();
                }

                // Create point
                final Point point = Point.newBuilder()
                        .setInterval(interval)
                        .setValue(value)
                        .build();

                // Create time series
                final TimeSeries timeSeries = TimeSeries.newBuilder()
                        .setMetric(metric)
                        .setResource(resource)
                        .addPoints(point)
                        .build();

                timeSeriesList.add(timeSeries);
            } catch (Exception e) {
                logger.warn("Failed to convert metric point to TimeSeries: {}", e.getMessage());
            }
        }

        return timeSeriesList;
    }

    private String extractCountry(BidRequest bidRequest, AuctionContext auctionContext) {
        // Try GeoInfo first
        final GeoInfo geoInfo = auctionContext.getGeoInfo();
        if (geoInfo != null && geoInfo.getCountry() != null) {
            return geoInfo.getCountry();
        }

        // Try Device.geo
        final Device device = bidRequest.getDevice();
        if (device != null) {
            final Geo geo = device.getGeo();
            if (geo != null && geo.getCountry() != null) {
                return geo.getCountry();
            }
        }

        return null;
    }

    private List<String> extractStoredImpIds(BidRequest bidRequest) {
        if (CollectionUtils.isEmpty(bidRequest.getImp())) {
            return Collections.emptyList();
        }

        return bidRequest.getImp().stream()
                .map(this::extractStoredImpId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private String extractStoredImpId(Imp imp) {
        if (imp == null || imp.getExt() == null) {
            return null;
        }

        try {
            final JsonNode extNode = imp.getExt();
            final JsonNode prebidNode = extNode.get("prebid");

            if (prebidNode != null && prebidNode.isObject()) {
                // Check for storedimp.id (stored impression reference)
                final JsonNode storedImpNode = prebidNode.get("storedimp");
                if (storedImpNode != null && storedImpNode.isObject()) {
                    final JsonNode idNode = storedImpNode.get("id");
                    if (idNode != null && idNode.isTextual()) {
                        return idNode.textValue();
                    }
                }

                // Fallback: check storedrequest.id (stored request reference)
                final JsonNode storedRequestNode = prebidNode.get("storedrequest");
                if (storedRequestNode != null && storedRequestNode.isObject()) {
                    final JsonNode idNode = storedRequestNode.get("id");
                    if (idNode != null && idNode.isTextual()) {
                        return idNode.textValue();
                    }
                }
            }
        } catch (Exception e) {
            // Ignore parsing errors
        }

        return null;
    }

    private boolean isAdServedForImp(Imp imp, BidResponse bidResponse) {
        if (bidResponse == null || CollectionUtils.isEmpty(bidResponse.getSeatbid())) {
            return false;
        }

        return bidResponse.getSeatbid().stream()
                .anyMatch(seatBid -> CollectionUtils.isNotEmpty(seatBid.getBid())
                        && seatBid.getBid().stream()
                                .anyMatch(bid -> Objects.equals(bid.getImpid(), imp.getId())));
    }

    private Optional<Double> getWinningBidPrice(Imp imp, BidResponse bidResponse) {
        if (bidResponse == null || CollectionUtils.isEmpty(bidResponse.getSeatbid())) {
            return Optional.empty();
        }

        return bidResponse.getSeatbid().stream()
                .flatMap(seatBid -> CollectionUtils.emptyIfNull(seatBid.getBid()).stream())
                .filter(bid -> Objects.equals(bid.getImpid(), imp.getId()))
                .max((b1, b2) -> b1.getPrice().compareTo(b2.getPrice()))
                .map(bid -> bid.getPrice().doubleValue());
    }

    private MetricPoint createMetricPoint(String metricType, Number value, Map<String, String> labels) {
        final String valueType = value instanceof Long ? "int64Value" : "doubleValue";
        return MetricPoint.builder()
                .metricType(metricType)
                .value(value)
                .valueType(valueType)
                .labels(labels != null ? new HashMap<>(labels) : Collections.emptyMap())
                .build();
    }

    private AuctionEvent toAuctionEvent(AmpEvent ampEvent) {
        return AuctionEvent.builder()
                .auctionContext(ampEvent.getAuctionContext())
                .bidResponse(ampEvent.getBidResponse())
                .httpContext(ampEvent.getHttpContext())
                .status(ampEvent.getStatus())
                .errors(ampEvent.getErrors())
                .build();
    }

    private AuctionEvent toAuctionEvent(VideoEvent videoEvent) {
        // VideoEvent has VideoResponse, not BidResponse
        // Get BidResponse from auctionContext instead
        final AuctionContext auctionContext = videoEvent.getAuctionContext();
        final BidResponse bidResponse = auctionContext != null ? auctionContext.getBidResponse() : null;

        return AuctionEvent.builder()
                .auctionContext(auctionContext)
                .bidResponse(bidResponse)
                .httpContext(videoEvent.getHttpContext())
                .status(videoEvent.getStatus())
                .errors(videoEvent.getErrors())
                .build();
    }

    @Override
    public int vendorId() {
        return 0; // Set appropriate vendor ID for TCF if needed
    }

    @Override
    public String name() {
        return "gcp-monitoring";
    }
}

