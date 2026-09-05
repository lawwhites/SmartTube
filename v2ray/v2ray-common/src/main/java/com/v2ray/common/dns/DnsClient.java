package com.v2ray.common.dns;

import com.v2ray.common.lifecycle.Feature;

import java.net.InetAddress;
import java.util.List;

/**
 * DNS client feature for domain name resolution.
 * Equivalent to features/dns.Client in Go.
 */
public interface DnsClient extends Feature {
    List<InetAddress> lookup(String domain) throws Exception;
    InetAddress lookupFirst(String domain) throws Exception;

    @Override
    default Class<? extends Feature> getFeatureType() {
        return DnsClient.class;
    }
}
