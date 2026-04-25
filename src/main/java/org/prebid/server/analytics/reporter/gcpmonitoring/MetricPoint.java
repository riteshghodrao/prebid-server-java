package org.prebid.server.analytics.reporter.gcpmonitoring;

import lombok.Builder;
import lombok.Value;

import java.util.Map;

/**
 * Internal class representing a metric point to be sent to Google Cloud Monitoring.
 */
@Builder
@Value
class MetricPoint {

    /**
     * The metric type (e.g., custom.googleapis.com/prebid/requests)
     */
    String metricType;

    /**
     * The metric value
     */
    Number value;

    /**
     * The value type (int64Value or doubleValue)
     */
    String valueType;

    /**
     * Metric labels for filtering and grouping
     */
    Map<String, String> labels;
}

