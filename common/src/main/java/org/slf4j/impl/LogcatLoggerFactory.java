package org.slf4j.impl;

import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;

import java.util.concurrent.ConcurrentHashMap;

/** Logger factory producing logcat-backed loggers. */
public class LogcatLoggerFactory implements ILoggerFactory {
    private final ConcurrentHashMap<String, Logger> mLoggers = new ConcurrentHashMap<>();

    @Override
    public Logger getLogger(String name) {
        Logger logger = mLoggers.get(name);
        if (logger == null) {
            Logger created = new LogcatLoggerAdapter(name);
            Logger prev = mLoggers.putIfAbsent(name, created);
            logger = prev != null ? prev : created;
        }
        return logger;
    }
}
