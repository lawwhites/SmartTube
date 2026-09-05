package com.v2ray.core;

import com.v2ray.core.instance.V2RayInstance;
import com.v2ray.proxy.blackhole.BlackholeOutboundHandler;
import com.v2ray.proxy.freedom.FreedomOutboundHandler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class V2RayInstanceTest {

    @Test
    public void testInstanceLifecycle() throws Exception {
        V2RayInstance instance = new V2RayInstance();
        instance.getOutboundManager().addHandler(new FreedomOutboundHandler("direct"));
        instance.getOutboundManager().addHandler(new BlackholeOutboundHandler("blocked"));

        assertFalse(instance.isRunning());

        instance.start();
        assertTrue(instance.isRunning());

        instance.close();
        assertFalse(instance.isRunning());
    }
}
