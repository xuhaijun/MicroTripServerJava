package com.microtrip.server;

import com.microtrip.server.security.IpAllowList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Actuator 来源 IP 白名单的纯逻辑单测。
 *
 * <p>为什么单独测这个类而不是只做集成测试：IP 判定是安全边界，
 * 必须覆盖「等价写法」「越界值」「伪造头」这些集成测试很难构造的情况。
 * 尤其是 IPv6 回环的多种写法 —— Tomcat 在不同 JDK/系统组合下返回
 * {@code 0:0:0:0:0:0:0:1} 而非 {@code ::1}，不做归一化的话，
 * 「只允许本机」的配置在 IPv6 环境下会静默失效。</p>
 */
class IpAllowListTest {

    // ============================================================
    // 1. 精确匹配
    // ============================================================

    @Test
    @DisplayName("精确 IPv4：默认白名单放行本机，拒绝外部地址")
    void exactIpv4() {
        IpAllowList list = IpAllowList.of("127.0.0.1");

        assertThat(list.contains("127.0.0.1")).isTrue();
        assertThat(list.contains("127.0.0.2")).isFalse();
        assertThat(list.contains("10.0.0.1")).isFalse();
        assertThat(list.contains("203.0.113.7")).isFalse();
    }

    @Test
    @DisplayName("多个地址用逗号/分号/空白分隔均可解析")
    void multipleSeparators() {
        assertThat(IpAllowList.of("127.0.0.1,10.1.2.3").contains("10.1.2.3")).isTrue();
        assertThat(IpAllowList.of("127.0.0.1;10.1.2.3").contains("10.1.2.3")).isTrue();
        assertThat(IpAllowList.of("127.0.0.1 10.1.2.3").contains("10.1.2.3")).isTrue();
        assertThat(IpAllowList.of(" 127.0.0.1 , 10.1.2.3 ").contains("10.1.2.3")).isTrue();
    }

    // ============================================================
    // 2. IPv6 回环的等价写法（关键：不做归一化会静默失效）
    // ============================================================

    @Test
    @DisplayName("IPv6 回环：::1 与 0:0:0:0:0:0:0:1 视为同一地址")
    void ipv6LoopbackEquivalents() {
        IpAllowList list = IpAllowList.of("127.0.0.1,::1");

        assertThat(list.contains("::1")).isTrue();
        assertThat(list.contains("0:0:0:0:0:0:0:1")).isTrue();
        assertThat(list.contains("0000:0000:0000:0000:0000:0000:0000:0001")).isTrue();
    }

    @Test
    @DisplayName("反向：白名单只写 0:0:0:0:0:0:0:1 时，::1 也应放行")
    void ipv6LoopbackReverse() {
        IpAllowList list = IpAllowList.of("0:0:0:0:0:0:0:1");
        assertThat(list.contains("::1")).isTrue();
    }

    @Test
    @DisplayName("IPv6 带 scope id（fe80::1%eth0）能正确剥离后匹配")
    void ipv6ScopeId() {
        IpAllowList list = IpAllowList.of("fe80::1");
        assertThat(list.contains("fe80::1%eth0")).isTrue();
    }

    @Test
    @DisplayName("IPv4-mapped IPv6（::ffff:127.0.0.1）回退为 IPv4 判定")
    void ipv4MappedIpv6() {
        IpAllowList list = IpAllowList.of("127.0.0.1");
        assertThat(list.contains("::ffff:127.0.0.1")).isTrue();
    }

    // ============================================================
    // 3. IPv4 CIDR
    // ============================================================

    @Test
    @DisplayName("CIDR /8：10.0.0.0/8 覆盖整个 A 类私有段")
    void cidr8() {
        IpAllowList list = IpAllowList.of("10.0.0.0/8");

        assertThat(list.contains("10.0.0.1")).isTrue();
        assertThat(list.contains("10.255.255.254")).isTrue();
        assertThat(list.contains("11.0.0.1")).isFalse();
        assertThat(list.contains("9.255.255.255")).isFalse();
    }

