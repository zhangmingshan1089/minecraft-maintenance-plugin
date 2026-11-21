package cn.yourname.maintenance.listener;

import cn.yourname.maintenance.MaintenancePlugin;
import cn.yourname.maintenance.text.TextUtil;
import cn.yourname.maintenance.util.IpUtil;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.Listener;

/**
 * 兜底拦截：同步 PlayerLoginEvent。
 *
 * <p>为什么还要这一层：异步预登录之后、真正入场之前，配置文件可能被 reload、
 * 其它插件可能改过事件结果，权限组也可能刚变更。这里在主线程用同一套口径再判一次。
 * 由于预登录一旦 disallow，本事件根本不会触发，所以不存在「同一玩家被踢两次」；
 * 日志侧仍用 MaintenancePlugin#logDeny 做 10 秒去重，防止重复登录尝试刷屏。
 */
public final class LoginListener implements Listener {

    private final MaintenancePlugin plugin;

    public LoginListener(MaintenancePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onLogin(PlayerLoginEvent event) {
        if (!plugin.state().isEnabled()) return;

        Player player = event.getPlayer();
        String ip = IpUtil.display(event.getAddress());
        // player 非空 → 权限检查直接同步完成，无需 callSyncMethod
        if (plugin.isBypassed(player.getUniqueId(), player.getName(), event.getAddress(), player, false)) return;

        TextUtil.Placeholders ph = plugin.basePlaceholders()
                .put("player", player.getName())
                .put("uuid", player.getUniqueId())
                .put("ip", ip)
                .put("reason", plugin.state().reason());
        event.disallow(PlayerLoginEvent.Result.KICK_OTHER, plugin.message("login-deny", ph));
        plugin.logDeny(player.getUniqueId(), player.getName(), ip, "login");
    }
}
