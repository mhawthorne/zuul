package com.netflix.zuul.rxnetty;

import com.netflix.zuul.FilterFileManager;
import com.netflix.zuul.FilterLoader;
import com.netflix.zuul.ZuulAsyncFilter;
import com.netflix.zuul.ZuulFilter;
import com.netflix.zuul.ZuulRunner;
import com.netflix.zuul.context.RequestContext;
import com.netflix.zuul.exception.ZuulException;
import com.netflix.zuul.groovy.GroovyCompiler;
import com.netflix.zuul.groovy.GroovyFileFilter;
import com.netflix.zuul.monitoring.MonitoringHelper;
import io.netty.buffer.ByteBuf;
import io.reactivex.netty.RxNetty;
import io.reactivex.netty.protocol.http.server.HttpServer;
import io.reactivex.netty.protocol.http.server.HttpServerRequest;
import io.reactivex.netty.protocol.http.server.HttpServerResponse;
import io.reactivex.netty.protocol.http.server.RequestHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rx.Observable;
import rx.Observable.OnSubscribe;
import rx.Subscriber;
import rx.functions.Action0;
import rx.functions.Action1;

import java.util.LinkedList;
import java.util.List;

/**
 * @author mhawthorne
 */
public class StartServer {

    public static void main(String... args) {
        final StartServer s = new StartServer();
        s.initFilters();
        s.startServer();
    }

