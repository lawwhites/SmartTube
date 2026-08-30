package com.liskovsoft.smartyoutubetv2.common.proxy.xray;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.sharedutils.okhttp.OkHttpManager;
import com.liskovsoft.sharedutils.rx.RxHelper;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.prefs.AppPrefs;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData;
import com.liskovsoft.smartyoutubetv2.common.proxy.PasswdInetSocketAddress;
import com.liskovsoft.smartyoutubetv2.common.proxy.Proxy;
import com.liskovsoft.smartyoutubetv2.common.proxy.ProxyManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Automatic startup pipeline: detect the fastest built-in node and route the
 * whole app through it, without any user interaction.
 *
 * Phase 1: TCP ping every node (built-in asset or custom subscription).
 * Phase 2: real delay through the top survivors (temporary core per node).
 * Phase 3: start the full core with the best node, verify YouTube reachability
 *          through the local SOCKS proxy, then apply the proxy app-wide.
 *
 * On total failure the app stays direct-connected.
 */
public class XrayBootstrap {
    private static final String TAG = XrayBootstrap.class.getSimpleName();
    private static final int PING_CONCURRENCY = 20;
    private static final int MEASURE_CONCURRENCY = 12;
    private static final int PING_TIMEOUT_MS = 3_000;
    private static final int REAL_TEST_TOP_N = 60;
    private static final int YOUTUBE_CHECK_ATTEMPTS = 3;
    private static final String YOUTUBE_TEST_URL = "https://www.youtube.com/generate_204";
    /** Fast path: reuse the cached node only when its YouTube delay stays below this. */
    private static final int FAST_PATH_MAX_DELAY_MS = 500;
    /** Phase 2 exits early once this many nodes measure below MEASURE_EARLY_EXIT_DELAY_MS. */
    private static final int MEASURE_EARLY_EXIT_COUNT = 3;
    private static final int MEASURE_EARLY_EXIT_DELAY_MS = 500;
    /** Overall cap for Phase 2; slower stragglers are cancelled. */
    private static final long MEASURE_TOTAL_TIMEOUT_MS = 60_000;

    public interface Callback {
        /** Progress update for the splash screen. Runs on the main thread. */
        void onProgress(String message);
        /** Runs on the main thread. proxyActive = app traffic now goes through Xray. */
        void onDone(boolean proxyActive);
    }

    private static ProxyNode sSelected;

    public static void start(Context context, Callback callback) {
        Context appContext = context.getApplicationContext();
        RxHelper.runAsyncUser(
                () -> sSelected = detectBestNode(appContext, callback),
                error -> {
                    Log.e(TAG, "Xray bootstrap failed: %s", error.getMessage());
                    sSelected = null;
                },
                () -> {
                    boolean active = applyResult(appContext, sSelected);
                    if (callback != null) {
                        callback.onDone(active);
                    }
                });
    }

    private static void reportProgress(Context context, Callback callback, int resId, Object... args) {
        if (callback == null) {
            return;
        }
        String message = context.getString(resId, args);
        new Handler(Looper.getMainLooper()).post(() -> callback.onProgress(message));
    }

