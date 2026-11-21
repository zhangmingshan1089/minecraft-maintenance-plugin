package cn.yourname.maintenance.command;

import cn.yourname.maintenance.MaintenancePlugin;
import cn.yourname.maintenance.api.PluginConfig;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 与命令执行器一一对应的 Tab 补全；不泄露名单以外的内容。 */
public final class MaintenanceTabCompleter implements TabCompleter {

    private static final List<String> ROOT =
            List.of("on", "off", "status", "reload", "add", "remove", "list", "ip");
    private static final List<String> IP_MODES = List.of("on", "off", "status");

    private final MaintenancePlugin plugin;

    public MaintenanceTabCompleter(MaintenancePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("maintenance.admin") && !sender.hasPermission("maintenance.reload")) {
            return List.of();
        }
        if (args.length == 1) {
            return filter(ROOT, args[0]);
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            return switch (sub) {
                case "ip" -> filter(IP_MODES, args[1]);
                case "add", "remove" -> filter(staffSuggestions(), args[1]);
                default -> List.of();
            };
        }
        return List.of();
    }

    private List<String> staffSuggestions() {
        List<String> result = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) result.add(player.getName());
        PluginConfig cfg = plugin.config();
        cfg.bypass().uuids().forEach(u -> result.add(u.toString()));
        cfg.bypass().names().forEach(result::add);
        return result;
    }

    private static List<String> filter(List<String> source, String prefix) {
        String p = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String value : source) {
            if (value.toLowerCase(Locale.ROOT).startsWith(p)) result.add(value);
        }
        return result;
    }
}