    private void initFilters() {
        // mocks monitoring infrastructure as we don't need it for this simple app
        MonitoringHelper.initMocks();

        FilterLoader.getInstance().setCompiler(new GroovyCompiler());

        final String scriptRoot = System.getProperty("zuul.filter.root");
        try {
            FilterFileManager.setFilenameFilter(new GroovyFileFilter());
            FilterFileManager.init(5, scriptRoot + "/");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }

    private void startServer() {
        final HttpServer<ByteBuf, ByteBuf> server = RxNetty.createHttpServer(8080, new ZuulRequestHandler());
        server.start();
    }

    private static final class ZuulRequestHandler implements RequestHandler<ByteBuf, ByteBuf> {

        private static final Logger LOG = LoggerFactory.getLogger(ZuulRequestHandler.class);

        private final ZuulRunner zuulRunner = new ZuulRunner();

        private ZuulRequestHandler() {}

        @Override
        public Observable<Void> handle(HttpServerRequest<ByteBuf> request, HttpServerResponse<ByteBuf> response) {
            System.out.println("Server => Request: " + request.getPath());
//            try {
//                if (request.getPath().equals("/error")) {
//                    throw new RuntimeException("forced error");
//                }
//                response.setStatus(HttpResponseStatus.OK);
//                return response.writeStringAndFlush("Path Requested =>: " + request.getPath() + "\n");
//            } catch (Throwable e) {
//                System.err.println("Server => Error [" + request.getPath() + "] => " + e);
//                response.setStatus(HttpResponseStatus.BAD_REQUEST);
//                return response.writeStringAndFlush("Error 500: Bad Request\n");
//            }

            // build filter chain
            final RequestContext ctx = new RequestContext();
            ctx.set("request", request);
            ctx.set("response", response);

            final FilterLoader filterLoader = FilterLoader.getInstance();


            final List<Observable<Object>> pre = this.buildTypedFilterChain("pre", ctx, filterLoader);
            final List<Observable<Object>> route = this.buildTypedFilterChain("route", ctx, filterLoader);
            final List<Observable<Object>> post = this.buildTypedFilterChain("post", ctx, filterLoader);

//            final Observable fullFilterChain = Observable.concat(Observable.from(route));
//            final Observable fullFilterChain = Observable.concat(Observable.from(pre), Observable.from(route));

            // TODO: need to way to implement different failure strategies per typed filter chain
            // I wanted separate observables for each chain, but can't get that to work with concat
            // using one big list of observables for now

            final List allFilterObservables = new LinkedList<Observable<Object>>();
            allFilterObservables.addAll(pre);
            allFilterObservables.addAll(route);
            allFilterObservables.addAll(post);
            final Observable fullFilterChain = Observable.concat(Observable.from(allFilterObservables));

            return fullFilterChain.doOnError(new Action1<Throwable>() {
                @Override
                public void call(Throwable t) {
                    LOG.error("top level filter chain error", t);
                }
            });

//            try {
//                // marks this request as having passed through the "Zuul engine", as opposed to servlets
//                // explicitly bound in web.xml, for which requests will not have the same data attached
//                final RequestContext ctx = new RequestContext();
//                ctx.setZuulEngineRan();
//
//                try {
//                    preRoute();
//                } catch (ZuulException e) {
//                    error(e);
//                    postRoute();
//                    return emptyObservable();
//                }
//                try {
//                    route();
//                } catch (ZuulException e) {
//                    error(e);
//                    postRoute();
//                    return emptyObservable();
//                }
//                try {
//                    postRoute();
//                } catch (ZuulException e) {
//                    error(e);
//                    return emptyObservable();
//                }
//            } catch (Throwable e) {
//                error(new ZuulException(e, 500, "UNHANDLED_EXCEPTION_" + e.getClass().getName()));
//            } finally {
//    //            RequestContext.getCurrentContext().unset();
//            }
//
//            return emptyObservable();
        }

//        private List<Observable> buildTypedFilterChain(String type, final RequestContext ctx, FilterLoader filterLoader) {
//            final List<ZuulFilter> filters = filterLoader.getFiltersByType(type);
//            final List<Observable> observables = new LinkedList<Observable>();
//            for (final ZuulFilter f : filters) {
//                if (f instanceof ZuulAsyncFilter) {
//                    // TODO: need to insert a shouldFilter call here
//                    observables.add(((ZuulAsyncFilter) f).toObservable(ctx));
//                } else {
//                    observables.add(Observable.create(new OnSubscribe<Object>() {
//                        @Override
//                        public void call(Subscriber subscriber) {
//                            if (f.shouldFilter(ctx)) {
//                                f.run(ctx);
//                            }
//                        }
//                    }).doOnError(new Action1<Throwable>() {
//                        @Override
//                        public void call(Throwable throwable) {
//                            boolean b = false;
//                        }
//                    }));
//                }
//            }
//
//            return observables;
//        }

        private <T> List<Observable<T>> buildTypedFilterChain(String type, final RequestContext ctx, FilterLoader filterLoader) {
            final List<ZuulFilter> filters = filterLoader.getFiltersByType(type);
            final List<Observable<T>> observables = new LinkedList<Observable<T>>();
            for (final ZuulFilter f : filters) {
                final Action1<Throwable> filterOnError = new Action1<Throwable>() {
                    @Override
                    public void call(Throwable t) {
                        LOG.error("error in filter " + f, t);
                    }
                };

                if (f instanceof ZuulAsyncFilter) {
                    // TODO: need to insert a shouldFilter call here
                    observables.add(((ZuulAsyncFilter) f).toObservable(ctx).doOnError(filterOnError));
                } else {
                    observables.add(Observable.create(new OnSubscribe<T>() {
                        @Override
                        public void call(Subscriber subscriber) {
                            if (f.shouldFilter(ctx)) {
                                f.run(ctx);
                                subscriber.onCompleted();
                            }
                        }
                    }).doOnError(filterOnError).doOnTerminate(new Action0() {
                        @Override
                        public void call() {
                            boolean b = true;
                        }
                    }));
                }
            }

            return observables;
        }

        private Observable<Void> emptyObservable() {
            return Observable.<Void>empty();
        }

        /**
         * executes "post" ZuulFilters
         *
         * @throws ZuulException
         */
        void postRoute() throws ZuulException {
            zuulRunner.postRoute();
        }

        /**
         * executes "route" filters
         *
         * @throws ZuulException
         */
        void route() throws ZuulException {
            zuulRunner.route();
        }

        /**
         * executes "pre" filters
         *
         * @throws ZuulException
         */
        void preRoute() throws ZuulException {
            zuulRunner.preRoute();
        }

        /**
         * sets error context info and executes "error" filters
         *
         * @param e
         */
        void error(ZuulException e) {
            RequestContext.getCurrentContext().setThrowable(e);
            zuulRunner.error();
            LOG.error(e.getMessage(), e);
        }
    }

}
