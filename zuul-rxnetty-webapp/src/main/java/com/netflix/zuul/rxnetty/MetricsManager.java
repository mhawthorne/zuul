package com.netflix.zuul.rxnetty;

import com.netflix.numerus.NumerusProperty;
import com.netflix.numerus.NumerusRollingNumber;
import com.netflix.numerus.NumerusRollingNumberEvent;
import com.netflix.numerus.NumerusRollingPercentile;
import com.netflix.servo.monitor.Counter;
import com.netflix.servo.monitor.MonitorConfig;
import com.netflix.servo.monitor.Monitors;
import com.netflix.servo.monitor.NumberGauge;
import com.netflix.servo.monitor.ResettableCounter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author mhawthorne
 */
public class MetricsManager {

    private enum Event implements NumerusRollingNumberEvent {

        REQUESTS;

        @Override
        public boolean isCounter() {
            return true;
        }

        @Override
        public boolean isMaxUpdater() {
            return false;
        }

        @Override
        public NumerusRollingNumberEvent[] getValues() {
            return values();
        }

    }

    private static final Logger LOG = LoggerFactory.getLogger(MetricsManager.class);

    private static final MetricsManager INSTANCE = new MetricsManager();

    public static final MetricsManager instance() {
        return INSTANCE;
    }

    private final AtomicInteger concurrentRequests = new AtomicInteger();

    private final NumberGauge concurrentRequestsGauge =
        new NumberGauge(MonitorConfig.builder("concurrentRequests").build(), concurrentRequests);

    private final ResettableCounter requestCount = new ResettableCounter(MonitorConfig.builder("requestCount").build());

    private static final int rollingSeconds = 5;

    private final ConcurrentHashMap<String, Long> requestStarts = new ConcurrentHashMap<String, Long>();

    private final NumerusRollingNumber counter = new NumerusRollingNumber(Event.REQUESTS,
            NumerusProperty.Factory.asProperty(rollingSeconds * 1000),
            NumerusProperty.Factory.asProperty(10));

    private final NumerusRollingPercentile latency =
        new NumerusRollingPercentile(NumerusProperty.Factory.asProperty(rollingSeconds * 1000),
            NumerusProperty.Factory.asProperty(10),
            NumerusProperty.Factory.asProperty(1000),
            NumerusProperty.Factory.asProperty(Boolean.TRUE));

    public void startRequest(String requestId) {
        this.concurrentRequests.incrementAndGet();
        requestCount.increment();

        // safety check for memory leaks
        // not sure why endRequest() wouldn't be called, but who knows
        if(this.requestStarts.size() > 10000) {
            this.requestStarts.clear();
        }

        this.requestStarts.put(requestId, System.currentTimeMillis());
    }

    public void endRequest(String requestId) {
        // since I am hacking this number in reset() I only decrement if it is greater than zero
        // the risk is that I reset the concurrent request counter while I still have live requests
        if(this.concurrentRequests.get() > 0) this.concurrentRequests.decrementAndGet();

        this.counter.increment(Event.REQUESTS);

        final long requestStart = this.requestStarts.remove(requestId);
        if(requestStart > 0) {
            final long latency = System.currentTimeMillis() - requestStart;
            this.latency.addValue((int)latency);
        }
    }

    public Map<String, Object> getAll() {
        final Map<String, Object> m = new HashMap<String, Object>();

        m.put("rps", this.getRequestRate());
        m.put("concurrentRequests", this.getConcurrentRequests());
        m.put("requestStartsInFlight", this.requestStarts.size());
        m.put("latency50", this.latency.getPercentile(50));
        m.put("latency90", this.latency.getPercentile(90));
        m.put("latency99", this.latency.getPercentile(99));

        return m;
    }

    public Number getRequestRate() {
//        return requestCount.getValue();
        return this.counter.getRollingSum(Event.REQUESTS);
    }

    public Number getConcurrentRequests() {
//       return concurrentRequestsGauge.getValue();
        return concurrentRequests.get();
    }

    public void reset() {
        requestCount.getAndResetValue();

        // I am attempting to decay the concurrent request count instead of resetting to zero
        // I acknowledge this is a weird approach but it is a decent hack to give me decent numbers during load testing
        // the root cause is why endRequest() isn't being called reliably
//        final int requests = concurrentRequests.get();
//        final int requestsResetVal = (int) Math.ceil(requests * 0.5);
//        if (requestsResetVal != requests) LOG.debug("resetting concurrent requests {}->{}", requests, requestsResetVal);
//        concurrentRequests.set(requestsResetVal);
    }

}
