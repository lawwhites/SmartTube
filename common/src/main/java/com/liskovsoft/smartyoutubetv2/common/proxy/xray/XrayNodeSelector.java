package com.liskovsoft.smartyoutubetv2.common.proxy.xray;

import android.content.Context;

import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.sharedutils.okhttp.OkHttpManager;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.prefs.AppPrefs;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

import io.reactivex.Flowable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Loads the node list (built-in asset by default, custom Clash subscription
 * URL if set), measures the real delay of every node (concurrently) and
 * shows a single-choice dialog sorted by latency with the fastest node on top.
 */
public class XrayNodeSelector {
    private static final String TAG = XrayNodeSelector.class.getSimpleName();
    private static final int PING_CONCURRENCY = 20;
    private static final int MEASURE_CONCURRENCY = 5;
    private static final int PING_TIMEOUT_MS = 3_000;
    /** Only the fastest ping survivors get the (slow) real-delay test. */
    private static final int REAL_TEST_TOP_N = 15;
    private static final String BUILTIN_SUB_ASSET = "xray_builtin_sub.yaml";
    /** Default subscription: keeps the node list fresh (uuid/port rotation); the bundled asset is the offline fallback. */
    private static final String DEFAULT_SUB_URL =
            "https://666473.sub-cloudflare.com/ssp/huojian/link/3FAxvd1CNVjviVKE?clash=3&extend=1";

    /**
     * Loads the subscription YAML: user URL if set, otherwise the built-in
     * subscription URL; falls back to the bundled asset when fetching fails.
     */
    public static String loadSubscription(Context context) {
        String url = AppPrefs.instance(context).getXraySubscriptionUrl();
        String fetchUrl = url.isEmpty() ? DEFAULT_SUB_URL : url;
        try {
            return download(fetchUrl);
        } catch (Exception e) {
            Log.e(TAG, "Subscription fetch failed (%s), using bundled list: %s", fetchUrl, e.getMessage());
            try {
                return loadBuiltin(context);
            } catch (Exception e2) {
                Log.e(TAG, "Bundled subscription missing: %s", e2.getMessage());
                return "";
            }
        }
    }

    public static void show(Context context) {
        MessageHelpers.showMessage(context, R.string.xray_loading_nodes);

        Flowable.just("")
                .map(u -> loadSubscription(context))
                // Resolve domain-based nodes via the config's DoH servers,
                // one variant per candidate IP (see DohResolver).
                .map(yaml -> DohResolver.expandWithResolvedIps(yaml, ClashConfigParser.parse(yaml)))
                // Phase 1: fast TCP ping of every node.
                .flatMap(nodes -> Flowable.fromIterable(nodes)
                        .flatMap(node -> Flowable.fromCallable(() -> {
                                    node.delayMs = tcpPing(node);
                                    return node;
                                }).subscribeOn(Schedulers.io()),
                                PING_CONCURRENCY)
                        .toList()
                        .map(list -> {
                            sortByDelay(list);
                            DohResolver.dedupeByName(list);
                            return list;
                        })
                        .toFlowable())
                // Phase 2: real delay through the node, top survivors only
                // (each measure spins a temporary core, way too slow for all).
                .flatMap(nodes -> {
                    List<ProxyNode> top = new ArrayList<>();
                    for (ProxyNode node : nodes) {
                        if (node.delayMs > 0 && top.size() < REAL_TEST_TOP_N) {
                            top.add(node);
                        }
                    }
                    if (top.isEmpty()) {
                        return Flowable.just(nodes);
                    }
                    XrayManager.instance(context).ensureEnv();
                    return Flowable.fromIterable(top)
                            .flatMap(node -> Flowable.fromCallable(() -> {
                                        long realDelay = XrayManager.measureNodeDelay(node);
                                        if (realDelay > 0) {
                                            node.delayMs = realDelay;
                                        }
                                        return node;
                                    }).subscribeOn(Schedulers.io()),
                                    MEASURE_CONCURRENCY)
                            .toList()
                            .map(tested -> sortByDelay(nodes))
                            .toFlowable();
                })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        nodes -> showNodeDialog(context, nodes),
                        error -> {
                            Log.e(TAG, "Failed to load subscription: %s", error.getMessage());
                            MessageHelpers.showMessage(context, R.string.xray_subscription_load_failed);
                        });
    }

    /** TCP connect time to the server, ms; -1 if unreachable. */
    private static long tcpPing(ProxyNode node) {
        long start = System.currentTimeMillis();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(node.server, node.port), PING_TIMEOUT_MS);
            return System.currentTimeMillis() - start;
        } catch (Exception e) {
            return -1;
        }
    }

    /** Reads the node list bundled in the APK assets. */
    private static String loadBuiltin(Context context) throws Exception {
        InputStream in = context.getAssets().open(BUILTIN_SUB_ASSET);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16_384];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        in.close();
        return out.toString("UTF-8");
    }

    /** Downloads the subscription bypassing any configured proxy. */
    public static String download(String url) throws Exception {
        Request request = new Request.Builder().url(url).build();
        try (Response response = OkHttpManager.instance().getClient()
                .newBuilder()
                .proxy(Proxy.NO_PROXY)
                .build()
                .newCall(request)
                .execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IllegalStateException("HTTP " + response.code());
            }
            return response.body().string();
        }
    }

    /** Reachable nodes first, sorted by delay; unreachable ones last. */
    private static List<ProxyNode> sortByDelay(List<ProxyNode> nodes) {
        List<ProxyNode> reachable = new ArrayList<>();
        List<ProxyNode> unreachable = new ArrayList<>();
        for (ProxyNode node : nodes) {
            (node.delayMs > 0 ? reachable : unreachable).add(node);
        }
        reachable.sort((a, b) -> Long.compare(a.delayMs, b.delayMs));
        reachable.addAll(unreachable);
        return reachable;
    }

    private static void showNodeDialog(Context context, List<ProxyNode> nodes) {
        if (nodes.isEmpty()) {
            MessageHelpers.showMessage(context, R.string.xray_no_supported_nodes);
            return;
        }

        AppPrefs prefs = AppPrefs.instance(context);
        String selectedName = prefs.getXraySelectedNodeName();

        List<OptionItem> options = new ArrayList<>();
        for (ProxyNode node : nodes) {
            String delay = node.delayMs > 0
                    ? node.delayMs + " ms"
                    : context.getString(R.string.xray_node_timeout);
            options.add(UiOptionItem.from(node.name, delay,
                    option -> onNodeSelected(context, node),
                    node.name.equals(selectedName)));
        }

        AppDialogPresenter dialog = AppDialogPresenter.instance(context);
        dialog.appendRadioCategory(context.getString(R.string.xray_select_node), options);
        dialog.showDialog(context.getString(R.string.xray_select_node));
    }

    private static void onNodeSelected(Context context, ProxyNode node) {
        AppPrefs prefs = AppPrefs.instance(context);
        prefs.setXraySelectedNodeName(node.name);
        prefs.setXraySelectedOutbound(node.getOutboundJson());

        MessageHelpers.showMessage(context, context.getString(R.string.xray_node_selected, node.name));

        // Restart the core with the new node if the proxy is active.
        if (prefs.isXrayEnabled()) {
            XrayManager manager = XrayManager.instance(context);
            manager.stop();
            manager.startAsync(node.getOutboundJson(), null);
        }
    }
}
