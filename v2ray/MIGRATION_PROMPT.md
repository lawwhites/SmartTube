# 任务提示词：将已有 Android/Java 应用中的 Go 版本 v2ray.aar 替换为纯 Java 版 v2ray-java

> **使用说明**：本文件是一份标准化 AI 辅助编程 / 工程重构提示词（Prompt）。您可以直接将以下完整内容复制并发送给 AI 编程助手（如 Cursor、Claude、ChatGPT 等）或分配给 Android 工程师，用于指导将现有集成 Go 语言 AAR（如 `libv2ray.aar`、`v2rayNG` 核心库）的 Android/Java 应用无缝迁移至纯 Java 核心实现 `v2ray-java`。

---

```markdown
# 任务：将 Android 项目中的 Go 版本 v2ray.aar 替换为纯 Java 版 v2ray-java 核心

## 1. 任务背景与目标
当前 Android/Java 项目集成了基于 Go (gomobile) 编译的 V2Ray AAR（常见类名为 `libv2ray.Libv2ray`、`V2RayPoint` 等）。
目标是彻底移除该 Go AAR 及其附带的数十兆 Native `.so` 库，替换为轻量级、无 JNI 依赖、基于 Netty 与直接内存的纯 Java 实现：`v2ray-java`。

---

## 2. 依赖改造与 Gradle 配置 (build.gradle)

### 步骤 2.1：移除旧的 Go AAR 与 ABI 限制
1. 在 `app/build.gradle` 的 `dependencies` 中移除旧依赖：
   - 移除：`implementation files('libs/libv2ray.aar')` 或 `implementation(name: 'libv2ray', ext: 'aar')`。
   - 删除 `app/libs/` 下对应的 `.aar` 文件。
2. 检查 `android.defaultConfig.ndk.abiFilters`：
   - 如果之前为了兼容 Go 的 so 库强制限制了 ABI（如只保留 `arm64-v8a`, `armeabi-v7a`），现在由于纯 Java 版本无需 Native 动态库，可直接移除这些强制限制以优化包体积。

### 步骤 2.2：引入 v2ray-java 库及基础依赖
将 `v2ray-java` 编译生成的 Fat Jar（如 `v2ray-app-1.0.0-SNAPSHOT.jar`）放入 `app/libs/`，并在 `app/build.gradle` 中声明：
```groovy
dependencies {
    // 引入 v2ray-java 核心包
    implementation files('libs/v2ray-app-1.0.0-SNAPSHOT.jar')

    // 如果未打包为 Fat-Jar（单 jar），请补充基础运行时依赖：
    implementation 'io.netty:netty-all:4.1.108.Final'
    implementation 'com.fasterxml.jackson.core:jackson-databind:2.17.0'
    implementation 'org.bouncycastle:bcprov-jdk18on:1.78.1'
    implementation 'org.slf4j:slf4j-api:2.0.12'
}
```

---

## 3. 核心 API 替换与代码改造

请全局搜索项目中的 `Libv2ray`、`V2RayPoint`、`V2RayVPNServiceSupport` 等旧类引用，并按如下模式进行重构：

### 3.1 创建核心服务控制器 (V2RayServiceManager)
在项目中新增单例管理器，统一接管核心实例的生命周期与异常保护：

```java
package com.example.app.v2ray;

import com.v2ray.config.ConfigLoader;
import com.v2ray.config.model.V2RayConfig;
import com.v2ray.core.instance.V2RayInstance;
import android.util.Log;

public class V2RayServiceManager {
    private static final String TAG = "V2RayServiceManager";
    private static volatile V2RayServiceManager instance;
    private V2RayInstance v2rayInstance;

    private V2RayServiceManager() {}

    public static V2RayServiceManager getInstance() {
        if (instance == null) {
            synchronized (V2RayServiceManager.class) {
                if (instance == null) {
                    instance = new V2RayServiceManager();
                }
            }
        }
        return instance;
    }

    /**
     * 启动 V2Ray 核心实例
     * @param configJson 标准 V2Ray JSON 配置字符串
     * @return 是否启动成功
     */
    public synchronized boolean startV2Ray(String configJson) {
        stopV2Ray(); // 保证之前实例彻底释放
        try {
            Log.i(TAG, "Parsing V2Ray config...");
            V2RayConfig config = ConfigLoader.load(configJson);
            v2rayInstance = ConfigLoader.createInstance(config);
            v2rayInstance.start();
            Log.i(TAG, "V2Ray Java instance started successfully!");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to start V2Ray Java core", e);
            stopV2Ray();
            return false;
        }
    }

    /**
     * 停止 V2Ray 核心实例
     */
    public synchronized void stopV2Ray() {
        if (v2rayInstance != null) {
            try {
                v2rayInstance.close();
                Log.i(TAG, "V2Ray Java instance stopped.");
            } catch (Exception e) {
                Log.w(TAG, "Error stopping instance: " + e.getMessage());
            } finally {
                v2rayInstance = null;
            }
        }
    }

    /**
     * 查询运行状态
     */
    public synchronized boolean isRunning() {
        return v2rayInstance != null && v2rayInstance.isRunning();
    }
}
```

### 3.2 替换原有启动与停止调用点

#### 【改造前】Go AAR 常见调用模式：
```java
// 旧代码 (Go libv2ray):
Libv2ray.initV2Env(Utils.userAssetPath(this));
v2rayPoint = Libv2ray.newV2RayPoint(new V2RayVPNServiceSupport() { ... }, false);
v2rayPoint.setConfigureFileContent(configJsonString);
v2rayPoint.runLoop(false);
...
v2rayPoint.stopLoop();
```

#### 【改造后】纯 Java 调用模式：
```java
// 新代码 (纯 Java v2ray-java):
boolean success = V2RayServiceManager.getInstance().startV2Ray(configJsonString);
...
V2RayServiceManager.getInstance().stopV2Ray();
```

---

## 4. 网络流转与代理端口对接

1. `v2ray-java` 启动后，会根据传入的 `configJson` 自动在本地监听配置好的入站端口（例如本地 SOCKS5 代理 `127.0.0.1:10808`，本地 HTTP 代理 `127.0.0.1:10809`）。
2. 如果项目使用了 `VpnService` + `tun2socks`（如 `hev-socks5-tunnel`）：
   - 无需修改底层的 tun2socks 二进制，只需确保 tun2socks 将流量导向 `127.0.0.1:10808` 即可无缝透传至 `v2ray-java`。

---

## 5. Proguard 混淆规则 (proguard-rules.pro)

为防止 Android 编译 Release APK 时 Netty 反射与 Jackson 数据模型被 R8 混淆移除，请在 `proguard-rules.pro` 中加入：

```proguard
# v2ray-java 核心保留
-keep class com.v2ray.** { *; }

# Netty 运行时反射与 JNI 保留
-keep class io.netty.** { *; }
-dontwarn io.netty.**

# Jackson JSON 解析实体保留
-keepclassmembers class com.v2ray.config.model.** {
    <fields>;
    <methods>;
}

# BouncyCastle 密码学保留
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
```

---

## 6. 验证清单
1. 编译并打包 APK，验证 APK 体积是否缩减了 30MB~60MB（彻底移除了 arm64/armeabi/x86 等 ABI 的 Go native .so）。
2. 启动服务，在 Android Logcat 中检索是否有 `V2RayInstance started successfully` 日志。
3. 验证本地应用发起网络请求是否成功代理通网（如通过 SOCKS5 或 HTTP 访问网络）。
```
