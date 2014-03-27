package com.netflix.zuul2;

import com.netflix.zuul.context.RequestContext;
import rx.Observable;

/**
 * @author mhawthorne
 */
public abstract class ZuulAsyncFilter<T> extends ZuulFilterBase {

    public abstract Observable<T> toObservable(ZuulRequestContext ctx);

}