import com.netflix.config.DynamicIntProperty
import com.netflix.config.DynamicPropertyFactory
import com.netflix.config.DynamicStringProperty
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

    private static final DynamicPropertyFactory DPF = DynamicPropertyFactory.getInstance();
    private static final DynamicStringProperty ORIGIN_HOST = DPF.getStringProperty("zuul.origin.host", null);
    private static final DynamicIntProperty ORIGIN_PORT = DPF.getIntProperty("zuul.origin.port", 80);

    final HttpClient<ByteBuf, ByteBuf> client;

    OriginRoutingFilter() {
        final String host = ORIGIN_HOST.get();
        final int port = ORIGIN_PORT.get();
        if (host == null) {
            throw new IllegalStateException(ORIGIN_HOST.getName() + " cannot be null");
        }
        client = RxNetty.createHttpClient(ORIGIN_HOST.get(), ORIGIN_PORT.get());
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
