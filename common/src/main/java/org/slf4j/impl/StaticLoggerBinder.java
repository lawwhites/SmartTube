package org.slf4j.impl;

import org.slf4j.ILoggerFactory;

/**
 * slf4j 1.7 static binding that routes to android.util.Log.
 * Exists so embedded libraries (v2ray-java core) get logcat visibility.
 */
public class StaticLoggerBinder {
    private static final StaticLoggerBinder SINGLETON = new StaticLoggerBinder();

    public static String REQUESTED_API_VERSION = "1.7.25";

    private final ILoggerFactory mFactory = new LogcatLoggerFactory();

    public static StaticLoggerBinder getSingleton() {
        return SINGLETON;
    }

    public ILoggerFactory getLoggerFactory() {
        return mFactory;
    }

    public String getLoggerFactoryClassStr() {
        return LogcatLoggerFactory.class.getName();
    }
}
