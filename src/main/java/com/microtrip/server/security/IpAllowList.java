package com.microtrip.server.security;

import jakarta.servlet.http.HttpServletRequest;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Actuator 端点的来源 IP 白名单。
 *
 * <p><b>为什么需要这个类：</b>Actuator 的 {@code /actuator/health} 必须公开（探活用），
 * 但 {@code /actuator/metrics}、{@code /actuator/prometheus} 会暴露 JVM 堆内存、
 * 连接池等待数、各接口耗时分布等内部结构 —— 相当于给攻击者一份系统说明书。</p>
 *
 * <p>曾有做法是只设 {@code management.server.address=127.0.0.1}，但 Spring Boot 的
 * 该属性<strong>要求同时自定义 management.server.port 才生效</strong>；同端口场景下它是空操作，
 * 配置看着像加固了、实际没有。这里改为在安全过滤链上做真实判定，行为确定且可测。</p>
 *
 * <p><b>安全要点：</b>判定只依据 {@code request.getRemoteAddr()}，<strong>不读
 * X-Forwarded-For</strong>。否则攻击者伪造该头即可绕过白名单 —— 这是 IP 白名单最经典的漏洞。</p>
 *
 * <p>支持的写法：</p>
 * <ul>
 *   <li>精确 IPv4 / IPv6，如 {@code 127.0.0.1}、{@code ::1}</li>
 *   <li>IPv4 CIDR，支持 /8 ~ /32，如 {@code 10.0.0.0/8}、{@code 172.17.0.0/16}</li>
 * </ul>
 */
public final class IpAllowList {

    /** 单条 IPv4 CIDR 规则：网络地址与掩码都已转为 int 便于位运算 */
    private record Cidr4(int network, int mask) {
        boolean contains(int ip) {
            return (ip & mask) == network;
        }
    }

    private final List<Cidr4> cidr4s;
    private final Set<String> exact;

    private IpAllowList(List<Cidr4> cidr4s, Set<String> exact) {
        this.cidr4s = cidr4s;
        this.exact = exact;
    }

    /**
     * 解析白名单配置。
     *
     * @param patterns 逗号或分号分隔的 IP / CIDR 列表；无法识别的条目会被跳过（并由此处的 fail-open 变为 fail-closed）
     */
    public static IpAllowList of(String patterns) {
        List<Cidr4> cidrs = new ArrayList<>();
        Set<String> exacts = new LinkedHashSet<>();
        if (patterns == null || patterns.isBlank()) {
            // 空配置 = 谁都不放行（除 health/info，它们由调用方单独放行）。
            // 刻意不做「空即全放行」：配置漏填应当表现为拒绝，而不是敞开。
            return new IpAllowList(cidrs, exacts);
        }
        for (String raw : patterns.split("[,;\\s]+")) {
            String p = raw.trim();
            if (p.isEmpty()) {
                continue;
            }
            int slash = p.indexOf('/');
            if (slash > 0) {
                Cidr4 c = parseCidr4(p.substring(0, slash), p.substring(slash + 1));
                if (c != null) {
                    cidrs.add(c);
                    continue;
                }
                // IPv6 的 CIDR 暂不支持；前缀长度非法时同样降级为精确匹配。
                // 注意走的是「少放行」而非「多放行」：把 10.0.0.0/99 当成 10.0.0.0 处理，
                // 攻击者无法借此扩大可达范围。
                String fallback = normalize(p.substring(0, slash));
                if (isValidAddress(fallback)) {
                    exacts.add(fallback);
                }
            } else {
                // 配置侧也要归一化：否则白名单写 0:0:0:0:0:0:0:1 时，
                // 请求侧归一化后的 ::1 反而匹配不上 —— 归一化只做一半比不做更隐蔽。
                String entry = normalize(p);
                if (isValidAddress(entry)) {
                    exacts.add(entry);
                }
                // 非法条目（如 999.1.1.1、not-an-ip）直接丢弃：
                // 对白名单而言「丢弃」等于「不放行」，是 fail-closed 的正确方向。
            }
        }
        return new IpAllowList(cidrs, exacts);
    }

