package com.liskovsoft.smartyoutubetv2.common.proxy.xray;

import android.content.Context;
import android.os.Build;

import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.sharedutils.rx.RxHelper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.v2ray.config.ConfigLoader;
import com.v2ray.config.model.V2RayConfig;
import com.v2ray.core.instance.V2RayInstance;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Manages the embedded v2ray-java core running as a hidden local SOCKS proxy
 * on 127.0.0.1:10808 (no VpnService, no user-visible proxy server).
 *
 * App traffic is routed into it via the existing ProxyManager
 * (JVM socksProxyHost/socksProxyPort system properties).
 *
 * Historically this class drove the gomobile Xray-core (libv2ray aar); it now
 * drives the pure-Java v2ray-java core with the same public surface.
 */
public class XrayManager {
    private static final String TAG = XrayManager.class.getSimpleName();
    public static final String LOCAL_HOST = "127.0.0.1";
    public static final int LOCAL_PORT = 10808;
    // Measure against YouTube directly: the real-delay test doubles as the
    // YouTube reachability check (a node fast on gstatic but blocking
    // YouTube would pass a gstatic-only test).
    private static final String DELAY_TEST_URL = "https://www.youtube.com/generate_204";
    private static final long MEASURE_TIMEOUT_MS = 8_000;
    private static final int MEASURE_PORT_BASE = 23200;
    private static final int MEASURE_PORT_RANGE = 1000;
    private static final AtomicInteger sMeasurePort = new AtomicInteger(MEASURE_PORT_BASE);

    private static XrayManager sInstance;
    private final Context mContext;
    private V2RayInstance mInstance;

    private XrayManager(Context context) {
        mContext = context.getApplicationContext();
    }

    public static XrayManager instance(Context context) {
        if (sInstance == null) {
            sInstance = new XrayManager(context);
        }
        return sInstance;
    }

    /**
     * The pure-Java core has no native library constraint. AES/GCM support is
     * reliable from API 21 up; gate there to stay conservative on legacy ROMs.
     */
    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= 21;
    }

    public boolean isRunning() {
        try {
            return mInstance != null && mInstance.isRunning();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Starts the core on a background thread with the given outbound JSON,
     * waits until the local SOCKS port accepts connections,
     * then runs onStarted on the main thread.
     */
    public void startAsync(String outboundJson, Runnable onStarted) {
        RxHelper.runAsyncUser(
                () -> {
                    try {
                        startSync(outboundJson);
                        waitForPort(LOCAL_PORT, 10_000);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                },
                error -> Log.e(TAG, "Xray start failed: %s", error.getMessage()),
                () -> {
                    if (isRunning() && onStarted != null) {
                        onStarted.run();
                    }
                });
    }

    public synchronized void startSync(String outboundJson) throws Exception {
        if (isRunning()) {
            return;
        }
        ensureEnv();
        V2RayConfig config = ConfigLoader.load(buildConfig(outboundJson));
        V2RayInstance instance = ConfigLoader.createInstance(config);
        instance.start();
        mInstance = instance;
    }

    public synchronized void stop() {
        if (mInstance != null) {
            try {
                mInstance.close();
            } catch (Exception e) {
                Log.e(TAG, "Xray stop failed: %s", e.getMessage());
            }
            mInstance = null;
        }
    }

    /**
     * Measures the real delay of a single node by spinning up a temporary core
     * instance with a private SOCKS inbound, then timing a YouTube generate_204
     * request through it.
     * @return RTT in ms, or a negative value if unreachable.
     */
    public static long measureNodeDelay(ProxyNode node) {
        int port = nextMeasurePort();
        V2RayInstance instance = null;
        try {
            V2RayConfig config = ConfigLoader.load(buildMeasureConfig(node.getOutbound(), port));
            instance = ConfigLoader.createInstance(config);
            instance.start();
            waitForPort(port, 5_000);

            OkHttpClient client = new OkHttpClient.Builder()
                    .proxy(new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(LOCAL_HOST, port)))
                    .connectTimeout(MEASURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .readTimeout(MEASURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .build();

            long start = System.currentTimeMillis();
            try (Response response = client.newCall(new Request.Builder().url(DELAY_TEST_URL).build()).execute()) {
                if (response.code() != 204) {
                    return -1;
                }
            }
            return System.currentTimeMillis() - start;
        } catch (Exception e) {
            Log.e(TAG, "Delay measure failed for %s: %s", node.name, e.getMessage());
            return -1;
        } finally {
            if (instance != null) {
                try {
                    instance.close();
                } catch (Exception ignored) {
                    // ignore
                }
            }
        }
    }

    private static int nextMeasurePort() {
        return sMeasurePort.updateAndGet(p -> p >= MEASURE_PORT_BASE + MEASURE_PORT_RANGE ? MEASURE_PORT_BASE : p + 1);
    }

    /**
     * Kept for API compatibility with the gomobile era: the pure-Java core
     * needs no environment setup. Everything is initialized per instance.
     */
    public synchronized void ensureEnv() {
        // no-op
    }

    private static void waitForPort(int port, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(LOCAL_HOST, port), 1_000);
                return;
            } catch (IOException e) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ignored) {
                    return;
                }
            }
        }
        Log.e(TAG, "Timed out waiting for local SOCKS port %d", port);
    }

    /** Full config: local SOCKS inbound + selected outbound + direct fallback. */
    private static String buildConfig(String outboundJson) throws JSONException {
        JSONObject config = new JSONObject();
        config.put("log", new JSONObject().put("loglevel", "warning"));

        config.put("inbounds", new JSONArray().put(new JSONObject()
                .put("tag", "socks")
                .put("listen", LOCAL_HOST)
                .put("port", LOCAL_PORT)
                .put("protocol", "socks")
                .put("settings", new JSONObject()
                        .put("auth", "noauth")
                        .put("udp", true))));

        JSONArray outbounds = new JSONArray();
        outbounds.put(new JSONObject(outboundJson));
        outbounds.put(new JSONObject()
                .put("tag", "direct")
                .put("protocol", "freedom"));
        config.put("outbounds", outbounds);

        return config.toString();
    }

    /** Measure config: private SOCKS inbound + node as the only way out. */
    private static String buildMeasureConfig(JSONObject outbound, int socksPort) throws JSONException {
        JSONObject config = new JSONObject();
        config.put("log", new JSONObject().put("loglevel", "warning"));
        config.put("inbounds", new JSONArray().put(new JSONObject()
                .put("tag", "socks")
                .put("listen", LOCAL_HOST)
                .put("port", socksPort)
                .put("protocol", "socks")
                .put("settings", new JSONObject().put("auth", "noauth"))));
        config.put("outbounds", new JSONArray().put(outbound));
        return config.toString();
    }
}
