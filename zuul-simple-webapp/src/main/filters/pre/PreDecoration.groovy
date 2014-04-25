/*
 * Copyright 2013 Netflix, Inc.
 *
 *      Licensed under the Apache License, Version 2.0 (the "License");
 *      you may not use this file except in compliance with the License.
 *      You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 *      Unless required by applicable law or agreed to in writing, software
 *      distributed under the License is distributed on an "AS IS" BASIS,
 *      WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *      See the License for the specific language governing permissions and
 *      limitations under the License.
 */


import com.netflix.config.DynamicPropertyFactory
import com.netflix.zuul.ZuulFilter
import com.netflix.zuul.context.RequestContext
import org.omg.PortableInterceptor.ORBInitializerOperations

/**
 * @author mhawthorne
 */
class PreDecorationFilter extends ZuulFilter {

    private static final ORIGIN_HOST = DynamicPropertyFactory.getInstance().getStringProperty("zuul.origin.host", null);

    private URL originUrl;

    @Override
    int filterOrder() {
        return 5
    }

    @Override
    String filterType() {
        return "pre"
    }

    @Override
    boolean shouldFilter() {
        return true;
    }

    @Override
    Object run() {
        final RequestContext ctx = RequestContext.getCurrentContext();

        if(originUrl == null) {
            // the first few threads will hit this concurrently, but it's ok for now
            final String rawOriginHost = ORIGIN_HOST.get();
            if(rawOriginHost == null)
                throw new IllegalStateException(ORIGIN_HOST.getName() + " must be set")
            originUrl = new URL(rawOriginHost);
        }

        ctx.setRouteHost(originUrl)

        // sets origin
//        ctx.setRouteHost(new URL("http://apache.org/"));

        // sets custom header to send to the origin
        // ctx.addOriginResponseHeader("cache-control", "max-age=3600");
    }

}
