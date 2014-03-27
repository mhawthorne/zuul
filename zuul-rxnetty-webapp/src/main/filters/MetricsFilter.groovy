import com.netflix.zuul.ZuulFilter
import com.netflix.zuul.context.RequestContext
import com.netflix.zuul2.ZuulRequestContext
import com.netflix.zuul2.ZuulSimpleFilter
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * @author mhawthorne
 */
class MetricsFilter extends ZuulSimpleFilter {

    private static final Logger LOG = LoggerFactory.getLogger("MetricsFilter");

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
        LOG.info("{s} {s}", ctx.path, ctx.responseStatusCode);
    }

}