    /** 判断来源地址是否在白名单内 */
    public boolean contains(String ip) {
        if (ip == null || ip.isBlank()) {
            return false;
        }
        String candidate = normalize(ip);
        if (exact.contains(candidate)) {
            return true;
        }
        Integer v4 = toIpv4Int(candidate);
        if (v4 == null) {
            return false;
        }
        for (Cidr4 c : cidr4s) {
            if (c.contains(v4)) {
                return true;
            }
        }
        return false;
    }

    /** 便捷方法：判断请求来源是否在白名单内（只看 TCP 来源地址，不读任何代理头） */
    public boolean contains(HttpServletRequest request) {
        return request != null && contains((String) request.getRemoteAddr());
    }

    // ---------------- 内部解析 ----------------

    private static Cidr4 parseCidr4(String networkPart, String prefixPart) {
        Integer network = toIpv4Int(networkPart);
        if (network == null) {
            return null;
        }
        int prefix;
        try {
            prefix = Integer.parseInt(prefixPart.trim());
        } catch (NumberFormatException e) {
            return null;
        }
        if (prefix < 0 || prefix > 32) {
            return null;
        }
        // 掩码计算必须在 **int** 上做移位，不能用 long 再强转：
        // 0xFFFFFFFFL << 24 = 0xFFFFFFFF000000，强转取低 32 位得到 0x00000000，
        // 于是 /8 会变成「掩码全 0」= 放行全部 IPv4 —— 一个静默变成完全敞开的严重错误。
        // int 的 << 会自然丢弃溢出高位，正是这里需要的语义。
        // prefix=0 时 1<<32 会被 Java 按 mod 32 处理成 1<<0，故单独分支返回 0（不放行任何位）。
        int mask = (prefix == 0) ? 0 : (0xFFFFFFFF << (32 - prefix));
        return new Cidr4(network & mask, mask);
    }

    /**
     * 宽松的地址合法性校验：只用于把明显是垃圾的配置条目挡掉。
     *
     * <p>不追求严格的 IPv6 文法校验 —— 目的是让 {@code 999.1.1.1}、{@code not-an-ip}
     * 这类条目无法进入白名单（否则它们会以「精确匹配」的形式长期潜伏，
     * 掩盖配置写错的事实），而不是做一台完整的地址解析器。</p>
     */
    private static boolean isValidAddress(String s) {
        if (s == null || s.isBlank()) {
            return false;
        }
        if (s.indexOf(':') >= 0) {
            // 含冒号 → 视为 IPv6，只允许十六进制、冒号、点（IPv4-mapped）与百分号（scope id）
            return s.matches("[0-9A-Fa-f:.%]+");
        }
        return toIpv4Int(s) != null;
    }

    /** IPv4 点分十进制转 int；非 IPv4 返回 null */
    private static Integer toIpv4Int(String ip) {
        if (ip == null || ip.indexOf('.') < 0 || ip.indexOf(':') >= 0) {
            return null;
        }
        String[] parts = ip.split("\\.");
        if (parts.length != 4) {
            return null;
        }
        int result = 0;
        for (String part : parts) {
            int octet;
            try {
                octet = Integer.parseInt(part);
            } catch (NumberFormatException e) {
                return null;
            }
            if (octet < 0 || octet > 255) {
                return null;
            }
            result = (result << 8) | octet;
        }
        return result;
    }

    /**
     * 归一化常见等价写法。
     *
     * <p>最关键的是 IPv6 回环：Tomcat 在部分 JDK / 系统组合下返回 {@code 0:0:0:0:0:0:0:1}
     * 而非 {@code ::1}，若不做归一，「只允许本机」的配置在 IPv6 下会失效。</p>
     */
    private static String normalize(String ip) {
        String s = ip.trim();
        int pct = s.indexOf('%');           // 去掉 IPv6 的 scope id，如 fe80::1%eth0
        if (pct > 0) {
            s = s.substring(0, pct);
        }
        if ("0:0:0:0:0:0:0:1".equals(s) || "0000:0000:0000:0000:0000:0000:0000:0001".equals(s)) {
            return "::1";
        }
        if ("0:0:0:0:0:0:0:0".equals(s)) {
            return "::";
        }
        // IPv4-mapped IPv6，如 ::ffff:127.0.0.1 → 127.0.0.1
        int mapped = s.toLowerCase().lastIndexOf("::ffff:");
        if (mapped == 0) {
            String tail = s.substring(7);
            if (tail.indexOf('.') > 0) {
                return tail;
            }
        }
        return s;
    }
}
