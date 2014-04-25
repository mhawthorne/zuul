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

    public static final void log(String requestId, String eventMsg) {
        if (Sampler.shouldSample(requestId.hashCode(), SAMPLE_PERCENTAGE.get())) {
            if(LOG.isDebugEnabled()) {
                LOG.debug("{} {}", requestId, eventMsg);
            }
        }
    }

}
