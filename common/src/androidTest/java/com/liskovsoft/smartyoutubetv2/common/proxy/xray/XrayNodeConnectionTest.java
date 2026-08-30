package com.liskovsoft.smartyoutubetv2.common.proxy.xray;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.InstrumentationRegistry;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Instrumented connectivity test: runs the embedded Xray core (aar) for real.
 *
 * Phase 1: TCP ping every built-in node.
 * Phase 2: real delay (through the node, via a temporary core) for the top survivors.
 * Phase 3: start the core with the best node and fetch a YouTube URL through
 *          the local SOCKS proxy.
 *
 * Prints a full result list to stdout/logcat and writes it to
 * &lt;external-files&gt;/xray_node_report.txt.
 */
public class XrayNodeConnectionTest {
    private static final String TAG = "XrayNodeTest";
    private static final int PING_CONCURRENCY = 20;
    private static final int MEASURE_CONCURRENCY = 12;
    private static final int PING_TIMEOUT_MS = 3_000;
    private static final int REAL_TEST_TOP_N = 60;
    private static final String YOUTUBE_TEST_URL = "https://www.youtube.com/generate_204";

    @Test
    public void testBuiltinNodesConnectivity() throws Exception {
        Context ctx = InstrumentationRegistry.getTargetContext();
        runConnectivityTest(ctx, readAsset(ctx, "xray_builtin_sub.yaml"));
    }

    /** Same three-phase check against the domain-based huojian subscription (DoH-resolved). */
    @Test
    public void testIpConfigNodesConnectivity() throws Exception {
        Context ctx = InstrumentationRegistry.getTargetContext();
        Context testCtx = InstrumentationRegistry.getInstrumentation().getContext();
        runConnectivityTest(ctx, readAsset(testCtx, "clash_config.yaml"));
    }

    private static void runConnectivityTest(Context ctx, String yaml) throws Exception {
        StringBuilder report = new StringBuilder();

        List<ProxyNode> nodes = ClashConfigParser.parse(yaml);
        assertFalse("no nodes parsed from subscription", nodes.isEmpty());
        report.append("Total nodes: ").append(nodes.size()).append('\n');

        // Domain-based subscriptions: resolve via the config's DoH servers,
        // one variant per candidate IP.
        nodes = DohResolver.expandWithResolvedIps(yaml, nodes);

        // Phase 1: TCP ping all nodes
        pingAll(nodes, PING_CONCURRENCY, false);
        sortByDelay(nodes);
        DohResolver.dedupeByName(nodes);

        int reachable = 0;
        for (ProxyNode node : nodes) {
            if (node.delayMs > 0) {
                reachable++;
            }
        }
        report.append("\n== Phase 1: TCP ping ==\n");
        report.append("Reachable: ").append(reachable).append('/').append(nodes.size()).append('\n');
        for (ProxyNode node : nodes) {
            report.append(node.delayMs > 0 ? node.delayMs + " ms" : "timeout")
                    .append("\t").append(node.name).append('\n');
        }

        // Phase 2: real delay for top survivors
        List<ProxyNode> top = new ArrayList<>();
        for (ProxyNode node : nodes) {
            if (node.delayMs > 0 && top.size() < REAL_TEST_TOP_N) {
                top.add(node);
            }
        }

        if (!top.isEmpty()) {
            XrayManager.instance(ctx).ensureEnv();
            measureRealDelay(top);
            // Best = lowest measured real delay, not lowest TCP ping.
            sortByDelay(top);

            report.append("\n== Phase 2: real delay through node ==\n");
            for (ProxyNode node : top) {
                report.append(node.delayMs > 0 ? node.delayMs + " ms" : "timeout")
                        .append("\t").append(node.name).append('\n');
            }
        }

        // Phase 3: YouTube through the best node
        boolean youtubeOk = false;
        ProxyNode best = null;
        for (ProxyNode node : top) {
            if (node.delayMs > 0) {
                best = node;
                break;
            }
        }

        report.append("\n== Phase 3: YouTube via best node ==\n");
        if (best == null) {
            report.append("no working node, skipped\n");
        } else {
            report.append("best node: ").append(best.name).append('\n');
            XrayManager manager = XrayManager.instance(ctx);
            try {
                manager.startSync(best.getOutboundJson());
                waitForPort(10_000);

                long start = System.currentTimeMillis();
                OkHttpClient client = new OkHttpClient.Builder()
                        .proxy(new java.net.Proxy(java.net.Proxy.Type.SOCKS,
                                new InetSocketAddress(XrayManager.LOCAL_HOST, XrayManager.LOCAL_PORT)))
                        .build();
                try (Response response = client.newCall(
                        new Request.Builder().url(YOUTUBE_TEST_URL).build()).execute()) {
                    long elapsed = System.currentTimeMillis() - start;
                    youtubeOk = response.code() == 204 || response.code() == 200;
                    report.append("GET ").append(YOUTUBE_TEST_URL)
                            .append(" -> HTTP ").append(response.code())
                            .append(" in ").append(elapsed).append(" ms\n");
                }
            } catch (Exception e) {
                report.append("YouTube check failed: ").append(e.getMessage()).append('\n');
            } finally {
                manager.stop();
            }
        }

        String text = report.toString();
        System.out.println(text);
        android.util.Log.i(TAG, text);
        writeReport(ctx, text);

        assertTrue("no reachable node", reachable > 0);
        assertTrue("YouTube unreachable through the best node", youtubeOk);
    }

