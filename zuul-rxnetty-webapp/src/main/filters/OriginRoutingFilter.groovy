import com.netflix.config.DynamicIntProperty
import com.netflix.config.DynamicPropertyFactory
import com.netflix.config.DynamicStringProperty
import com.netflix.zuul2.ZuulObservableFilter
import com.netflix.zuul2.ZuulRequestContext
import io.netty.buffer.ByteBuf
import io.reactivex.netty.RxNetty
import io.reactivex.netty.protocol.http.client.HttpClient
import io.reactivex.netty.protocol.http.client.HttpClientBuilder
import io.reactivex.netty.protocol.http.client.HttpClientRequest
import io.reactivex.netty.protocol.http.server.HttpServerBuilder
import io.reactivex.netty.protocol.http.server.HttpServerResponse
import rx.Observable
import rx.Subscriber
/**
 * @author mhawthorne
 */
public class OriginRoutingFilter extends ZuulObservableFilter {

    private static final DynamicPropertyFactory DPF = DynamicPropertyFactory.getInstance();
    private static final DynamicStringProperty ORIGIN_HOST = DPF.getStringProperty("zuul.origin.host", null);
    private static final DynamicIntProperty ORIGIN_PORT = DPF.getIntProperty("zuul.origin.port", 80);
    private static final DynamicIntProperty ORIGIN_MAX_CONNS = DPF.getIntProperty("zuul.origin.max-connections", 0);


    final HttpClient<ByteBuf, ByteBuf> client;

    OriginRoutingFilter() {
        final String host = ORIGIN_HOST.get();
        final int port = ORIGIN_PORT.get();
        if (host == null) {
            throw new IllegalStateException(ORIGIN_HOST.getName() + " cannot be null");
        }
        final HttpClientBuilder<ByteBuf, ByteBuf> builder =
            new HttpClientBuilder<ByteBuf, ByteBuf>(ORIGIN_HOST.get(), ORIGIN_PORT.get())
        final int maxConns = ORIGIN_MAX_CONNS.get();
        if (maxConns > 0) builder = builder.withMaxConnections(maxConns)
        client = builder.build();
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
                ctx.poolStats = client.getStats();

                final String path = ctx.path;

                final StringBuilder pathBuilder = new StringBuilder();
                if (path == null) path = ""
                pathBuilder.append(path != null ? path : "")

                if(ctx.query != null)
                    pathBuilder.append("?" + ctx.query)


                final Map<String, String> reqHeaders = ctx.zuulRequestHeaders;

                final HttpClientRequest originReq = HttpClientRequest.createGet(pathBuilder.toString());
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
