import com.netflix.zuul2.ZuulAsyncFilter
import com.netflix.zuul.context.RequestContext
import com.netflix.zuul2.ZuulRequestContext
import io.netty.buffer.ByteBuf
import io.reactivex.netty.RxNetty
import io.reactivex.netty.protocol.http.client.HttpClient
import io.reactivex.netty.protocol.http.client.HttpClientRequest
import io.reactivex.netty.protocol.http.server.HttpServerResponse
import rx.Observable
import rx.Subscriber
/**
 * @author mhawthorne
 */
public class OriginRoutingFilter extends ZuulAsyncFilter {

    final HttpClient<ByteBuf, ByteBuf> client;

    OriginRoutingFilter() {
        client = RxNetty.createHttpClient("api.test.netflix.com", 80);
    }

    @Override
    String filterType() {
        return "route"
    }

    @Override
    int filterOrder() {
        return 1
    }

    @Override
    boolean shouldFilter(ZuulRequestContext ctx) {
        return true
    }

    @Override
    Observable toObservable(ZuulRequestContext ctx) {
        return Observable.create(new Observable.OnSubscribe<Subscriber>() {
            @Override
            void call(Subscriber sub) {
                final String path = ctx.path;
                if (path == null) path = "/"

                final Map<String, String> reqHeaders = ctx.zuulRequestHeaders;

                final HttpClientRequest originReq = HttpClientRequest.createGet(path);
                for(final String name : reqHeaders.keySet()) {
                    originReq.withHeader(name, reqHeaders.get(name));
                }

                final Observable<HttpServerResponse<ByteBuf>> res = client.submit(originReq);
                ctx.originResponse = res;

                // why do I need this?
                sub.onCompleted();
            }
        });
    }

}
