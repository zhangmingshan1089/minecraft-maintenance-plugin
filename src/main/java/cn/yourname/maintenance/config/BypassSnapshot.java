package cn.yourname.maintenance.config;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 豁免名单的不可变快照。判定顺序（满足任意一条即放行）：
 *
 * <ol>
 *   <li><b>UUID 精确匹配（优先）</b>：规范化为小写带横线后比较；写入时也统一规范化。</li>
 *   <li><b>玩家名兜底</b>：仅当 allowNameFallback=true 时启用，大小写不敏感。</li>
 *   <li><b>权限节点 maintenance.bypass</b>：由调用方在安全的线程里检查，不在这里碰 Bukkit API。</li>
 *   <li><b>回环 IP</b>：由 StateStore + IpUtil 判定，不在这个快照里。</li>
 * </ol>
 *
 * <p>这个 record 只保存「名单数据 + 两个开关」，刻意不持有 Plugin / Bukkit 引用，
 * 因此可以在异步预登录线程中安全地读取。
 */
public record BypassSnapshot(Set<UUID> uuids, Set<String> names,
                             boolean allowNameFallback, boolean permissionBypass) {

    public static final BypassSnapshot EMPTY = new BypassSnapshot(Set.of(), Set.of(), false, false);

    // 紧凑构造器：强制不可变，外部传入的 Set 改动不会影响快照
    public BypassSnapshot {
        uuids = Set.copyOf(uuids);
        names = Set.copyOf(names);
    }

    public boolean containsUuid(UUID uuid) {
        return uuid != null && uuids.contains(normalizeUuid(uuid));
    }

    public boolean containsName(String name) {
        return allowNameFallback && name != null && names.contains(name.toLowerCase(Locale.ROOT));
    }

    public int size() {
        return uuids.size() + names.size();
    }

    /** 规范化：小写 + 带横线。UUID.toString() 本身已带横线，但外部数据不保证，统一处理。 */
    public static UUID normalizeUuid(UUID uuid) {
        return uuid == null ? null : UUID.fromString(uuid.toString().toLowerCase(Locale.ROOT));
    }

    /** 字符串容错：接受带横线 / 不带横线 / 大小写混合；非法返回 null（绝不抛异常给调用方）。 */
    public static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.length() != 32 && s.length() != 36) return null;
        try {
            if (s.length() == 32) {
                return UUID.fromString(s.substring(0, 8) + "-" + s.substring(8, 12) + "-" +
                        s.substring(12, 16) + "-" + s.substring(16, 20) + "-" + s.substring(20));
            }
            return UUID.fromString(s);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