    private static void pingAll(List<ProxyNode> nodes, int concurrency, boolean real) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        List<Future<?>> futures = new ArrayList<>();
        for (ProxyNode node : nodes) {
            futures.add(pool.submit(() -> node.delayMs = tcpPing(node)));
        }
        for (Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();
    }

    private static void measureRealDelay(List<ProxyNode> nodes) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(MEASURE_CONCURRENCY);
        List<Future<?>> futures = new ArrayList<>();
        for (ProxyNode node : nodes) {
            futures.add(pool.submit(() -> {
                long delay = XrayManager.measureNodeDelay(node);
                node.delayMs = delay; // may be -1 (unreachable through the node)
            }));
        }
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
    }

    private static long tcpPing(ProxyNode node) {
        long start = System.currentTimeMillis();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(node.server, node.port), PING_TIMEOUT_MS);
            return System.currentTimeMillis() - start;
        } catch (Exception e) {
            return -1;
        }
    }

    private static void sortByDelay(List<ProxyNode> nodes) {
        List<ProxyNode> reachable = new ArrayList<>();
        List<ProxyNode> unreachable = new ArrayList<>();
        for (ProxyNode node : nodes) {
            (node.delayMs > 0 ? reachable : unreachable).add(node);
        }
        reachable.sort((a, b) -> Long.compare(a.delayMs, b.delayMs));
        nodes.clear();
        nodes.addAll(reachable);
        nodes.addAll(unreachable);
    }

    private static void waitForPort(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(XrayManager.LOCAL_HOST, XrayManager.LOCAL_PORT), 1_000);
                return;
            } catch (Exception ignored) {
                Thread.sleep(300);
            }
        }
        throw new IllegalStateException("Xray SOCKS port did not open");
    }

    private static String readAsset(Context ctx, String name) throws Exception {
        InputStream in = ctx.getAssets().open(name);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16_384];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        in.close();
        return out.toString("UTF-8");
    }

    private static void writeReport(Context ctx, String text) {
        try {
            File dir = ctx.getExternalFilesDir(null);
            File file = new File(dir, "xray_node_report.txt");
            FileOutputStream out = new FileOutputStream(file);
            out.write(text.getBytes("UTF-8"));
            out.close();
            android.util.Log.i(TAG, "Report written to " + file.getAbsolutePath());
        } catch (Exception e) {
            android.util.Log.e(TAG, "Failed to write report: " + e.getMessage());
        }
    }
}
