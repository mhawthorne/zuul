import com.netflix.zuul.ZuulAsyncFilter
import com.netflix.zuul.context.RequestContext
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
    boolean shouldFilter(RequestContext ctx) {
        return true
    }

    @Override
    Observable toObservable(RequestContext ctx) {
        return Observable.create(new Observable.OnSubscribe<Subscriber>() {
            @Override
            void call(Subscriber sub) {
                final String path = ctx.path;
                if (path == null) path = "/"

                final Map<String, String> reqHeaders = ctx.getZuulRequestHeaders();

                final HttpClientRequest originReq = HttpClientRequest.createGet(path);
                for(final String name : reqHeaders.keySet()) {
                    originReq.withHeader(name, reqHeaders.get(name));
                }

                final Observable<HttpServerResponse<ByteBuf>> res = client.submit(originReq);
                ctx.originResponse = res;

//                        .flatMap(new Func1<HttpClientResponse<ByteBuf>, Observable<ByteBuf>>() {
//                    @Override
//                    Observable<ByteBuf> call(HttpClientResponse<ByteBuf> res) {
//                        ctx.originResponse = res;
//                        return res.getContent();
//                    }
//                });

//                .doOnCompleted(new Action0() {
//
//                    @Override
//                    void call() {
//                        boolean b = false;
//                    }
//                }).subscribe(sub);

//                        .map(data -> "Client => " + data.toString(Charset.defaultCharset()))

                sub.onCompleted();
            }
        });
    }

}
