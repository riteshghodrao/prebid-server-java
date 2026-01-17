package org.prebid.server.analytics.reporter.gcpmonitoring.model;

import lombok.Builder;
import lombok.Value;

/**
 * Configuration properties for Google Cloud Monitoring analytics reporter.
 */
@Builder
@Value
public class GcpMonitoringAnalyticsProperties {

    /**
     * GCP Project ID where metrics will be sent.
     */
    String projectId;

    /**
     * Whether the reporter is enabled.
     */
    Boolean enabled;

    /**
     * Optional: Custom metric namespace prefix (defaults to custom.googleapis.com/prebid/)
     */
    String metricPrefix;
}

