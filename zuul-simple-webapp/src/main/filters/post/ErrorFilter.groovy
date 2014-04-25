package post

import com.netflix.zuul.ZuulFilter
import com.netflix.zuul.context.RequestContext

/**
 * @author mhawthorne
 */
class ErrorFilter extends ZuulFilter {

    @Override
    int filterOrder() {
        return 0
    }

    @Override
    String filterType() {
        return "error"
    }

    @Override
    boolean shouldFilter() {
        return true
    }

    @Override
    Object run() {
        RequestContext ctx = RequestContext.getCurrentContext()
        ctx.setResponseStatusCode(500)

        Throwable t = ctx.getThrowable()
        if (t != null) {
            ctx.setResponseBody(t.toString())
        }
    }

}
