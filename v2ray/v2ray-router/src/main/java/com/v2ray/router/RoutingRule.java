package com.v2ray.router;

import com.v2ray.common.net.Destination;
import com.v2ray.router.condition.IpCidrMatcher;
import io.netty.util.NetUtil;

import java.util.*;
import java.util.regex.Pattern;

public class RoutingRule {
    private String outboundTag;
    private final List<String> domains = new ArrayList<>();
    private final List<String> ipCidrs = new ArrayList<>();
    private final List<String> inboundTags = new ArrayList<>();
    private final List<Integer> ports = new ArrayList<>();

    private static final List<String> PRIVATE_IPS = Arrays.asList(
            "10.0.0.0/8",
            "172.16.0.0/12",
            "192.168.0.0/16",
            "127.0.0.0/8",
            "100.64.0.0/10",
            "169.254.0.0/16",
            "fc00::/7",
            "fe80::/10",
            "::1/128"
    );

    private static final Set<String> COMMON_CN_DOMAINS = new HashSet<>(Arrays.asList(
            "cn", "com.cn", "net.cn", "org.cn", "gov.cn", "edu.cn",
            "baidu.com", "qq.com", "taobao.com", "alipay.com", "jd.com",
            "163.com", "126.com", "sina.com.cn", "weibo.com", "bilibili.com",
            "zhihu.com", "sohu.com", "youku.com", "iqiyi.com", "douyin.com",
            "toutiao.com", "meituan.com", "dianping.com", "xiaomi.com"
    ));

    private static final Set<String> ADS_DOMAINS = new HashSet<>(Arrays.asList(
            "adservice.google.com", "doubleclick.net", "googlesyndication.com",
            "googleadservices.com", "admob.com", "pagead2.googlesyndication.com"
    ));

    private final List<IpCidrMatcher> compiledCidrs = new ArrayList<>();
    private final Map<String, Pattern> compiledRegexes = new HashMap<>();
    private boolean initialized = false;

    public RoutingRule() {}

    public RoutingRule(String outboundTag) {
        this.outboundTag = outboundTag;
    }

    private synchronized void ensureInitialized() {
        if (initialized) return;

        for (String cidr : ipCidrs) {
            if ("geoip:private".equalsIgnoreCase(cidr)) {
                for (String privCidr : PRIVATE_IPS) {
                    try {
                        compiledCidrs.add(new IpCidrMatcher(privCidr));
                    } catch (Exception ignored) {}
                }
            } else if ("geoip:cn".equalsIgnoreCase(cidr)) {
                // Common Chinese IP blocks or placeholder
                for (String privCidr : PRIVATE_IPS) {
                    try {
                        compiledCidrs.add(new IpCidrMatcher(privCidr));
                    } catch (Exception ignored) {}
                }
            } else {
                try {
                    compiledCidrs.add(new IpCidrMatcher(cidr));
                } catch (Exception ignored) {}
            }
        }

        for (String rule : domains) {
            if (rule.startsWith("regexp:")) {
                try {
                    compiledRegexes.put(rule, Pattern.compile(rule.substring(7), Pattern.CASE_INSENSITIVE));
                } catch (Exception ignored) {}
            }
        }

        initialized = true;
    }

    public boolean matches(Destination destination, String inboundTag) {
        ensureInitialized();

        if (!inboundTags.isEmpty()) {
            if (inboundTag == null || !inboundTags.contains(inboundTag)) {
                return false;
            }
        }

        if (!ports.isEmpty()) {
            if (!ports.contains(destination.getPort())) {
                return false;
            }
        }

        boolean hasDomainOrIp = !domains.isEmpty() || !ipCidrs.isEmpty();
        if (hasDomainOrIp) {
            String host = destination.getAddress();
            boolean isIp = NetUtil.isValidIpV4Address(host) || NetUtil.isValidIpV6Address(host);
            if (isIp) {
                if (ipCidrs.isEmpty()) {
                    return false;
                }
                boolean ipMatched = false;
                for (IpCidrMatcher matcher : compiledCidrs) {
                    if (matcher.matches(host)) {
                        ipMatched = true;
                        break;
                    }
                }
                if (!ipMatched) {
                    return false;
                }
            } else {
                if (domains.isEmpty()) {
                    return false;
                }
                boolean domainMatched = false;
                for (String domainRule : domains) {
                    if (matchDomain(domainRule, host)) {
                        domainMatched = true;
                        break;
                    }
                }
                if (!domainMatched) {
                    return false;
                }
            }
        }

        return true;
    }

    private boolean matchDomain(String rule, String host) {
        String lowerHost = host.toLowerCase();
        if (rule.startsWith("full:")) {
            return lowerHost.equalsIgnoreCase(rule.substring(5).toLowerCase());
        } else if (rule.startsWith("domain:")) {
            String suffix = rule.substring(7).toLowerCase();
            return lowerHost.equals(suffix) || lowerHost.endsWith("." + suffix);
        } else if (rule.startsWith("keyword:")) {
            return lowerHost.contains(rule.substring(8).toLowerCase());
        } else if (rule.startsWith("regexp:")) {
            Pattern p = compiledRegexes.get(rule);
            return p != null && p.matcher(host).find();
        } else if ("geosite:cn".equalsIgnoreCase(rule)) {
            for (String cnDomain : COMMON_CN_DOMAINS) {
                if (lowerHost.equals(cnDomain) || lowerHost.endsWith("." + cnDomain)) {
                    return true;
                }
            }
            return false;
        } else if ("geosite:category-ads-all".equalsIgnoreCase(rule)) {
            for (String adDomain : ADS_DOMAINS) {
                if (lowerHost.equals(adDomain) || lowerHost.endsWith("." + adDomain)) {
                    return true;
                }
            }
            return false;
        } else {
            // Default plain string match as suffix/domain match (standard V2Ray behavior)
            String lowerRule = rule.toLowerCase();
            return lowerHost.equals(lowerRule) || lowerHost.endsWith("." + lowerRule);
        }
    }

    public String getOutboundTag() {
        return outboundTag;
    }

    public void setOutboundTag(String outboundTag) {
        this.outboundTag = outboundTag;
    }

    public List<String> getDomains() {
        return domains;
    }

    public List<String> getIpCidrs() {
        return ipCidrs;
    }

    public List<String> getInboundTags() {
        return inboundTags;
    }

    public List<Integer> getPorts() {
        return ports;
    }
}
