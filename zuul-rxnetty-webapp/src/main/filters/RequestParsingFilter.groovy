import com.netflix.zuul.ZuulFilter
import com.netflix.zuul.context.RequestContext
import io.reactivex.netty.protocol.http.server.HttpRequestHeaders
import io.reactivex.netty.protocol.http.server.HttpServerRequest
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * @author mhawthorne
 */
class RequestParsingFilter extends ZuulFilter {

    // TODO: define logger in base class
    private static final Logger LOG = LoggerFactory.getLogger("RequestParsingFilter");

    @Override
    String filterType() {
        return "pre"
    }

    @Override
    int filterOrder() {
        return 0
    }

    @Override
    boolean shouldFilter(RequestContext ctx) {
        return true
    }

    @Override
    Object run(RequestContext ctx) {
        HttpServerRequest req = ctx.request;
        ctx.path = req.path;

        // copies all Netty request headers into RequestContext
        final HttpRequestHeaders nettyReqHeaders = req.getHeaders();
        final Map<String, String> parsedReqHeaders = ctx.getZuulRequestHeaders();
        for(final String name : nettyReqHeaders.names()) {
            final List<String> vals = nettyReqHeaders.getAll(name);
            if (vals.size() > 1) {
                LOG.warn("More than 1 value for header {}: {}", name, vals);
            }
            parsedReqHeaders.put(name, vals.get(0))
        }
    }

}
