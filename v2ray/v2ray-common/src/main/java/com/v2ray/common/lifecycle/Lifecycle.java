package com.v2ray.common.lifecycle;

/**
 * Common lifecycle interface for startable and stoppable V2Ray components.
 */
public interface Lifecycle {
    void start() throws Exception;
    void close() throws Exception;
    boolean isRunning();
}
