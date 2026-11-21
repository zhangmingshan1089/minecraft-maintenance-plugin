package cn.yourname.maintenance.util;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * IP 归一化工具：只用 InetAddress，绝不裸字符串 equals。
 *
 * <p>InetAddress#isLoopbackAddress() 同时覆盖 127.0.0.0/8、::1、0:0:0:0:0:0:0:1，
 * 因此不需要手写字符串列表；"localhost" 通过 getByName 解析后也会得到回环地址。
 */
public final class IpUtil {

    private IpUtil() {
    }

    public static boolean isLoopback(InetAddress address) {
        return address != null && address.isLoopbackAddress();
    }

    /** Player#getAddress() 在 1.21 返回 InetSocketAddress，这里统一取出 InetAddress。 */
    public static InetAddress of(InetSocketAddress socketAddress) {
        return socketAddress == null ? null : socketAddress.getAddress();
    }

    /** 供配置/字符串输入使用；解析失败返回 null 而不是抛异常。 */
    public static InetAddress parse(String host) {
        if (host == null || host.isBlank()) return null;
        String h = host.trim().toLowerCase(Locale.ROOT);
        // IPv6 带方括号写法 "[::1]:25565"
        if (h.startsWith("[") && h.indexOf(']') > 1) {
            h = h.substring(1, h.indexOf(']'));
        } else {
            // IPv4 带端口 "127.0.0.1:25565"
            int lastColon = h.lastIndexOf(':');
            if (lastColon > 0 && !h.contains("::") && h.substring(lastColon + 1).matches("\\d{1,5}")) {
                h = h.substring(0, lastColon);
            }
        }
        try {
            return InetAddress.getByName(h);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    /** 展示给玩家看的 IP，统一归一化；绝不展示内部路径或名单内容。 */
    public static String display(InetAddress address) {
        if (address == null) return "<unknown>";
        String host = address.getHostAddress();
        return host == null ? "<unknown>" : host;
    }
}
