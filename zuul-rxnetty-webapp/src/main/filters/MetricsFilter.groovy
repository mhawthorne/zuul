import com.netflix.numerus.NumerusProperty
import com.netflix.numerus.NumerusRollingNumber
import com.netflix.numerus.NumerusRollingPercentile
import com.netflix.zuul.ZuulFilter
import com.netflix.zuul.context.RequestContext
import com.netflix.zuul.rxnetty.MetricsManager
import com.netflix.zuul2.ZuulRequestContext
import com.netflix.zuul2.ZuulSimpleFilter
import io.reactivex.netty.client.PoolStats
import io.reactivex.netty.protocol.http.server.HttpServerRequest
import org.codehaus.jackson.map.ObjectMapper
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.util.concurrent.atomic.AtomicReference

/**
 * @author mhawthorne
 */
class MetricsFilter extends ZuulSimpleFilter {

    private static final Logger LOG = LoggerFactory.getLogger("MetricsFilter");

    private final ObjectMapper jsonMapper = new ObjectMapper();

    private final AtomicReference summaryLock = new AtomicReference();

    private long lastSummaryMillis;

    @Override
    int filterOrder() {
        return 1
    }

    @Override
    String filterType() {
        return "post"
    }

    @Override
    boolean shouldFilter(ZuulRequestContext ctx) {
        return true
    }

    @Override
    Object run(ZuulRequestContext ctx) {
        final HttpServerRequest req = ctx.request

        final MetricsManager metrics = MetricsManager.instance();

        // only logs each request if the rate is low
//        if (metrics.getRequestRate() < 5) {
//            LOG.info("{} {} {}", Thread.currentThread().getName(), ctx.pathAndQuery, ctx.responseStatusCode);
//        }

        final long currentMillis = System.currentTimeMillis();

        if((currentMillis - lastSummaryMillis) > 2000) {
            // only one thread should win
            if (summaryLock.compareAndSet(summaryLock.get(), new Object())) {
                lastSummaryMillis = currentMillis;

                final PoolStats poolStats = ctx.poolStats;

                final Map<String, Object> stats = metrics.getAll();
                stats.put("connsTotal", poolStats.getTotalConnectionCount());
                stats.put("connsInUse", poolStats.getInUseCount());
                stats.put("connsIdle", poolStats.getIdleCount());

                final String statsJson = this.jsonMapper.writeValueAsString(stats);
                LOG.info(statsJson);
                metrics.reset();
            }
        }
    }

}
