package cn.yourname.maintenance.listener;

import cn.yourname.maintenance.MaintenancePlugin;
import cn.yourname.maintenance.text.TextUtil;
import cn.yourname.maintenance.util.IpUtil;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * 主拦截：异步预登录。
 *
 * <p>选型理由：这是登录链路里最早能拿到 UUID + 对端 InetSocketAddress 的事件，
 * 在这里拒绝可以避免玩家真正进服再被踢（体验更好、也不触发 PlayerJoin/Quit）。
 * 本监听器里<b>绝不</b>调用 getOnlinePlayers / 写配置 / 任何同步 Bukkit API；
 * 需要的权限判断由 MaintenancePlugin#isBypassed 通过 callSyncMethod 桥接主线程。
 */
public final class PreLoginListener implements Listener {

    private final MaintenancePlugin plugin;

    public PreLoginListener(MaintenancePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (!plugin.state().isEnabled()) return;

        String ip = IpUtil.display(event.getAddress());
        // 只读不可变配置快照 + volatile 状态；allowSyncLookup=true 用于权限豁免
        if (plugin.isBypassed(event.getUniqueId(), event.getName(), event.getAddress(), null, true)) return;

        TextUtil.Placeholders ph = plugin.basePlaceholders()
                .put("player", event.getName())
                .put("uuid", event.getUniqueId())
                .put("ip", ip)
                .put("reason", plugin.state().reason());

        // 玩家只会看到「正在维护」；不附加错误码、不泄露名单或内部路径
        // 以实际 paper-api 1.21.11-R0.1-SNAPSHOT 工件为准：Result 含 KICK_OTHER
        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                plugin.message("login-deny", ph));
        plugin.logDeny(event.getUniqueId(), event.getName(), ip, "prelogin");
    }
}
