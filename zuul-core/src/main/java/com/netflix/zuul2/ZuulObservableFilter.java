package com.netflix.zuul2;

import rx.Observable;

/**
 * @author mhawthorne
 */
public abstract class ZuulObservableFilter<T> extends ZuulFilter {

    public abstract Observable<T> toObservable(ZuulRequestContext ctx);

}