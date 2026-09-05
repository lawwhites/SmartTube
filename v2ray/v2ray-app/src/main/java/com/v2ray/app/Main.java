package com.v2ray.app;

import com.v2ray.config.ConfigLoader;
import com.v2ray.config.model.V2RayConfig;
import com.v2ray.core.instance.V2RayInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);
    private static final String VERSION = "1.0.0 (V2Ray Java Core)";

    public static void main(String[] args) {
        String configPath = "config.json";
        boolean testOnly = false;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("-config".equalsIgnoreCase(arg) || "-c".equalsIgnoreCase(arg)) {
                if (i + 1 < args.length) {
                    configPath = args[++i];
                }
            } else if ("-test".equalsIgnoreCase(arg) || "-t".equalsIgnoreCase(arg)) {
                testOnly = true;
            } else if ("-version".equalsIgnoreCase(arg) || "-v".equalsIgnoreCase(arg)) {
                System.out.println("V2Ray Java " + VERSION);
                System.out.println("A modular pure Java implementation of V2Ray Core.");
                return;
            } else if ("-help".equalsIgnoreCase(arg) || "-h".equalsIgnoreCase(arg)) {
                printHelp();
                return;
            }
        }

        File configFile = new File(configPath);
        if (!configFile.exists()) {
            logger.error("Configuration file not found: {}", configFile.getAbsolutePath());
            System.exit(1);
        }

        try {
            logger.info("Loading configuration from {}", configFile.getAbsolutePath());
            V2RayConfig config = ConfigLoader.load(configFile);
            logger.info("Configuration parsed successfully. Inbounds: {}, Outbounds: {}",
                    config.getInbounds().size(), config.getOutbounds().size());

            if (testOnly) {
                logger.info("Configuration test passed successfully.");
                return;
            }

            V2RayInstance instance = ConfigLoader.createInstance(config);

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                logger.info("Shutdown hook triggered. Stopping V2Ray...");
                instance.close();
            }, "v2ray-shutdown-thread"));

            instance.start();
            logger.info("V2Ray Java is running. Press Ctrl+C to stop.");

            Thread.currentThread().join();
        } catch (Exception e) {
            logger.error("Failed to start V2Ray: {}", e.getMessage(), e);
            System.exit(1);
        }
    }

    private static void printHelp() {
        System.out.println("Usage: java -jar v2ray-app.jar [options]");
        System.out.println("Options:");
        System.out.println("  -c, -config <file>   Specify configuration file (default: config.json)");
        System.out.println("  -t, -test            Test configuration file syntax only");
        System.out.println("  -v, -version         Display version information");
        System.out.println("  -h, -help            Display this help message");
    }
}