    @Test
    @DisplayName("CIDR /16：172.17.0.0/16 覆盖 Docker 默认网桥段")
    void cidr16() {
        IpAllowList list = IpAllowList.of("172.17.0.0/16");

        assertThat(list.contains("172.17.0.1")).isTrue();
        assertThat(list.contains("172.17.255.254")).isTrue();
        assertThat(list.contains("172.18.0.1")).isFalse();
    }

    @Test
    @DisplayName("CIDR /32 等价于精确匹配单个地址")
    void cidr32() {
        IpAllowList list = IpAllowList.of("192.168.1.10/32");
        assertThat(list.contains("192.168.1.10")).isTrue();
        assertThat(list.contains("192.168.1.11")).isFalse();
    }

    @Test
    @DisplayName("CIDR /24 的网络地址与广播地址都算在内")
    void cidr24Edges() {
        IpAllowList list = IpAllowList.of("192.168.1.0/24");
        assertThat(list.contains("192.168.1.0")).isTrue();
        assertThat(list.contains("192.168.1.255")).isTrue();
        assertThat(list.contains("192.168.2.0")).isFalse();
    }

    @Test
    @DisplayName("CIDR 网络地址未对齐时按掩码归一（10.1.2.3/24 → 10.1.2.0/24）")
    void cidrUnaligned() {
        IpAllowList list = IpAllowList.of("10.1.2.3/24");
        assertThat(list.contains("10.1.2.99")).isTrue();
        assertThat(list.contains("10.1.3.1")).isFalse();
    }

    @Test
    @DisplayName("前缀长度 0：0.0.0.0/0 放行全部（配置者显式为之）")
    void cidrPrefixZero() {
        IpAllowList list = IpAllowList.of("0.0.0.0/0");
        assertThat(list.contains("127.0.0.1")).isTrue();
        assertThat(list.contains("203.0.113.7")).isTrue();
    }

    // ============================================================
    // 4. 异常 / 边界输入（安全边界必须 fail-closed）
    // ============================================================

    @Test
    @DisplayName("空配置：谁都不放行（刻意 fail-closed，避免漏填变敞开）")
    void emptyConfigDeniesAll() {
        assertThat(IpAllowList.of("").contains("127.0.0.1")).isFalse();
        assertThat(IpAllowList.of("   ").contains("127.0.0.1")).isFalse();
        assertThat(IpAllowList.of(null).contains("127.0.0.1")).isFalse();
    }

    @Test
    @DisplayName("null / 空白来源地址一律拒绝")
    void nullSourceDenied() {
        IpAllowList list = IpAllowList.of("127.0.0.1,10.0.0.0/8");
        assertThat(list.contains((String) null)).isFalse();
        assertThat(list.contains("")).isFalse();
        assertThat(list.contains("   ")).isFalse();
    }

    @Test
    @DisplayName("非法条目被忽略，但不影响同一配置中的合法条目")
    void malformedEntriesIgnored() {
        IpAllowList list = IpAllowList.of("not-an-ip,127.0.0.1,999.1.1.1,10.0.0.0/99,,");

        assertThat(list.contains("127.0.0.1")).isTrue();
        assertThat(list.contains("10.0.0.1")).isFalse();   // /99 非法 → 未生效
        assertThat(list.contains("999.1.1.1")).isFalse();  // 非法 IP 不参与匹配
    }

    @Test
    @DisplayName("超范围八位组（256）不被误判为合法 IPv4")
    void invalidOctet() {
        IpAllowList list = IpAllowList.of("0.0.0.0/0");
        // 0.0.0.0/0 覆盖全部合法 IPv4，但 256 不是合法八位组 → 拒绝
        assertThat(list.contains("256.1.1.1")).isFalse();
    }

    // ============================================================
    // 5. 组合场景
    // ============================================================

    @Test
    @DisplayName("精确 IP 与 CIDR 混用：任一命中即放行")
    void mixedExactAndCidr() {
        IpAllowList list = IpAllowList.of("127.0.0.1,10.0.0.0/8,172.17.0.0/16");

        assertThat(list.contains("127.0.0.1")).isTrue();
        assertThat(list.contains("10.20.30.40")).isTrue();
        assertThat(list.contains("172.17.0.5")).isTrue();
        assertThat(list.contains("8.8.8.8")).isFalse();
    }
}
