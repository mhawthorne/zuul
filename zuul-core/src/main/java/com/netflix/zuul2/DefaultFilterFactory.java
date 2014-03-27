package com.netflix.zuul2;

/**
 * Default factory for creating instances of ZuulFilter. 
 */
public class DefaultFilterFactory implements FilterFactory {

    /**
     * Returns a new implementation of ZuulFilter as specified by the provided 
     * Class. The Class is instantiated using its nullary constructor.
     * 
     * @param clazz the Class to instantiate
     * @return A new instance of ZuulFilter
     */
    @Override
    public ZuulFilterBase newInstance(Class clazz) throws InstantiationException, IllegalAccessException {
        return (ZuulFilterBase) clazz.newInstance();
    }

}
