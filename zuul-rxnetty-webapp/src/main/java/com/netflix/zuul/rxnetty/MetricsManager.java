package com.netflix.zuul.rxnetty;

import com.netflix.servo.monitor.Counter;
import com.netflix.servo.monitor.MonitorConfig;
import com.netflix.servo.monitor.Monitors;
import com.netflix.servo.monitor.NumberGauge;
import com.netflix.servo.monitor.ResettableCounter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author mhawthorne
 */
public class MetricsManager {

    private static final Logger LOG = LoggerFactory.getLogger(MetricsManager.class);

    private static final MetricsManager INSTANCE = new MetricsManager();

    public static final MetricsManager instance() {
        return INSTANCE;
    }

    private final AtomicInteger concurrentRequests = new AtomicInteger();

    private final NumberGauge concurrentRequestsGauge =
        new NumberGauge(MonitorConfig.builder("concurrentRequests").build(), concurrentRequests);

    private final ResettableCounter requestCount = new ResettableCounter(MonitorConfig.builder("requestCount").build());

    public void startRequest() {
        this.concurrentRequests.incrementAndGet();
        requestCount.increment();
    }

    public void endRequest() {
        // since I am hacking this number in reset() I only decrement if it is greater than zero
        // the risk is that I reset the concurrent request counter while I still have live requests
        if(this.concurrentRequests.get() > 0) this.concurrentRequests.decrementAndGet();
    }

    public Number getRequestRate() {
        return requestCount.getValue();
    }

    public Number getConcurrentRequests() {
        return concurrentRequestsGauge.getValue();
    }

    public void reset() {
        requestCount.getAndResetValue();

        // I am attempting to decay the concurrent request count instead of resetting to zero
        // I acknowledge this is a weird approach but it is a decent hack to give me decent numbers during load testing
        // the root cause is why endRequest() isn't being called reliably
        final int requests = concurrentRequests.get();
        final int requestsResetVal = (int) Math.ceil(requests * 0.5);
        if (requestsResetVal != requests) LOG.debug("resetting concurrent requests {}->{}", requests, requestsResetVal);
        concurrentRequests.set(requestsResetVal);
    }

}
