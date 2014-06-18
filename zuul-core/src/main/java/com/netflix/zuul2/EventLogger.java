package com.netflix.zuul2;

import com.netflix.config.DynamicIntProperty;
import com.netflix.config.DynamicPropertyFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author mhawthorne
 */
public class EventLogger {

    private static final Logger LOG = LoggerFactory.getLogger(EventLogger.class);

    private static final DynamicIntProperty SAMPLE_PERCENTAGE =
        DynamicPropertyFactory.getInstance().getIntProperty("zuul.event-log.sample-percentage", 0);

    private static final DynamicIntProperty SAMPLE_PERMYRIAD =
        DynamicPropertyFactory.getInstance().getIntProperty("zuul.event-log.sample-permyriad", 0);

    public static final void log(String requestId, String eventMsg) {
        final int permyriad = SAMPLE_PERMYRIAD.get();
        final int percentage = SAMPLE_PERCENTAGE.get();

        final int requestHash = requestId.hashCode();

        boolean shouldSample = false;

        if (permyriad > 0) {
            shouldSample = Sampler.shouldSampleByPermyriad(requestHash, permyriad);
        } else if (percentage > 0) {
            shouldSample = Sampler.shouldSampleByPercentage(requestHash, percentage);
        }

        if (shouldSample) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("{} {}", requestId, eventMsg);
            }
        }
    }
}
