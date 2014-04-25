import com.netflix.zuul.ZuulFilter
import com.netflix.zuul.context.RequestContext
import com.netflix.zuul.rxnetty.MetricsManager
import com.netflix.zuul2.ZuulRequestContext
import com.netflix.zuul2.ZuulSimpleFilter
import io.reactivex.netty.protocol.http.server.HttpServerRequest
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.util.concurrent.atomic.AtomicReference

/**
 * @author mhawthorne
 */
class MetricsFilter extends ZuulSimpleFilter {

    private static final Logger LOG = LoggerFactory.getLogger("MetricsFilter");

    private long lastSummaryMillis;

    private AtomicReference summaryLock = new AtomicReference();

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
        if (metrics.getRequestRate() < 5) {
            LOG.info("{} {} {}", Thread.currentThread().getName(), ctx.pathAndQuery, ctx.responseStatusCode);
        }

        final long currentMillis = System.currentTimeMillis();

        if((currentMillis - lastSummaryMillis) > 1000) {
            // only one thread should win
            if (summaryLock.compareAndSet(summaryLock.get(), new Object())) {
                lastSummaryMillis = currentMillis;


                LOG.info("{} req/sec; {} concurrent requests",
                    String.format("%.1f", metrics.getRequestRate()),
                    metrics.getConcurrentRequests());
                metrics.reset();
            }
        }
    }

}
