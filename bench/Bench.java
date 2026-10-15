import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * OfferMate load test tool (pure JDK, NO JMeter needed).
 *
 * Usage:
 *   1) create test accounts : java -Dstdout.encoding=UTF-8 Bench.java --prepare 500 --base http://localhost:8080
 *   2) async path           : java -Dstdout.encoding=UTF-8 Bench.java --path /api/bench/apply-async --concurrency 500 --requests 5000
 *   3) sync path (baseline): java -Dstdout.encoding=UTF-8 Bench.java --path /api/bench/apply-sync  --concurrency 500 --requests 5000
 *   4) tool self test       : java -Dstdout.encoding=UTF-8 Bench.java --selftest
 *
 * NOTE: output is ASCII only, so it renders correctly in any Windows console.
 */
public class Bench {

    public static void main(String[] args) throws Exception {
        Map<String, String> a = parseArgs(args);
        if (a.containsKey("selftest")) { selftest(a); return; }

        String base = a.getOrDefault("base", "http://localhost:18080");
        int concurrency = Integer.parseInt(a.getOrDefault("concurrency", "500"));
        int requests = Integer.parseInt(a.getOrDefault("requests", "5000"));

        if (a.containsKey("prepare")) {
            prepare(base, Integer.parseInt(a.get("prepare")), a.getOrDefault("password", "123456"));
            return;
        }

        Path tokenFile = Path.of(a.getOrDefault("tokens", "tokens.txt"));
        if (!Files.exists(tokenFile)) {
            System.out.println("[ERROR] token file not found: " + tokenFile.toAbsolutePath());
            System.out.println("        run --prepare first, e.g. java Bench.java --prepare 500");
            return;
        }
        List<String> tokens = Files.readAllLines(tokenFile);
        tokens.removeIf(String::isBlank);
        if (tokens.isEmpty()) { System.out.println("[ERROR] no token in " + tokenFile); return; }

        String path = a.getOrDefault("path", "/api/bench/apply-async");
        int jobRange = Integer.parseInt(a.getOrDefault("jobs", String.valueOf(tokens.size())));
        run(base, path, tokens, jobRange, concurrency, requests);
    }

