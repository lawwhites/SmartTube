package com.v2ray.router;

import com.v2ray.common.net.Destination;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class RouterTest {

    @Test
    public void testRoutingRules() {
        DefaultRouter router = new DefaultRouter("direct");

        RoutingRule blockRule = new RoutingRule("blocked");
        blockRule.getDomains().add("domain:badsite.com");
        blockRule.getDomains().add("keyword:tracker");
        blockRule.getDomains().add("full:exact.match.org");
        router.addRule(blockRule);

        RoutingRule specialRule = new RoutingRule("special");
        specialRule.getPorts().add(8443);
        router.addRule(specialRule);

        // Test suffix domain match
        assertEquals("blocked", router.route(Destination.tcp("badsite.com", 80), "inbound"));
        assertEquals("blocked", router.route(Destination.tcp("sub.badsite.com", 80), "inbound"));

        // Test keyword match
        assertEquals("blocked", router.route(Destination.tcp("my-tracker-service.com", 443), "inbound"));

        // Test full match
        assertEquals("blocked", router.route(Destination.tcp("exact.match.org", 80), "inbound"));

        // Test port match
        assertEquals("special", router.route(Destination.tcp("example.com", 8443), "inbound"));

        // Test fallback to default
        assertEquals("direct", router.route(Destination.tcp("github.com", 443), "inbound"));
    }

    @Test
    public void testGeoAndRegexpRules() {
        DefaultRouter router = new DefaultRouter("proxy");

        RoutingRule directRule = new RoutingRule("direct");
        directRule.getDomains().add("geosite:cn");
        directRule.getDomains().add("regexp:^.*\\.qq\\.com$");
        directRule.getIpCidrs().add("geoip:private");
        router.addRule(directRule);

        RoutingRule blockRule = new RoutingRule("block");
        blockRule.getDomains().add("geosite:category-ads-all");
        router.addRule(blockRule);

        // geosite:cn
        assertEquals("direct", router.route(Destination.tcp("baidu.com", 443), "inbound"));
        assertEquals("direct", router.route(Destination.tcp("news.sina.com.cn", 80), "inbound"));

        // regexp
        assertEquals("direct", router.route(Destination.tcp("im.qq.com", 443), "inbound"));

        // geoip:private
        assertEquals("direct", router.route(Destination.tcp("192.168.1.1", 80), "inbound"));
        assertEquals("direct", router.route(Destination.tcp("10.0.0.1", 80), "inbound"));
        assertEquals("direct", router.route(Destination.tcp("127.0.0.1", 8080), "inbound"));

        // ads
        assertEquals("block", router.route(Destination.tcp("adservice.google.com", 443), "inbound"));

        // foreign site falls through to default "proxy"
        assertEquals("proxy", router.route(Destination.tcp("youtube.com", 443), "inbound"));
    }
}
