package com.netflix.zuul.rxnetty;

import com.netflix.zuul2.EventLogger;
import com.netflix.zuul2.FilterFileManager;
import com.netflix.zuul2.FilterLoader;
import com.netflix.zuul2.ZuulObservableFilter;
import com.netflix.zuul2.ZuulFilter;
import com.netflix.zuul.groovy.GroovyCompiler;
import com.netflix.zuul.groovy.GroovyFileFilter;
import com.netflix.zuul.monitoring.MonitoringHelper;
import com.netflix.zuul2.ZuulRequestContext;
import com.netflix.zuul2.ZuulSimpleFilter;
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
import java.util.UUID;

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
        if (scriptRoot == null) {
            throw new IllegalStateException("zuul.filter.root must be provided");
        }

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

        private final MetricsManager metrics = MetricsManager.instance();

        private ZuulRequestHandler() {}

        @Override
        public Observable<Void> handle(HttpServerRequest<ByteBuf> request, HttpServerResponse<ByteBuf> response) {
            // generate request id
            final String requestId = UUID.randomUUID().toString();

            EventLogger.log(requestId, "request-start");

            // build filter chain
            final ZuulRequestContext ctx = new ZuulRequestContext();
            ctx.put("request", request);
            ctx.put("response", response);
            ctx.put("requestId", requestId);

            final FilterLoader filterLoader = FilterLoader.getInstance();

            final List<Observable<Object>> pre = this.buildTypedFilterChain("pre", ctx, filterLoader);
            final List<Observable<Object>> route = this.buildTypedFilterChain("route", ctx, filterLoader);
            final List<Observable<Object>> post = this.buildTypedFilterChain("post", ctx, filterLoader);

            // TODO: need to way to implement different failure strategies per typed filter chain
            // I wanted separate observables for each chain, but can't get that to work with concat
            // using one big list of observables for now

            final List allFilterObservables = new LinkedList<Observable<Object>>();
            allFilterObservables.addAll(pre);
            allFilterObservables.addAll(route);
            allFilterObservables.addAll(post);

            final Observable fullFilterChain = Observable.concat(Observable.from(allFilterObservables));

            final Observable<Void> finalFullFilterChain = fullFilterChain.doOnError(new Action1<Throwable>() {
                @Override
                public void call(Throwable t) {
                    LOG.error("top level filter chain error", t);
                    t.printStackTrace();
                }
            });

            return Observable.<Void>create(new OnSubscribe<Void>() {
                @Override
                public void call(Subscriber<? super Void> sub) {
                    EventLogger.log(requestId, "request-filters-start");
                    startRequest(requestId);
                    finalFullFilterChain.subscribe(sub);
                }
            }).doOnTerminate(new Action0() {
                @Override
                public void call() {
                    endRequest(requestId);
                    EventLogger.log(requestId, "request-filters-end");
                }
            });
        }

        private void startRequest(String requestId) {
            this.metrics.startRequest(requestId);
        }

        private void endRequest(String requestId) {
            this.metrics.endRequest(requestId);
        }

        private <T> List<Observable<T>> buildTypedFilterChain(String type, final ZuulRequestContext ctx, FilterLoader filterLoader) {
            final String requestId = (String) ctx.get("requestId");

            final List<ZuulFilter> filters = filterLoader.getFiltersByType(type);
            final List<Observable<T>> observables = new LinkedList<Observable<T>>();
            for (final ZuulFilter f : filters) {
                final String filterId = f.getClass().getSimpleName();

                final Action1<Throwable> filterOnError = new Action1<Throwable>() {
                    @Override
                    public void call(Throwable t) {
                        LOG.error("error in filter " + f, t);
                    }
                };

                Observable filterObservable = null;

                if (f instanceof ZuulSimpleFilter) {
                    filterObservable = Observable.create(new OnSubscribe<T>() {
                        @Override
                        public void call(Subscriber sub) {
                            EventLogger.log(requestId, "filter-start " + filterId);
                            if (f.shouldFilter(ctx)) {
                                ((ZuulSimpleFilter) f).run(ctx);
                            }
                            sub.onCompleted();
                        }
                    });
                } else if (f instanceof ZuulObservableFilter) {
                    filterObservable = Observable.create(new OnSubscribe<T>() {
                        @Override
                        public void call(Subscriber sub) {
                            EventLogger.log(requestId, "filter-start " + filterId);
                            if (f.shouldFilter(ctx)) {
                                ((ZuulObservableFilter) f).toObservable(ctx).subscribe(sub);
                            }
//                            sub.onCompleted();
                        }
                    });
                } else {
                    LOG.error("unrecognized filter class {} for instance {}", f.getClass().getName(), f);
                }

                filterObservable = filterObservable.doOnError(filterOnError).doOnTerminate(new Action0() {
                    @Override
                    public void call() {
                        EventLogger.log(requestId, "filter-end " + filterId);
                    }
                });

                if(filterObservable != null) {
                    observables.add(filterObservable);
                }
            }

            return observables;
        }

        private Observable<Void> emptyObservable() {
            return Observable.<Void>empty();
        }

    }

}