    // ---------- step 1: create test accounts ----------
    static void prepare(String base, int count, String password) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        List<String> tokens = Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = newVirtualPool();
        CountDownLatch latch = new CountDownLatch(count);
        AtomicInteger failed = new AtomicInteger();
        for (int i = 1; i <= count; i++) {
            final String phone = String.format("139%08d", i);
            pool.submit(() -> {
                try {
                    post(client, base + "/api/auth/register",
                            "{\"phone\":\"" + phone + "\",\"password\":\"" + password + "\",\"nickname\":\"bench\",\"role\":\"SEEKER\"}", null);
                    // 登录接口有「按 IP 每秒 5 次」的限流，所以失败要退避重试
                    String token = null;
                    for (int attempt = 0; attempt < 8 && token == null; attempt++) {
                        HttpResponse<String> resp = post(client, base + "/api/auth/login",
                                "{\"phone\":\"" + phone + "\",\"password\":\"" + password + "\"}", null);
                        token = extract(resp.body(), "\"token\":\"");
                        if (token == null) { Thread.sleep(250); }
                    }
                    if (token != null) { tokens.add(token); } else { failed.incrementAndGet(); }
                } catch (Exception e) {
                    failed.incrementAndGet();
                } finally { latch.countDown(); }
            });
        }
        latch.await();
        pool.shutdown();
        Files.write(Path.of("tokens.txt"), tokens);
        System.out.println("prepared " + tokens.size() + " accounts (failed: " + failed.get() + ") -> tokens.txt");
    }

    // ---------- step 2/3: run load test ----------
    static void run(String base, String path, List<String> tokens, int jobRange,
                    int concurrency, int requests) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        long[] latencies = new long[requests];
        AtomicInteger seqGen = new AtomicInteger(0);
        AtomicInteger ok = new AtomicInteger(0);
        AtomicInteger fail = new AtomicInteger(0);
        AtomicInteger connFail = new AtomicInteger(0);
        AtomicLong failStatus = new AtomicLong(0);
        AtomicReference<String> firstError = new AtomicReference<>("");

        // 闭模型压测：固定 N 个并发「虚拟用户」，每个用户串行地循环发若干次请求。
        // 关键点：同一时刻在飞的请求数 = concurrency，所以测出来的 latency 才是真正的单请求耗时，
        // 不会被"一次性把所有请求都发出去"造成的客户端排队污染。
        int perWorker = (int) Math.ceil((double) requests / concurrency);
        ExecutorService pool = newVirtualPool();
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>(concurrency);
        long start = System.currentTimeMillis();
        for (int w = 0; w < concurrency; w++) {
            futures.add(pool.submit(() -> {
                try { startGate.await(); } catch (InterruptedException ignored) { }
                for (int i = 0; i < perWorker; i++) {
                    int seq = seqGen.getAndIncrement();
                    if (seq >= requests) { return; }
                    String token = tokens.get(seq % tokens.size());
                    long jobId = (seq / tokens.size()) % jobRange + 1;   // keep (user, job) pairs unique
                    long t0 = System.nanoTime();
                    try {
                        HttpResponse<String> r = post(client, base + path + "/" + jobId, null, token);
                        latencies[seq] = (System.nanoTime() - t0) / 1_000_000;
                        if (r.statusCode() == 200) { ok.incrementAndGet(); }
                        else {
                            fail.incrementAndGet();
                            failStatus.compareAndSet(0, r.statusCode());
                            firstError.compareAndSet("", "HTTP " + r.statusCode() + " " + r.body().substring(0, Math.min(120, r.body().length())));
                        }
                    } catch (Exception e) {
                        latencies[seq] = (System.nanoTime() - t0) / 1_000_000;
                        fail.incrementAndGet();
                        if (e instanceof java.io.IOException) { connFail.incrementAndGet(); }
                        firstError.compareAndSet("", e.getClass().getSimpleName() + ": " + e.getMessage());
                    }
                }
            }));
        }
        startGate.countDown();
        for (Future<?> f : futures) { f.get(); }
        long total = System.currentTimeMillis() - start;
        pool.shutdown();

        int used = Math.min(seqGen.get(), requests);
        report(base + path, concurrency, used, total, ok.get(), fail.get(), connFail.get(), failStatus.get(), firstError.get(), Arrays.copyOf(latencies, used));
    }

    static void report(String target, int concurrency, int requests, long totalMillis,
                       int ok, int fail, int connFail, long failCode, String firstError, long[] latencies) {
        long[] sorted = latencies.clone();
        Arrays.sort(sorted);
        long sum = 0;
        for (long v : sorted) { sum += v; }
        System.out.println();
        System.out.println("=== OfferMate benchmark ===");
        System.out.println("target       : " + target);
        System.out.printf("concurrency  : %d    requests: %d    total: %.1fs    throughput: %d req/s%n",
                concurrency, requests, totalMillis / 1000.0, requests * 1000L / Math.max(1, totalMillis));
        System.out.printf("success/fail : %d / %d%n", ok, fail);
        if (fail > 0) {
            System.out.println("fail status  : " + failCode);
            if (!firstError.isEmpty()) { System.out.println("first error  : " + firstError); }
        }
        if (connFail > 0 && connFail > requests * 0.01) {
            System.out.println("[WARN] " + connFail + " connection-level failures (>1%).");
            System.out.println("       Usually a client/OS limit, not your server: lower --concurrency to 200.");
        }
        System.out.println("latency (ms) : P50 " + p(sorted, 50) + "   P90 " + p(sorted, 90)
                + "   P95 " + p(sorted, 95) + "   P99 " + p(sorted, 99)
                + "   max " + sorted[sorted.length - 1] + "   avg " + (sum / sorted.length));
        System.out.println();
        System.out.println("copy line for resume:");
        System.out.printf("  concurrency %d -> P99 = %dms, P95 = %dms, avg = %dms%n",
                concurrency, p(sorted, 99), p(sorted, 95), sum / sorted.length);
        System.out.println("  (remember to state the environment: local machine, middleware in Docker)");
    }

    static long p(long[] sorted, int percentile) {
        if (sorted.length == 0) { return 0; }
        int idx = (int) Math.ceil(percentile / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, idx))];
    }

    // ---------- tool self test ----------
    static void selftest(Map<String, String> a) throws Exception {
        int concurrency = Integer.parseInt(a.getOrDefault("concurrency", "500"));
        int requests = Integer.parseInt(a.getOrDefault("requests", "2000"));

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 1024);
        server.setExecutor(newVirtualPool());
        server.createContext("/slow", ex -> respond(ex, 300));
        server.createContext("/fast", ex -> respond(ex, 0));
        server.start();
        int port = server.getAddress().getPort();
        System.out.println("[selftest] numbers below come from a built-in mock endpoint,");
        System.out.println("[selftest] they are NOT your project metrics - just proving the tool works.");

        for (String path : new String[]{"/slow", "/fast"}) {
            run("http://127.0.0.1:" + port, path, List.of("selftest"), 1, concurrency, requests);
        }
        server.stop(0);
    }

    static void respond(HttpExchange ex, long sleepMillis) throws IOException {
        if (sleepMillis > 0) {
            try { Thread.sleep(sleepMillis); } catch (InterruptedException ignored) { }
        }
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(body); }
    }

    // ---------- helpers ----------
    static HttpResponse<String> post(HttpClient client, String url, String body, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30));
        if (token != null) { b.header("Authorization", "Bearer " + token); }
        if (body != null) {
            b.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        } else {
            b.POST(HttpRequest.BodyPublishers.noBody());
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    static String extract(String json, String key) {
        int i = json.indexOf(key);
        if (i < 0) { return null; }
        int s = i + key.length();
        int e = json.indexOf('"', s);
        return e < 0 ? null : json.substring(s, e);
    }

    static ExecutorService newVirtualPool() {
        try {
            return (ExecutorService) Executors.class.getMethod("newVirtualThreadPerTaskExecutor").invoke(null);
        } catch (Exception e) {
            return Executors.newFixedThreadPool(256);
        }
    }

    static Map<String, String> parseArgs(String[] args) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--")) { continue; }
            String key = args[i].substring(2);
            if (i + 1 < args.length && !args[i + 1].startsWith("--")) { map.put(key, args[++i]); }
            else { map.put(key, "true"); }
        }
        return map;
    }
}
