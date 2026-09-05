package com.v2ray.common.util;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.nio.charset.StandardCharsets;

public final class ByteBufUtils {
    private ByteBufUtils() {}

    public static byte[] toByteArray(ByteBuf buf) {
        if (buf.hasArray() && buf.arrayOffset() == 0 && buf.readableBytes() == buf.array().length) {
            return buf.array();
        }
        byte[] bytes = new byte[buf.readableBytes()];
        buf.getBytes(buf.readerIndex(), bytes);
        return bytes;
    }

    public static String readString(ByteBuf buf, int length) {
        byte[] bytes = new byte[length];
        buf.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public static ByteBuf fromString(String str) {
        return Unpooled.copiedBuffer(str, StandardCharsets.UTF_8);
    }
}
