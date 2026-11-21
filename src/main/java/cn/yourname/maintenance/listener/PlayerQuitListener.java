package cn.yourname.maintenance.listener;

import cn.yourname.maintenance.MaintenancePlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.Listener;

/** 玩家退出时清理拒绝日志去重记录，避免 ConcurrentHashMap 随在线人数长期增长。 */
public final class PlayerQuitListener implements Listener {

    private final MaintenancePlugin plugin;

    public PlayerQuitListener(MaintenancePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        plugin.clearDenyRecord(event.getPlayer().getUniqueId());
    }
}
