package com.netflix.zuul2;

import java.util.concurrent.ConcurrentHashMap;

/**
 * The Request Context holds request, response,  state information and data for ZuulFilters to access and share.
 * The RequestContext lives for the duration of the request.
 * Most methods here are convenience wrapper methods; the RequestContext is an extension of a ConcurrentHashMap
 *
 * @author Mikey Cohen
 * @author mhawthorne
 */
public class ZuulRequestContext extends ConcurrentHashMap<String, Object> {}
