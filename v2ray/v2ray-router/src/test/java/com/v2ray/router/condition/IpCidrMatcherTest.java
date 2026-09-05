package com.v2ray.router.condition;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class IpCidrMatcherTest {

    @Test
    public void testIpv4CidrMatching() {
        IpCidrMatcher matcher16 = new IpCidrMatcher("192.168.0.0/16");
        assertTrue(matcher16.matches("192.168.1.1"));
        assertTrue(matcher16.matches("192.168.254.254"));
        assertFalse(matcher16.matches("192.169.1.1"));
        assertFalse(matcher16.matches("10.0.0.1"));

        IpCidrMatcher matcher24 = new IpCidrMatcher("10.10.10.0/24");
        assertTrue(matcher24.matches("10.10.10.5"));
        assertFalse(matcher24.matches("10.10.11.5"));

        IpCidrMatcher matcher32 = new IpCidrMatcher("127.0.0.1/32");
        assertTrue(matcher32.matches("127.0.0.1"));
        assertFalse(matcher32.matches("127.0.0.2"));
    }
}
