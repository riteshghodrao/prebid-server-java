package org.prebid.server.metric;

import io.micrometer.core.instrument.MeterRegistry;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Account metrics support.
 */
class AccountMetrics extends UpdatableMetrics {

    // not thread-safe maps are intentionally used here because it's harmless in this particular case - eventually
    // this all boils down to metrics lookup by underlying metric registry and that operation is guaranteed to be
    // thread-safe
    private final Function<MetricName, RequestTypeMetrics> requestTypeMetricsCreator;
    private final Map<MetricName, RequestTypeMetrics> requestTypeMetrics;
    private final AdapterMetrics adapterMetrics;
    private final RequestMetrics requestsMetrics;
    private final CacheMetrics cacheMetrics;
    private final ResponseMetrics responseMetrics;
    private final HooksMetrics hooksMetrics;
    private final ActivitiesMetrics activitiesMetrics;

    AccountMetrics(MeterRegistry meterRegistry, CounterType counterType, String account) {
        super(Objects.requireNonNull(meterRegistry), Objects.requireNonNull(counterType),
                nameCreator(createPrefix(Objects.requireNonNull(account))));
        requestTypeMetricsCreator = requestType ->
                new RequestTypeMetrics(meterRegistry, counterType, createPrefix(account), requestType);
        adapterMetrics = new AdapterMetrics(meterRegistry, counterType, createPrefix(account));
        requestTypeMetrics = new HashMap<>();
        requestsMetrics = new RequestMetrics(meterRegistry, counterType, createPrefix(account));
        cacheMetrics = new CacheMetrics(meterRegistry, counterType, createPrefix(account));
        responseMetrics = new ResponseMetrics(meterRegistry, counterType, createPrefix(account));
        hooksMetrics = new HooksMetrics(meterRegistry, counterType, createPrefix(account));
        activitiesMetrics = new ActivitiesMetrics(meterRegistry, counterType, createPrefix(account));
    }

    private static String createPrefix(String account) {
        return "account." + account;
    }

    private static Function<MetricName, String> nameCreator(String prefix) {
        return metricName -> "%s.%s".formatted(prefix, metricName);
    }

    AdapterMetrics adapter() {
        return adapterMetrics;
    }

    RequestTypeMetrics requestType(MetricName requestType) {
        return requestTypeMetrics.computeIfAbsent(requestType, requestTypeMetricsCreator);
    }

    RequestMetrics requests() {
        return requestsMetrics;
    }

    CacheMetrics cache() {
        return cacheMetrics;
    }

    ResponseMetrics response() {
        return responseMetrics;
    }

    HooksMetrics hooks() {
        return hooksMetrics;
    }

    ActivitiesMetrics activities() {
        return activitiesMetrics;
    }
}
