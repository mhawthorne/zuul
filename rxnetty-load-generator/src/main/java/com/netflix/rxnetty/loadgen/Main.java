package com.netflix.rxnetty.loadgen;

import io.netty.buffer.ByteBuf;
import io.reactivex.netty.RxNetty;
import io.reactivex.netty.client.PoolStats;
import io.reactivex.netty.client.PoolStatsImpl;
import io.reactivex.netty.protocol.http.client.HttpClient;
import io.reactivex.netty.protocol.http.client.HttpClientBuilder;
import io.reactivex.netty.protocol.http.client.HttpClientRequest;
import io.reactivex.netty.protocol.http.client.HttpClientResponse;
import org.codehaus.jackson.JsonFactory;
import org.codehaus.jackson.JsonGenerator;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.map.ObjectWriter;
import org.codehaus.jackson.util.MinimalPrettyPrinter;
import rx.Observable;
import rx.Observable.OnSubscribe;
import rx.Subscriber;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.functions.Func1;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author mhawthorne
 */
public class Main {

    public static void main(String ... args) throws Exception {
        if(args.length < 4) throw error("expected <base-url> <path-file> <stats-root> <test-name>");

        final String rawUrl = args[0];
        final String pathFile = args[1];
        final String statsRoot = args[2];
        final String testName = args[3];

        final URL baseUrl = new URL(rawUrl);
        final List<String> paths = readLines(pathFile);

        if (paths.isEmpty()) throw error(String.format("could not ready any lines from paths file %s", pathFile));

        new Main(baseUrl, paths, statsRoot, testName).start();
    }

    private static RuntimeException error(String msg) {
        return new IllegalArgumentException(msg);
    }

    private static List<String> readLines(String path) throws IOException {
        final List<String> paths = new ArrayList<String>();
        final BufferedReader reader = new BufferedReader(new FileReader(path));
        String line;
        while((line = reader.readLine()) != null) {
            paths.add(line);
        }
        return paths;
    }

    private final HttpClient<ByteBuf, ByteBuf> client;
    private final List<String> paths;
    private final String path;
    private final Random random = new Random();

    private RequestStats stats;
    private long testStartMillis;

    private final JsonGenerator summaryGenerator;
    private final ObjectMapper jsonMapper = new ObjectMapper(); // .writer(new MinimalPrettyPrinter("\n"));

