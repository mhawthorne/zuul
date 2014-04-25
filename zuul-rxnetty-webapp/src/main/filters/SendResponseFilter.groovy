import com.netflix.util.Pair
import com.netflix.zuul2.ZuulObservableFilter
import com.netflix.zuul2.ZuulRequestContext
import io.netty.buffer.ByteBuf
import io.netty.handler.codec.http.HttpResponseStatus
import io.reactivex.netty.protocol.http.client.HttpClientResponse
import io.reactivex.netty.protocol.http.client.HttpResponseHeaders
import io.reactivex.netty.protocol.http.server.HttpServerResponse
import rx.Observable
import rx.Subscriber
import rx.functions.Action0
import rx.functions.Func1
/**
 * @author mhawthorne
 */
class SendResponseFilter extends ZuulObservableFilter {

    @Override
    int filterOrder() {
        return 0
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
    rx.Observable toObservable(ZuulRequestContext ctx) {
        return Observable.create(new Observable.OnSubscribe<Subscriber>() {

            @Override
            void call(Subscriber sub) {
                final HttpServerResponse<ByteBuf> clientRes = ctx.response;
                final Observable<HttpClientResponse<ByteBuf>> originRes = ctx.originResponse;

                originRes.flatMap(new Func1<HttpClientResponse<ByteBuf>, Observable<ByteBuf>>() {

                    @Override
                    Observable<ByteBuf> call(HttpClientResponse<ByteBuf> res) {
                        // grabs origin response data
                        final HttpResponseStatus status = res.getStatus();
                        final int statusCode = status.code();
                        final HttpResponseHeaders originResHeaders = res.getHeaders();

                        // sets origin response data into RequestContext for processing
                        ctx.responseStatusCode = statusCode;
                        final List<Pair<String, String>> ctxResHeaders = new LinkedList<Pair<String, String>>();
                        ctx.zuulResponseHeaders = ctxResHeaders;

                        // sets origin response data into zuul response
                        clientRes.setStatus(status);

                        for (final String name : originResHeaders.names()) {
                            final List<String> val = originResHeaders.getAll(name);
                            ctxResHeaders.add(new Pair<String, String>(name, val));
                            clientRes.headers.add(name, val);
                        }

                        // not sure if this will help
                        clientRes.headers.add("connection", "keep-alive");

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
                    }
                }).subscribe(sub);
                // this subscribe triggers execution of the request and allows me to grab the response and bytes
                // when I did this in the "route" filter I had to either consume the bytes there or I would lose them
                // I need to think about the right interaction here
            }
        });

    }
}
