package cn.yourname.maintenance.listener;

import cn.yourname.maintenance.MaintenancePlugin;
import cn.yourname.maintenance.api.PluginConfig;
import cn.yourname.maintenance.config.Settings;
import cn.yourname.maintenance.text.TextUtil;
import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerListPingEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 服务器列表维护态：MOTD / maxPlayers / 版本字符串 / hover 玩家样本。
 *
 * <p>使用 1.21 的 Component 版 motd(Component)。version 在 Paper 1.21 只有 String API
 * （setVersion），这是上游事实，不是我们偷懒；父类 setMotd(String) 仍可用但已不推荐。
 * Ping 事件可能在 Netty 线程触发，因此这里只读取不可变快照，不触碰任何同步 Bukkit API。
 */
public final class PingListener implements Listener {

    private static final UUID SAMPLE_UUID = UUID.nameUUIDFromBytes("maintenance-mode".getBytes(java.nio.charset.StandardCharsets.UTF_8));

    private final MaintenancePlugin plugin;

    public PingListener(MaintenancePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPing(ServerListPingEvent event) {
        if (!plugin.state().isEnabled()) return;
        PluginConfig cfg = plugin.config();
        Settings settings = cfg.settings();
        if (!settings.serverListEnabled()) return;

        TextUtil.Placeholders ph = plugin.basePlaceholders();
        Component motd = plugin.message("motd", ph);

        if (event instanceof PaperServerListPingEvent paper) {
            // Component 版 API：避免已不推荐的 setMotd(String)
            paper.motd(motd);

            if (settings.motdMaxPlayers() >= 0) {
                paper.setMaxPlayers(settings.motdMaxPlayers());
            }
            String version = settings.motdVersion();
            if (version != null && !version.isBlank()) {
                // Paper 1.21 的 version 只有 String 版本，这里明文注明
                paper.setVersion(plugin.text().plain(version, ph.build()));
            }
            paper.setHidePlayers(settings.hidePlayers());

            List<String> sample = settings.playerSample();
            if (!sample.isEmpty() && !settings.hidePlayers()) {
                List<PaperServerListPingEvent.ListedPlayerInfo> listed = new ArrayList<>();
                for (String line : sample) {
                    String plain = PlainTextComponentSerializer.plainText()
                            .serialize(plugin.text().render(line, ph.build()));
                    listed.add(new PaperServerListPingEvent.ListedPlayerInfo(plain, SAMPLE_UUID));
                }
                paper.getListedPlayers().clear();
                paper.getListedPlayers().addAll(listed);
            }
            return;
        }

        // 理论上 Paper 下不会走到这里；保留兜底：setMotd(String) 在 1.21 仍可用，但不推荐
        event.setMotd(PlainTextComponentSerializer.plainText().serialize(motd));
        if (settings.motdMaxPlayers() >= 0) {
            event.setMaxPlayers(settings.motdMaxPlayers());
        }
    }
}
