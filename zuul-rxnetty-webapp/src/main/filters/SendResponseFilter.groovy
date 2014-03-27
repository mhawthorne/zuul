import com.netflix.zuul.ZuulAsyncFilter
import com.netflix.zuul.context.RequestContext
import io.netty.buffer.ByteBuf
import io.reactivex.netty.protocol.http.client.HttpClientResponse
import io.reactivex.netty.protocol.http.client.HttpResponseHeaders
import io.reactivex.netty.protocol.http.server.HttpServerResponse
import rx.Observable;
import rx.Subscriber
import rx.functions.Action0
import rx.functions.Func1

/**
 * @author mhawthorne
 */
class SendResponseFilter extends ZuulAsyncFilter {

    @Override
    int filterOrder() {
        return 0
    }

    @Override
    String filterType() {
        return "post"
    }

    @Override
    boolean shouldFilter(RequestContext ctx) {
        return true
    }

    @Override
    rx.Observable toObservable(RequestContext ctx) {
        return Observable.create(new Observable.OnSubscribe<Subscriber>() {

            @Override
            void call(Subscriber sub) {
                final HttpServerResponse<ByteBuf> clientRes = ctx.response;
                final Observable<HttpClientResponse<ByteBuf>> originRes = ctx.originResponse;

                originRes.flatMap(new Func1<HttpClientResponse<ByteBuf>, Observable<ByteBuf>>() {

                    @Override
                    Observable<ByteBuf> call(HttpClientResponse<ByteBuf> res) {


                        final HttpResponseHeaders originHeaders = res.getHeaders();
                        for (final String name : originHeaders.names()) {
                            clientRes.headers.add(name, originHeaders.getAll(name));
                        }

                        return res.getContent();
                    }
                }).map(new Func1<ByteBuf, Void>() {

                    @Override
                    Void call(ByteBuf b) {
                        clientRes.writeAndFlush(b);
                        return null;
                    }
                }).doOnCompleted(new Action0() {

                    @Override
                    void call() {
                        clientRes.flush();
                        boolean b = false;
                    }
                }).subscribe(sub);
            }
        });

    }
}