    public Main(URL baseUrl, List<String> paths, String statsRoot, String testName) {
        this.client = new HttpClientBuilder<ByteBuf, ByteBuf>(baseUrl.getHost(), baseUrl.getPort())
            .withMaxConnections(1000)
            .build();

        this.paths = paths;

        final long start = System.currentTimeMillis();
        final String testResultDir = String.format("%s/%d-%s", statsRoot, start, testName);
        if(!new File(testResultDir).mkdirs()) throw error("could not create directory " + testResultDir);
        final String summaryFile = testResultDir + "/1-summary.json";

        try {
            summaryGenerator = new JsonFactory().createJsonGenerator(new FileWriter(summaryFile));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        summaryGenerator.setPrettyPrinter(new MinimalPrettyPrinter("\n"));

        // single path, just store single string
        if (paths.size() == 1) {
            path = paths.get(0);
        } else {
            throw error("can't handle multiple paths yet");
        }
    }

    private void logResponse(Long sequence, int status, String path, long requestMillis) {
        final PrintStream o = System.out;

        // logging every response is overkill and too much I/O
        // just logs them every once in awhile as a sanity check
        if(sequence % 2000 == 0) {
            o.println(String.format("%d %s %s %s %dms", sequence, Thread.currentThread().getName(), status, path, requestMillis));
        }

        if(stats.addRequest(status, requestMillis)) {
            final Map<String, Object> lastStats = stats.fetchLastStats();
            lastStats.put("i", sequence);

            final PoolStats connPoolStats = client.getStats();
            o.println(String.format("totalConnections:%d, inUse:%d, idle:%d",
                connPoolStats.getTotalConnectionCount(),
                connPoolStats.getInUseCount(),
                connPoolStats.getIdleCount()));

            // TODO: convert to JSON
            o.println(lastStats);
            try {
                this.jsonMapper.writeValue(this.summaryGenerator, lastStats);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static final void log(String msg) {
        System.out.println(String.format("%d %s", System.currentTimeMillis(), msg));
    }

    private static final void log(String msg, Object ... args) {
        log(String.format(msg, args));
    }

    private void start() throws Exception {
        // hack to test our jackson pretty printing
//        for (int i = 0; i < 10; i++) {
//            final Map m = new HashMap();
//            m.put("i", i);
//            m.put("time", System.currentTimeMillis());
//
//            System.out.println(m);
//
//            this.jsonMapper.writeValue(this.summaryGenerator, m);
//        }
//
//        if (true) {
//            return;
//        }

        testStartMillis = System.currentTimeMillis();
        stats = new RequestStats(testStartMillis);

        final int concurrentRequestsStart = 2;

        final AtomicReference<Semaphore> semaphoreRef = new AtomicReference<Semaphore>(new Semaphore(concurrentRequestsStart));

        createSemaphoreLoadGenerator(semaphoreRef)
        .subscribe(new Action1<Long>() {
            @Override
            public void call(final Long l) {
                String requestPath;
                if (path != null) {
                    requestPath = path;
                } else {
                    // selects random path from list
                    requestPath = Main.this.paths.get(random.nextInt(paths.size()));
                }

                // injects "proc_id" into path
                final String finalRequestPath = requestPath.replace("{proc_id}", String.valueOf(l));

                final HttpClientRequest<ByteBuf> req = HttpClientRequest.createGet(finalRequestPath);
                final Observable<HttpClientResponse<ByteBuf>> responseObservable = Main.this.client.submit(req);
                final long requestStartTime = System.currentTimeMillis();

                responseObservable.flatMap(new Func1<HttpClientResponse<ByteBuf>, Observable<ByteBuf>>() {
                    @Override
                    public Observable<ByteBuf> call(HttpClientResponse<ByteBuf> res) {
                        final long requestLatency = System.currentTimeMillis() - requestStartTime;
                        logResponse(l, res.getStatus().code(), finalRequestPath, requestLatency);
                        return res.getContent();
                    }
                }).map(new Func1<ByteBuf, Void>() {
                    @Override
                    public Void call(ByteBuf b) {
                        b.clear();
                        return null;
                    }
                }).doOnError(new Action1<Throwable>() {
                    @Override
                    public void call(Throwable t) {
                        final long requestLatency = System.currentTimeMillis() - requestStartTime;
                        logResponse(l, -1, finalRequestPath, requestLatency);

//                        log(t.getClass().getName());
                        t.printStackTrace();
                    }
                }).doOnTerminate(new Action0() {
                    @Override
                    public void call() {
                        semaphoreRef.get().release();
                    }
                }).subscribe();
            }
        });
    }

    // TODO: centralize dupe code between load generator observables

    private static final Observable<Long> createSemaphoreLoadGenerator(final AtomicReference<Semaphore> semaphoreRef) {
        final int windowMillis = 30000;

        final List<Integer> concurrencyWindows = new ArrayList<Integer>() {{
            add(5);
            add(10);
            add(20);
            add(50);
            add(100);
        }};

        return Observable.<Long>create(new OnSubscribe<Long>() {
            @Override
            public void call(Subscriber<? super Long> s) {
                int currentWindowIdx = 0;
                long requestsSentDuringWindow = 0;
                long currentWindowStart = System.currentTimeMillis();
                boolean isThrottling = true;
                long desiredWindowConcurrency = concurrencyWindows.get(0);

                long lastCheckMillis = currentWindowStart;
                long millisSinceLastCheck = 0;

                for (long l = 1; true; l++) {
                    final long currentMillis = System.currentTimeMillis();
                    millisSinceLastCheck = currentMillis - lastCheckMillis;

                    // checks for throttling every N requests
                    if (isThrottling && requestsSentDuringWindow > 0 && millisSinceLastCheck > 1000) {
                        lastCheckMillis = currentMillis;
                        final long millisSinceWindowStart = currentMillis - currentWindowStart;

                        final long windowRps = requestsSentDuringWindow * 1000 / millisSinceWindowStart;
                        log("windowRps: %d", windowRps);

                        // shifts current window
                        if (millisSinceWindowStart > windowMillis) {
                            if (currentWindowIdx < concurrencyWindows.size() - 1) {
                                currentWindowIdx++;
                                currentWindowStart = currentMillis;
                                desiredWindowConcurrency = concurrencyWindows.get(currentWindowIdx);

                                // this is an artificial decrease, but otherwise the stats would get screwed up
                                // because the count would include requests from the final iteration of the previous window
                                // since we will generally be increasing RPS anyway, I think this is fine
                                requestsSentDuringWindow = 0;

                                log("new throttle window: %d", desiredWindowConcurrency);
                            } else {
                                log("finished throttling");
                                // we have moved through all RPS windows, stop throttling
                                //                                isThrottling = false;
                                //                                currentWindowIdx = -1;
                                //                                requestsSentDuringWindow = -1;
                                //                                currentWindowStart = currentMillis;
                                //                                sleepMillis = 0;

                                // limit RPS increase to double of current
                                currentWindowStart = currentMillis;
                                desiredWindowConcurrency *= 2;
                                requestsSentDuringWindow = 0;

                                log("new throttle window: %d", desiredWindowConcurrency);
                            }
                        }
                    } else {
                        // ?
                    }

                    try {
                        semaphoreRef.get().tryAcquire(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }

                    s.onNext(new Long(l));
                    requestsSentDuringWindow++;
                }
            }
        });
    }

    private static final Observable<Long> createTimedLoadGenerator() {
        return Observable.<Long>create(new OnSubscribe<Long>() {
            private final int minSleepMillis = 0;
            private final int maxSleepMillis = 1000;

            private final int minSleepNanos = 1;
            private final int maxSleepNanos = 999999;

            @Override
            public void call(Subscriber<? super Long> s) {
                // I'm attempting to cap the number of RPS I send for 5 second intervals
                // to ramp up the load of the test
                final int windowMillis = 30000;

                final List<Integer> rpsWindows = new ArrayList<Integer>() {{
                    add(200);
                    add(500);
                    add(1000);
                }};

                int currentWindowIdx = 0;
                long requestsSentDuringWindow = 0;
                long currentWindowStart = System.currentTimeMillis();
                boolean isThrottling = true;
                long sleepMillis = 100;
                int sleepNanos = 0;
                long desiredWindowRps = rpsWindows.get(0);

                long lastCheckMillis = currentWindowStart;
                long millisSinceLastCheck = 0;

                for (long l = 1; true; l++) {
                    final long currentMillis = System.currentTimeMillis();
                    millisSinceLastCheck = currentMillis - lastCheckMillis;

                    // checks for throttling every N requests
                    if (isThrottling && requestsSentDuringWindow > 0 && millisSinceLastCheck > 1000) {
                        lastCheckMillis = currentMillis;

                        final long millisSinceWindowStart = currentMillis - currentWindowStart;

                        // shifts current window
                        if (millisSinceWindowStart > windowMillis) {
                            if (currentWindowIdx < rpsWindows.size() - 1) {
                                currentWindowIdx++;
                                currentWindowStart = currentMillis;
                                desiredWindowRps = rpsWindows.get(currentWindowIdx);

                                // this is an artificial decrease, but otherwise the stats would get screwed up
                                // because the count would include requests from the final iteration of the previous window
                                // since we will generally be increasing RPS anyway, I think this is fine
                                requestsSentDuringWindow = 0;

                                log("new throttle window: %drps", desiredWindowRps);
                            } else {
                                log("finished throttling");
                                // we have moved through all RPS windows, stop throttling
                                //                                isThrottling = false;
                                //                                currentWindowIdx = -1;
                                //                                requestsSentDuringWindow = -1;
                                //                                currentWindowStart = currentMillis;
                                //                                sleepMillis = 0;

                                // limit RPS increase to double of current
                                currentWindowStart = currentMillis;
                                desiredWindowRps *= 2;
                                requestsSentDuringWindow = 0;

                                log("new throttle window: %drps", desiredWindowRps);
                            }
                        }

                        // when window shifts, does actualWindowRps get screwed up?

                        final long actualWindowRps = requestsSentDuringWindow * 1000 / millisSinceWindowStart;

                        final long absoluteRpsDiff = Math.abs(actualWindowRps - desiredWindowRps);
                        final long rpsDiffThreshold = (long) (desiredWindowRps * 0.1);

                        if (absoluteRpsDiff > rpsDiffThreshold) {
                            // calculate how long I should sleep in between requests in order to throttle to the
                            // appropriate RPS
                            //                            log("actualWindowRps:%d, desiredWindowRps:%d", actualWindowRps, desiredWindowRps);

                            final float actualMillisPerRequest = 1 / ((float) actualWindowRps / 1000);
                            final float desiredMillisPerRequest = 1 / ((float) desiredWindowRps / 1000);

                            long oldSleepMillis = sleepMillis;
                            long newSleepMillis = -1;

                            int oldSleepNanos = sleepNanos;
                            int newSleepNanos = -1;

                            if (actualWindowRps > desiredWindowRps) {
                                log("actualWindowRps > desiredWindowRps (%d > %d)", actualWindowRps, desiredWindowRps);

                                // resets sleep nanos since we will be increasing sleepMillis
                                newSleepNanos = 0;

                                newSleepMillis = sleepMillis + (long) desiredMillisPerRequest;

                                if (newSleepMillis == maxSleepMillis) {
                                    log("leaving sleep millis at max of %d", maxSleepMillis);
                                } else if (newSleepMillis == sleepMillis) {
                                    log("default algo didn't change sleep millis; doubling");
                                    newSleepMillis *= 2;
                                } else if (sleepMillis > 0 && newSleepMillis > (sleepMillis * 2)) {
                                    // limits how much sleep can change at once
                                    log("limiting sleep millis increase to double");
                                    newSleepMillis = sleepMillis * 2;
                                }

                                if (newSleepMillis > maxSleepMillis) {
                                    log("limiting sleep millis to max of %d", maxSleepMillis);
                                    newSleepMillis = maxSleepMillis;
                                }

                                // if RPS isn't at desired and the math above doesn't change it, just increase by 1ms
                                //                                if(newSleepMillis == sleepMillis) newSleepMillis = sleepMillis + 1;
                            } else if (actualWindowRps < desiredWindowRps) {
                                log("actualWindowRps < desiredWindowRps (%d < %d)", actualWindowRps, desiredWindowRps);

                                newSleepMillis = sleepMillis - (long) desiredMillisPerRequest;

                                if (newSleepMillis == minSleepMillis) {
                                    log("leaving sleep millis at min of %d", minSleepMillis);

                                    // decreases sleep nanos
                                    if (sleepNanos == 0) {
                                        newSleepNanos = maxSleepNanos;
                                    } else {
                                        newSleepNanos = sleepNanos /= 2;
                                    }

                                    if (newSleepNanos < minSleepNanos) {
                                        log("limiting sleep nanos to min of %d", minSleepNanos);
                                        newSleepNanos = minSleepNanos;
                                    }
                                } else if (newSleepMillis == sleepMillis) {
                                    log("default algo didn't change sleep millis; halving");
                                    newSleepMillis /= 2;
                                } else if (newSleepMillis < (sleepMillis / 2)) {
                                    log("limiting sleep millis decrease to half");
                                    newSleepMillis = sleepMillis / 2;
                                }

                                if (newSleepMillis < minSleepMillis) {
                                    log("limiting sleep millis to min of %d", minSleepMillis);
                                    newSleepMillis = minSleepMillis;
                                }

                                // same hack as above
                                //                                if(newSleepMillis == sleepMillis) newSleepMillis = sleepMillis - 1;
                            }

                            if (newSleepNanos != -1 && newSleepNanos != sleepNanos) {
                                log("changing sleepNanos %d->%d", oldSleepNanos, newSleepNanos);
                                sleepNanos = newSleepNanos;
                            }

                            if (newSleepMillis > -1 && newSleepMillis != sleepMillis) {
                                sleepMillis = newSleepMillis;
                                log("changing sleepMillis %d->%d (actualWindowRps:%d, desiredWindowRps:%d) (actualMillisPerRequest:%f, desiredMillisPerRequest:%f)", oldSleepMillis, sleepMillis, actualWindowRps, desiredWindowRps, actualMillisPerRequest, desiredMillisPerRequest);
                            }
                        } else {
                            log("maintaining sleepMillis of %d (actualWindowRps:%d, desiredWindowRps:%d)", sleepMillis, actualWindowRps, desiredWindowRps);
                        }
                    }

                    if (sleepMillis > 0 || sleepNanos > 0) {
                        try {
                            Thread.sleep(sleepMillis, sleepNanos);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                            Thread.currentThread().interrupt();
                        }
                    }

                    s.onNext(new Long(l));
                    requestsSentDuringWindow++;
                }
            }
        });
    }

    private static final class RequestStats {

        private final long testStartMillis;

        // TODO: make configurable
        private final long statsIntervalMillis = 5000;

        private long lastStatsMillis;

        final AtomicInteger requests = new AtomicInteger();
        final AtomicInteger errors = new AtomicInteger();
        final List<Long> latencies = new ArrayList<Long>();

        Map<String, Object> lastStats;

        private final AtomicReference<Object> statsLock = new AtomicReference<Object>();

        RequestStats(long testStartMillis) {
            this.testStartMillis = testStartMillis;
            lastStatsMillis = testStartMillis;
        }

        boolean addRequest(int status, long latency) {
            requests.incrementAndGet();

            if (status == -1 || status > 399) errors.incrementAndGet();

            latencies.add(latency);

            final long currentMillis = System.currentTimeMillis();
            final long millisSinceLastStats = currentMillis - lastStatsMillis;
            if(millisSinceLastStats > statsIntervalMillis) {
                lastStatsMillis = currentMillis;

                // only one thread should win
                if(!statsLock.compareAndSet(statsLock.get(), new Object())) return false;

                this.lastStats = this.calculateAndClear(currentMillis, millisSinceLastStats);

                return true;
            } else {
                return false;
            }
        }

        Map<String, Object> fetchLastStats() {
            return this.lastStats;
        }

        Map<String, Object> calculateAndClear(long currentMillis, long millisSinceLastStats) {
            // copy stats into local method vars since other requests are executing concurrently
            final long requestCount = requests.get();
            final long errorCount = errors.get();
            final List<Long> latenciesCopy = new ArrayList<Long>(this.latencies);

            // resets stats for next window
            requests.set(0);
            errors.set(0);
            latencies.clear();

            final Map<String, Object> stats = new HashMap<String, Object>();

            stats.put("proc_id", Thread.currentThread().getId());
            stats.put("proc_name", Thread.currentThread().getName());

            stats.put("request_count", requestCount);
            stats.put("rps", requestCount / (millisSinceLastStats/1000));

            stats.put("test_run_millis", currentMillis - this.testStartMillis);
            stats.put("millis_since_last_stats", millisSinceLastStats);

            stats.put("error_count", errorCount);
            stats.put("error_pct", ((float)errorCount / requestCount) * 100);
//            stats.put("reconnect_count", null);

            if(latenciesCopy.size() < 2) {
                log("less than 2 latencies to sort?");
            } else {
                Collections.sort(latenciesCopy);
            }

            final int latencyCount = latenciesCopy.size();

            stats.put("latency_min", latenciesCopy.get(0));
            stats.put("latency_max", latenciesCopy.get(latencyCount-1));
            stats.put("latency_avg", null);
            stats.put("latency_median", latenciesCopy.get(latencyCount/2));
            stats.put("latency_90", latenciesCopy.get((int)(latencyCount * 0.9)));

            return stats;
        }

    }

}
