package com.netflix.zuul2;

import com.netflix.config.DynamicBooleanProperty;
import com.netflix.config.DynamicPropertyFactory;

/**
 * @author mhawthorne
 */
public abstract class ZuulFilterBase implements IZuulFilter, Comparable<ZuulFilterBase> {

    protected final DynamicBooleanProperty filterDisabled =
        DynamicPropertyFactory.getInstance().getBooleanProperty(disablePropertyName(), false);

    /**
     * to classify a filter by type. Standard types in Zuul are "pre" for pre-routing filtering,
     * "route" for routing to an origin, "post" for post-routing filters, "error" for error handling.
     * We also support a "static" type for static responses see  StaticResponseFilter.
     * Any filterType made be created or added and run by calling FilterProcessor.runFilters(type)
     *
     * @return A String representing that type
     */
    abstract public String filterType();

    /**
     * filterOrder() must also be defined for a filter. Filters may have the same  filterOrder if precedence is not
     * important for a filter. filterOrders do not need to be sequential.
     *
     * @return the int order of a filter
     */
    abstract public int filterOrder();

    @Override
    public int compareTo(ZuulFilterBase other) {
        return this.filterOrder() - other.filterOrder();
    }

    /**
     * The name of the Archaius property to disable this filter. by default it is zuul.[classname].[filtertype].disable
     *
     * @return
     */
    public String disablePropertyName() {
        return "zuul." + this.getClass().getSimpleName() + "." + filterType() + ".disable";
    }

    /**
     * If true, the filter has been disabled by archaius and will not be run
     *
     * @return
     */
    public boolean isFilterDisabled() {
        return filterDisabled.get();
    }

}
