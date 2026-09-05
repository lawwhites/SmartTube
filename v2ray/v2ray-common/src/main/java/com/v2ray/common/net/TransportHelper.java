package com.v2ray.common.net;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TransportHelper manages Netty EventLoopGroups, Channel implementations,
 * and socket options optimized for mobile (Android ART) and desktop/server environments.
 */
public final class TransportHelper {
    private static final Logger logger = LoggerFactory.getLogger(TransportHelper.class);

    private static final boolean IS_ANDROID;
    private static final boolean EPOLL_AVAILABLE;
    private static final boolean KQUEUE_AVAILABLE;

    /**
     * Mobile-optimized watermarks: 32KB low, 64KB high.
     * Prevents buffer bloat and memory spikes on cellular connections.
     */
    public static final WriteBufferWaterMark DEFAULT_WATER_MARK =
            new WriteBufferWaterMark(32 * 1024, 64 * 1024);

    static {
        // Detect Android runtime (ART / Dalvik)
        String vmName = System.getProperty("java.vm.name", "");
        String vendor = System.getProperty("java.vendor", "");
        IS_ANDROID = vmName.contains("Dalvik") || vmName.contains("ART") || vendor.contains("Android");

        // Dynamically probe for Netty native transports without failing on missing JNI/libc
        boolean epoll = false;
        try {
            epoll = io.netty.channel.epoll.Epoll.isAvailable();
        } catch (Throwable t) {
            epoll = false;
        }
        EPOLL_AVAILABLE = epoll;

        boolean kqueue = false;
        try {
            kqueue = io.netty.channel.kqueue.KQueue.isAvailable();
        } catch (Throwable t) {
            kqueue = false;
        }
        KQUEUE_AVAILABLE = kqueue;

        logger.info("TransportHelper initialized - Platform: {}, Epoll: {}, KQueue: {}",
                IS_ANDROID ? "Android" : "Standard JVM",
                EPOLL_AVAILABLE,
                KQUEUE_AVAILABLE);
    }

    private TransportHelper() {}

    public static boolean isAndroid() {
        return IS_ANDROID;
    }

    public static boolean isEpollAvailable() {
        return EPOLL_AVAILABLE;
    }

    public static boolean isKQueueAvailable() {
        return KQUEUE_AVAILABLE;
    }

    /**
     * Calculates optimal worker thread count.
     * On Android: bounds between 2 and 4 threads to avoid waking up big CPU cores and saving battery.
     * On Server: defaults to 2 * availableProcessors.
     */
    public static int optimalWorkerThreads() {
        int cores = Runtime.getRuntime().availableProcessors();
        if (IS_ANDROID) {
            // ARM big.LITTLE mobile friendly
            return Math.min(4, Math.max(2, cores / 2));
        }
        return Math.max(2, cores * 2);
    }

    /**
     * Boss threads for local proxy listening (1 thread is optimal).
     */
    public static int optimalBossThreads() {
        return 1;
    }

    /**
     * Creates an EventLoopGroup with the specified thread count and prefix.
     */
    public static EventLoopGroup createEventLoopGroup(int nThreads, String threadNamePrefix) {
        DefaultThreadFactory factory = new DefaultThreadFactory(threadNamePrefix, true);
        if (EPOLL_AVAILABLE) {
            return new io.netty.channel.epoll.EpollEventLoopGroup(nThreads, factory);
        }
        if (KQUEUE_AVAILABLE) {
            return new io.netty.channel.kqueue.KQueueEventLoopGroup(nThreads, factory);
        }
        return new NioEventLoopGroup(nThreads, factory);
    }

    public static EventLoopGroup createBossGroup() {
        return createEventLoopGroup(optimalBossThreads(), "v2ray-boss");
    }

    public static EventLoopGroup createWorkerGroup() {
        return createEventLoopGroup(optimalWorkerThreads(), "v2ray-worker");
    }

    /**
     * Returns matching ServerSocketChannel class for the detected transport.
     */
    public static Class<? extends ServerSocketChannel> serverSocketChannelClass() {
        if (EPOLL_AVAILABLE) {
            return io.netty.channel.epoll.EpollServerSocketChannel.class;
        }
        if (KQUEUE_AVAILABLE) {
            return io.netty.channel.kqueue.KQueueServerSocketChannel.class;
        }
        return NioServerSocketChannel.class;
    }

    /**
     * Returns default SocketChannel class for outbounds.
     */
    public static Class<? extends SocketChannel> socketChannelClass() {
        if (EPOLL_AVAILABLE) {
            return io.netty.channel.epoll.EpollSocketChannel.class;
        }
        if (KQUEUE_AVAILABLE) {
            return io.netty.channel.kqueue.KQueueSocketChannel.class;
        }
        return NioSocketChannel.class;
    }

    /**
     * Returns matching SocketChannel class based on the given inbound channel's EventLoop.
     * Prevents Netty 'incompatible event loop' errors when dialing outbounds on the same EventLoop.
     */
    public static Class<? extends SocketChannel> socketChannelClass(Channel inboundChannel) {
        if (inboundChannel != null) {
            return socketChannelClass(inboundChannel.eventLoop());
        }
        return socketChannelClass();
    }

    public static Class<? extends SocketChannel> socketChannelClass(EventLoop eventLoop) {
        if (eventLoop != null) {
            String className = eventLoop.getClass().getName();
            if (EPOLL_AVAILABLE && className.contains("Epoll")) {
                return io.netty.channel.epoll.EpollSocketChannel.class;
            }
            if (KQUEUE_AVAILABLE && className.contains("KQueue")) {
                return io.netty.channel.kqueue.KQueueSocketChannel.class;
            }
        }
        return NioSocketChannel.class;
    }

    /**
     * Applies mobile and performance-tuned options to ServerBootstrap.
     */
    public static void applyServerOptions(ServerBootstrap b) {
        b.option(ChannelOption.SO_REUSEADDR, true)
         .option(ChannelOption.SO_BACKLOG, 128)
         .childOption(ChannelOption.SO_KEEPALIVE, true)
         .childOption(ChannelOption.TCP_NODELAY, true)
         .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, DEFAULT_WATER_MARK)
         .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT);
    }

    /**
     * Applies mobile and performance-tuned options to client Bootstrap.
     */
    public static void applyClientOptions(Bootstrap b) {
        b.option(ChannelOption.SO_KEEPALIVE, true)
         .option(ChannelOption.TCP_NODELAY, true)
         .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)
         .option(ChannelOption.WRITE_BUFFER_WATER_MARK, DEFAULT_WATER_MARK)
         .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT);
    }
}