    /** Returns the node that passed the YouTube check, or null. Blocking. */
    private static ProxyNode detectBestNode(Context context, Callback callback) {
        try {
            // Fast path: re-verify the previously selected node against YouTube
            // and skip the full detection when it is still healthy. Falls back
            // to the full pipeline on failure or high delay (node credentials
            // may have rotated since the node was cached).
            ProxyNode cached = tryCachedNode(context, callback);
            if (cached != null) {
                return cached;
            }

            reportProgress(context, callback, R.string.xray_detecting_nodes);
            // Remote subscription first (node credentials may rotate), bundled list as fallback.
            String yaml = XrayNodeSelector.loadSubscription(context);
            List<ProxyNode> nodes = ClashConfigParser.parse(yaml);
            if (nodes.isEmpty()) {
                Log.e(TAG, "No nodes to test");
                return null;
            }

            // Domain-based subscriptions: resolve node domains to currently
            // working IPs via the config's own DoH servers. Each domain
            // expands into one variant per candidate IP; Phase 1 prunes the
            // dead ones. No-op for IP-based subscriptions.
            nodes = DohResolver.expandWithResolvedIps(yaml, nodes);

            // Phase 1: TCP ping all
            pingAll(nodes);
            sortByDelay(nodes);
            DohResolver.dedupeByName(nodes);
            Log.d(TAG, "Phase 1 done, reachable: %d/%d", countReachable(nodes), nodes.size());
            reportProgress(context, callback, R.string.xray_phase1_result, countReachable(nodes), nodes.size());

            // Phase 2: real delay for top survivors
            List<ProxyNode> top = new ArrayList<>();
            for (ProxyNode node : nodes) {
                if (node.delayMs > 0 && top.size() < REAL_TEST_TOP_N) {
                    top.add(node);
                }
            }
            if (top.isEmpty()) {
                return null;
            }
            XrayManager.instance(context).ensureEnv();
            int working = measureRealDelay(context, top);
            Log.d(TAG, "Phase 2 done, working: %d/%d", working, top.size());
            reportProgress(context, callback, R.string.xray_phase2_result, working);

            // Phase 3: YouTube check through the full core, best first.
            // measureRealDelay already ordered the list: measured nodes by real
            // delay first, then unmeasured ones by TCP ping.
            XrayManager manager = XrayManager.instance(context);
            int attempts = 0;
            for (ProxyNode node : top) {
                if (node.delayMs <= 0 || attempts >= YOUTUBE_CHECK_ATTEMPTS) {
                    continue;
                }
                attempts++;
                reportProgress(context, callback, R.string.xray_phase3_checking, node.name);
                long delayMs = checkYouTube(manager, node);
                if (delayMs > 0) {
                    Log.d(TAG, "Phase 3 passed with node: %s", node.name);
                    node.delayMs = delayMs;
                    return node;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Node detection failed: %s", e.getMessage());
        }
        return null;
    }

    /**
     * Fast path: measure the cached node's real delay with a temporary core
     * (pure RTT against YouTube, no full-core cold-start overhead) and start
     * the full core with it when still healthy. Returns the node when
     * reachable with delay &le; FAST_PATH_MAX_DELAY_MS, null otherwise.
     */
    private static ProxyNode tryCachedNode(Context context, Callback callback) {
        AppPrefs prefs = AppPrefs.instance(context);
        String outboundJson = prefs.getXraySelectedOutbound();
        if (!prefs.isXrayEnabled() || outboundJson.isEmpty()) {
            return null;
        }
        String name = prefs.getXraySelectedNodeName();
        XrayManager manager = XrayManager.instance(context);
        try {
            ProxyNode node = new ProxyNode(name, "", "", 0, new JSONObject(outboundJson));
            reportProgress(context, callback, R.string.xray_checking_last_node, name);
            manager.ensureEnv();
            long delayMs = XrayManager.measureNodeDelay(node);
            if (delayMs > 0 && delayMs <= FAST_PATH_MAX_DELAY_MS) {
                Log.d(TAG, "Fast path OK: %s (%d ms)", name, delayMs);
                node.delayMs = delayMs;
                manager.startSync(node.getOutboundJson());
                waitForPort(10_000);
                return node;
            }
            Log.d(TAG, "Fast path unusable (%d ms), running full detection", delayMs);
            manager.stop();
        } catch (Exception e) {
            Log.e(TAG, "Fast path failed: %s", e.getMessage());
            manager.stop();
        }
        return null;
    }

    /** Applies the proxy app-wide on the main thread. Returns whether active. */
    private static boolean applyResult(Context context, ProxyNode selected) {
        AppPrefs prefs = AppPrefs.instance(context);
        XrayManager manager = XrayManager.instance(context);

        if (selected == null) {
            prefs.setXrayEnabled(false);
            manager.stop();
            return false;
        }

        prefs.setXrayEnabled(true);
        prefs.setXraySelectedNodeName(selected.name);
        prefs.setXraySelectedOutbound(selected.getOutboundJson());

        // Route all JVM traffic into the local Xray SOCKS port.
        // In-memory only: the manual web proxy prefs stay untouched.
        new ProxyManager(context).configureProxy(new Proxy(Proxy.Type.SOCKS,
                PasswdInetSocketAddress.createUnresolved(XrayManager.LOCAL_HOST, XrayManager.LOCAL_PORT, null, null)));

        // Cronet bypasses JVM proxy properties, force OkHttp for playback.
        PlayerTweaksData.instance(context).setPlayerDataSource(PlayerTweaksData.PLAYER_DATA_SOURCE_OKHTTP);

        OkHttpManager.unhold();

        MessageHelpers.showMessage(context, context.getString(R.string.xray_node_selected, selected.name));
        return true;
    }

    /** @return YouTube generate_204 delay through the node in ms, -1 on failure. */
    private static long checkYouTube(XrayManager manager, ProxyNode node) {
        try {
            manager.stop();
            manager.startSync(node.getOutboundJson());
            waitForPort(10_000);

            OkHttpClient client = new OkHttpClient.Builder()
                    .proxy(new java.net.Proxy(java.net.Proxy.Type.SOCKS,
                            new InetSocketAddress(XrayManager.LOCAL_HOST, XrayManager.LOCAL_PORT)))
                    .build();
            long start = System.currentTimeMillis();
            try (Response response = client.newCall(
                    new Request.Builder().url(YOUTUBE_TEST_URL).build()).execute()) {
                boolean ok = response.code() == 204 || response.code() == 200;
                return ok ? System.currentTimeMillis() - start : -1;
            }
        } catch (Exception e) {
            Log.e(TAG, "YouTube check failed for %s: %s", node.name, e.getMessage());
            return -1;
        }
    }

    private static void pingAll(List<ProxyNode> nodes) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(PING_CONCURRENCY);
        List<Future<?>> futures = new ArrayList<>();
        for (ProxyNode node : nodes) {
            futures.add(pool.submit(() -> node.delayMs = tcpPing(node)));
        }
        for (Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();
    }

    /**
     * Measures the real forwarding delay of the given nodes, consuming results
     * as they arrive and exiting early once MEASURE_EARLY_EXIT_COUNT nodes
     * prove fast (the goal is a good-enough node, not the absolute fastest).
     * Nodes are measured in last-known-delay order so likely-good nodes are
     * tested first. On return the list is reordered: measured nodes by real
     * delay (failures last), then unmeasured nodes by TCP ping.
     * @return number of nodes that passed the real test
     */
    private static int measureRealDelay(Context context, List<ProxyNode> nodes) {
        Map<String, Long> lastDelays = readLastDelays(context);
        if (!lastDelays.isEmpty()) {
            // Stable sort: nodes measured fast last time go first, unknown
            // nodes keep their Phase 1 ping order.
            nodes.sort((a, b) -> {
                Long da = lastDelays.get(a.name);
                Long db = lastDelays.get(b.name);
                if (da == null && db == null) {
                    return 0;
                }
                if (da == null) {
                    return 1;
                }
                if (db == null) {
                    return -1;
                }
                return Long.compare(da, db);
            });
        }

        Set<ProxyNode> measured = Collections.newSetFromMap(new ConcurrentHashMap<>());
        ExecutorService pool = Executors.newFixedThreadPool(MEASURE_CONCURRENCY);
        ExecutorCompletionService<ProxyNode> completion = new ExecutorCompletionService<>(pool);
        for (ProxyNode node : nodes) {
            completion.submit(() -> {
                node.delayMs = XrayManager.measureNodeDelay(node);
                return node;
            });
        }

        int received = 0;
        int working = 0;
        int fast = 0;
        long deadline = System.currentTimeMillis() + MEASURE_TOTAL_TIMEOUT_MS;
        try {
            while (received < nodes.size()) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    break;
                }
                Future<ProxyNode> future = completion.poll(remaining, TimeUnit.MILLISECONDS);
                if (future == null) {
                    break; // overall timeout, cancel the rest
                }
                received++;
                ProxyNode node;
                try {
                    node = future.get();
                } catch (ExecutionException e) {
                    continue;
                }
                measured.add(node);
                if (node.delayMs > 0) {
                    working++;
                    lastDelays.put(node.name, node.delayMs);
                    if (node.delayMs <= MEASURE_EARLY_EXIT_DELAY_MS && ++fast >= MEASURE_EARLY_EXIT_COUNT) {
                        Log.d(TAG, "Phase 2 early exit: %d nodes under %d ms", fast, MEASURE_EARLY_EXIT_DELAY_MS);
                        break;
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            pool.shutdownNow();
            writeLastDelays(context, lastDelays);
        }

        List<ProxyNode> measuredList = new ArrayList<>();
        List<ProxyNode> unmeasured = new ArrayList<>();
        for (ProxyNode node : nodes) {
            (measured.contains(node) ? measuredList : unmeasured).add(node);
        }
        sortByDelay(measuredList);
        sortByDelay(unmeasured);
        nodes.clear();
        nodes.addAll(measuredList);
        nodes.addAll(unmeasured);
        return working;
    }

    /** Last measured real delays per node name, persisted across runs. */
    private static Map<String, Long> readLastDelays(Context context) {
        Map<String, Long> result = new HashMap<>();
        String json = AppPrefs.instance(context).getXrayNodeDelays();
        if (!json.isEmpty()) {
            try {
                JSONObject obj = new JSONObject(json);
                for (Iterator<String> it = obj.keys(); it.hasNext(); ) {
                    String name = it.next();
                    result.put(name, obj.getLong(name));
                }
            } catch (JSONException e) {
                Log.e(TAG, "Bad node delay cache: %s", e.getMessage());
            }
        }
        return result;
    }

    private static void writeLastDelays(Context context, Map<String, Long> delays) {
        if (!delays.isEmpty()) {
            AppPrefs.instance(context).setXrayNodeDelays(new JSONObject(delays).toString());
        }
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

    private static int countReachable(List<ProxyNode> nodes) {
        int count = 0;
        for (ProxyNode node : nodes) {
            if (node.delayMs > 0) {
                count++;
            }
        }
        return count;
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
}
