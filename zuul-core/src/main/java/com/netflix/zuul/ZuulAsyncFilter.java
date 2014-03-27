package com.netflix.zuul;

import com.netflix.zuul.context.RequestContext;
import rx.Observable;

/**
 * @author mhawthorne
 */
public abstract class ZuulAsyncFilter<T> extends ZuulFilter {

    public abstract Observable<T> toObservable(RequestContext ctx);

    @Override
    public ZuulFilterResult runFilter(RequestContext ctx) {
        throw notImplemented();
    }

    @Override
    public Object run(RequestContext ctx) {
        throw notImplemented();
    }

    private static final RuntimeException notImplemented() {
        return new UnsupportedOperationException();
    }

}