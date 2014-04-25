package com.netflix.rxnetty.loadgen;

import io.netty.buffer.ByteBuf;
import io.reactivex.netty.RxNetty;
import io.reactivex.netty.protocol.http.client.HttpClient;
import io.reactivex.netty.protocol.http.client.HttpClientRequest;
import io.reactivex.netty.protocol.http.client.HttpClientResponse;
import rx.Observable;
import rx.functions.Action1;
import rx.functions.Func1;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author mhawthorne
 */
public class Main {

    public static void main(String ... args) throws Exception {
        if(args.length < 4) error("expected <base-url> <concurrency> <path-file> <stats-root>");

        final String rawUrl = args[0];
        final String concurrency = args[1];
        final String pathFile = args[2];
        final String statsRoot = args[3];

        final URL baseUrl = new URL(rawUrl);
        final List<String> paths = readLines(pathFile);

        if (paths.isEmpty()) throw error(String.format("could not ready any lines from paths file %s", pathFile));

        new Main(baseUrl, paths).start();
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

    public Main(URL baseUrl, List<String> paths) {
        this.client = RxNetty.createHttpClient(baseUrl.getHost(), baseUrl.getPort());
        this.paths = paths;

        // single path, just store single string
        if (paths.size() == 1) {
            path = paths.get(0);
        } else {
            throw error("can't handle multiple paths yet");
        }
    }

    private void logResponse(String status, String path, long requestMillis) {
        final PrintStream o = System.out;
//        o.println(String.format("%s %s %dms", status, path, requestMillis));

        if(stats.addRequest(requestMillis)) {
            final Map<String, Object> lastStats = stats.fetchLastStats();

            // TODO: convert to JSON
            o.println(lastStats);
        }
    }

    private void start() throws Exception {
        testStartMillis = System.currentTimeMillis();
        stats = new RequestStats(testStartMillis);

        Observable.interval(2, TimeUnit.MILLISECONDS).subscribe(new Action1<Long>() {
            @Override
            public void call(Long l) {
                String requestPath;
                if (path != null) {
                    requestPath = path;
                } else {
                    // selects random path from list
                    requestPath = Main.this.paths.get(random.nextInt(paths.size()));
                }

                // injects "proc_id" into path
                final String finalRequestPath = requestPath.replace("{proc_id}", String.valueOf(Thread.currentThread().getId()));

                final HttpClientRequest<ByteBuf> req = HttpClientRequest.createGet(finalRequestPath);
                final Observable<HttpClientResponse<ByteBuf>> responseObservable = Main.this.client.submit(req);
                final long requestStartTime = System.currentTimeMillis();

                responseObservable.flatMap(new Func1<HttpClientResponse<ByteBuf>, Observable<ByteBuf>>() {
                    @Override
                    public Observable<ByteBuf> call(HttpClientResponse<ByteBuf> res) {
                        final long requestLatency = System.currentTimeMillis() - requestStartTime;
                        logResponse(String.valueOf(res.getStatus().code()), finalRequestPath, requestLatency);
                        return res.getContent();
                    }
                }).map(new Func1<ByteBuf, Void>() {
                    @Override
                    public Void call(ByteBuf byteBuf) {
                        return null;
                    }
                }).subscribe();
            }
        });
    }

    private static final class RequestStats {

        private final long testStartMillis;

        // TODO: make configurable
        private final long statsIntervalMillis = 5000;

        private long lastStatsMillis;

        final AtomicInteger requests = new AtomicInteger();
        final List<Long> latencies = new ArrayList<Long>();

        Map<String, Object> lastStats;

        RequestStats(long testStartMillis) {
            this.testStartMillis = testStartMillis;
            lastStatsMillis = testStartMillis;
        }

        boolean addRequest(long latency) {
            requests.incrementAndGet();
            latencies.add(latency);

            final long currentMillis = System.currentTimeMillis();
            final long millisSinceLastStats = currentMillis - lastStatsMillis;
            if(millisSinceLastStats > statsIntervalMillis) {
                this.lastStats = this.calculateAndClear(currentMillis, millisSinceLastStats);
                lastStatsMillis = currentMillis;
                return true;
            } else {
                return false;
            }
        }

        Map<String, Object> fetchLastStats() {
            return this.lastStats;
        }

        Map<String, Object> calculateAndClear(long currentMillis, long millisSinceLastStats) {
            final Map<String, Object> stats = new HashMap<String, Object>();

            stats.put("proc_id", Thread.currentThread().getId());

            stats.put("request_count", requests.get());
            stats.put("rps", requests.get() / (millisSinceLastStats/1000));

            stats.put("test_run_millis", currentMillis - this.testStartMillis);
            stats.put("millis_since_last_stats", millisSinceLastStats);

            stats.put("error_count", null);
            stats.put("error_pct", null);
//            stats.put("reconnect_count", null);

            Collections.sort(latencies);

            stats.put("latency_min", latencies.get(0));
            stats.put("latency_max", latencies.get(latencies.size()-1));
            stats.put("latency_avg", null);
            stats.put("latency_median", latencies.get(latencies.size()/2));
            stats.put("latency_90", latencies.get((int)(latencies.size() * 0.9)));

            // resets stats for next window
            requests.set(0);
            latencies.clear();

            return stats;
        }

    }

}
