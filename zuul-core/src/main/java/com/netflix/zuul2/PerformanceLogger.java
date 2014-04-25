package com.netflix.zuul2;

import com.netflix.config.DynamicBooleanProperty;
import com.netflix.config.DynamicPropertyFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * @author mhawthorne
 */
public class PerformanceLogger {

    private static final DynamicBooleanProperty PERF_LOG_ENABLED =
        DynamicPropertyFactory.getInstance().getBooleanProperty("zuul.perf-log-enabled", false);

    private static final Logger LOG = LoggerFactory.getLogger(PerformanceLogger.class);

    private static final PerformanceLogger INSTANCE = new PerformanceLogger();

    private final ConcurrentMap<String, Long> timers = new ConcurrentHashMap<String, Long>();

    private PerformanceLogger() {}

    public static final PerformanceLogger instance() {
        return INSTANCE;
    }

    public void start(String uuid, String name) {
        if(!PERF_LOG_ENABLED.get()) return;

        final String key = buildKey(uuid, name);
        if (this.timers.containsKey(key))
            throw new IllegalStateException("timer already exists: " + key);
        this.timers.put(key, System.currentTimeMillis());
    }

    public void stop(String uuid, String name) {
        if(!PERF_LOG_ENABLED.get()) return;

        final String key = buildKey(uuid, name);
        final Long start = this.timers.remove(key);
        if (start == null)
            throw new IllegalStateException("timer not found: " + key);

        final long elapsed = System.currentTimeMillis() - start;
        if(LOG.isDebugEnabled()) {
            LOG.debug("{}ms {}", elapsed, key);
        }
    }

    private static final String buildKey(String uuid, String name) {
        return uuid + " " + name;
    }

}
