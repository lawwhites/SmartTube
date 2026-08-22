package com.liskovsoft.smartyoutubetv2.common.proxy.xray;

import android.content.Context;
import android.os.Build;

import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.sharedutils.rx.RxHelper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

import go.Seq;
import libv2ray.CoreCallbackHandler;
import libv2ray.CoreController;
import libv2ray.Libv2ray;

/**
 * Manages the embedded Xray-core running as a hidden local SOCKS proxy
 * on 127.0.0.1:10808 (no VpnService, no user-visible proxy server).
 *
 * App traffic is routed into it via the existing ProxyManager
 * (JVM socksProxyHost/socksProxyPort system properties).
 */
public class XrayManager {
    private static final String TAG = XrayManager.class.getSimpleName();
    public static final String LOCAL_HOST = "127.0.0.1";
    public static final int LOCAL_PORT = 10808;
    private static final String DELAY_TEST_URL = "https://www.gstatic.com/generate_204";
    private static XrayManager sInstance;
    private final Context mContext;
    private CoreController mController;
    private boolean mEnvInitialized;

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
     * The official libv2ray aar is built with gomobile -androidapi 24,
     * so the native library may fail to load on older devices.
     */
    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= 24;
    }

    public boolean isRunning() {
        try {
            return mController != null && mController.getIsRunning();
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
                        waitForPort(10_000);
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
        mController = Libv2ray.newCoreController(new CoreCallbackHandler() {
            @Override
            public long onEmitStatus(long l, String s) {
                Log.d(TAG, "Xray status: %s", s);
                return 0;
            }

            @Override
            public long shutdown() {
                return 0;
            }

            @Override
            public long startup() {
                Log.d(TAG, "Xray core started");
                return 0;
            }
        });
        // tunFd = 0: proxy-only mode, no VpnService.
        mController.startLoop(buildConfig(outboundJson), 0);
    }

    public synchronized void stop() {
        if (mController != null) {
            try {
                mController.stopLoop();
            } catch (Exception e) {
                Log.e(TAG, "Xray stop failed: %s", e.getMessage());
            }
            mController = null;
        }
    }

    /**
     * Measures the real delay of a single node by spinning up a temporary
     * core instance inside the native lib (same approach as v2rayNG).
     * @return RTT in ms, or a negative value if unreachable.
     */
    public static long measureNodeDelay(ProxyNode node) {
        try {
            return Libv2ray.measureOutboundDelay(buildMeasureConfig(node.getOutbound()), DELAY_TEST_URL);
        } catch (Exception e) {
            Log.e(TAG, "Delay measure failed for %s: %s", node.name, e.getMessage());
            return -1;
        }
    }

    /**
     * Initializes the gomobile context and the Xray environment once per process.
     * Must run before ANY native call (startLoop, measureOutboundDelay).
     */
    public synchronized void ensureEnv() {
        if (mEnvInitialized) {
            return;
        }
        Seq.setContext(mContext);
        File assetDir = new File(mContext.getFilesDir(), "xray");
        //noinspection ResultOfMethodCallIgnored
        assetDir.mkdirs();
        Libv2ray.initCoreEnv(assetDir.getAbsolutePath(), "");
        mEnvInitialized = true;
    }

    private void waitForPort(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(LOCAL_HOST, LOCAL_PORT), 1_000);
                return;
            } catch (IOException e) {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException ignored) {
                    return;
                }
            }
        }
        Log.e(TAG, "Timed out waiting for Xray SOCKS port");
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

    /** Trimmed config for delay measurement: no inbound, node as the only way out. */
    private static String buildMeasureConfig(JSONObject outbound) throws JSONException {
        JSONObject config = new JSONObject();
        config.put("log", new JSONObject().put("loglevel", "warning"));
        config.put("outbounds", new JSONArray().put(outbound));
        return config.toString();
    }
}
