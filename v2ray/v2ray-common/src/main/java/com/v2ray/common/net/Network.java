package com.v2ray.common.net;

public enum Network {
    TCP,
    UDP,
    UNKNOWN;

    public static Network fromString(String str) {
        if (str == null) return UNKNOWN;
        String s = str.trim().toUpperCase();
        if ("TCP".equals(s)) return TCP;
        if ("UDP".equals(s)) return UDP;
        return UNKNOWN;
    }
}
