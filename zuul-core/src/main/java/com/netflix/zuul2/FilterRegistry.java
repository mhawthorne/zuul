package com.netflix.zuul2;


import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author mhawthorne
 */
public class FilterRegistry {

    private static final FilterRegistry INSTANCE = new FilterRegistry();

    public static final FilterRegistry instance() {
        return INSTANCE;
    }

    private final ConcurrentHashMap<String, ZuulFilterBase> filters = new ConcurrentHashMap<String, ZuulFilterBase>();

    private FilterRegistry() {
    }

    public ZuulFilterBase remove(String key) {
        return this.filters.remove(key);
    }

    public ZuulFilterBase get(String key) {
        return this.filters.get(key);
    }

    public void put(String key, ZuulFilterBase filter) {
        this.filters.putIfAbsent(key, filter);
    }

    public int size() {
        return this.filters.size();
    }

    public Collection<ZuulFilterBase> getAllFilters() {
        return this.filters.values();
    }

}
