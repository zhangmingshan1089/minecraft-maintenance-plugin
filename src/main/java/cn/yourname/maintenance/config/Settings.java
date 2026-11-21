package cn.yourname.maintenance.config;

/**
 * 从 config.yml 读出的不可变设置快照。
 *
 * <p>为什么用 record：登录拦截跑在异步线程，主线程可能同时 reload。
 * 一次性构造好不可变对象后由 volatile 引用整体替换，异步侧永远看到一致的一份，
 * 不会出现「读一半」的集合。
 */
public record Settings(
        boolean persistState,
        boolean allowNameFallback,
        boolean permissionBypass,
        boolean ipBypassEnabled,
        boolean trustForwardedIp,
        int kickDelayTicks,
        int logKickLimit,
        boolean recheckOnlineOnReload,
        boolean broadcastIncludeStaff,
        String estimatedRestore,
        boolean serverListEnabled,
        int motdMaxPlayers,
        String motdVersion,
        boolean hidePlayers,
        java.util.List<String> playerSample
) {
    /** 解析失败时使用的兜底设置（与安全一致：不放行任何额外通道）。 */
    public static Settings defaults() {
        return new Settings(
                true, true, true, true, false,
                0, 10, true, true, "待公告",
                true, -1, "", false, java.util.List.of()
        );
    }
}
