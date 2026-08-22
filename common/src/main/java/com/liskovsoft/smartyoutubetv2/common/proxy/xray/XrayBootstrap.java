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
            reportProgress(context, callback, R.string.xray_detecting_nodes);
            // Remote subscription first (node credentials may rotate), bundled list as fallback.
            String yaml = XrayNodeSelector.loadSubscription(context);
            List<ProxyNode> nodes = ClashConfigParser.parse(yaml);
            if (nodes.isEmpty()) {
                Log.e(TAG, "No nodes to test");
                return null;
            }

            // Phase 1: TCP ping all
            pingAll(nodes);
            sortByDelay(nodes);
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
            measureRealDelay(top);
            // Re-order by the measured real delay: Phase 3 starts from the
            // node with the lowest latency, not the lowest TCP ping.
            sortByDelay(top);
            Log.d(TAG, "Phase 2 done, working: %d/%d", countReachable(top), top.size());
            reportProgress(context, callback, R.string.xray_phase2_result, countReachable(top));

            // Phase 3: YouTube check, best first
            XrayManager manager = XrayManager.instance(context);
            int attempts = 0;
            for (ProxyNode node : top) {
                if (node.delayMs <= 0 || attempts >= YOUTUBE_CHECK_ATTEMPTS) {
                    continue;
                }
                attempts++;
                reportProgress(context, callback, R.string.xray_phase3_checking, node.name);
                if (checkYouTube(manager, node)) {
                    Log.d(TAG, "Phase 3 passed with node: %s", node.name);
                    return node;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Node detection failed: %s", e.getMessage());
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

    private static boolean checkYouTube(XrayManager manager, ProxyNode node) {
        try {
            manager.stop();
            manager.startSync(node.getOutboundJson());
            waitForPort(10_000);

            OkHttpClient client = new OkHttpClient.Builder()
                    .proxy(new java.net.Proxy(java.net.Proxy.Type.SOCKS,
                            new InetSocketAddress(XrayManager.LOCAL_HOST, XrayManager.LOCAL_PORT)))
                    .build();
            try (Response response = client.newCall(
                    new Request.Builder().url(YOUTUBE_TEST_URL).build()).execute()) {
                return response.code() == 204 || response.code() == 200;
            }
        } catch (Exception e) {
            Log.e(TAG, "YouTube check failed for %s: %s", node.name, e.getMessage());
            return false;
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

    private static void measureRealDelay(List<ProxyNode> nodes) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(MEASURE_CONCURRENCY);
        List<Future<?>> futures = new ArrayList<>();
        for (ProxyNode node : nodes) {
            futures.add(pool.submit(() -> node.delayMs = XrayManager.measureNodeDelay(node)));
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
