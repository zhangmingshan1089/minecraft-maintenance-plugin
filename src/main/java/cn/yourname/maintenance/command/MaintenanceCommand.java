package cn.yourname.maintenance.command;

import cn.yourname.maintenance.MaintenancePlugin;
import cn.yourname.maintenance.api.PluginConfig;
import cn.yourname.maintenance.text.TextUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * /maintenance（别名 /mt、/maint）执行器。
 *
 * <p>所有回执都从 messages.yml 取，代码里不硬编码面向玩家的中文；
 * 唯一例外是日志前缀 [Maintenance]，那是运维日志而非玩家文案。
 */
public final class MaintenanceCommand implements CommandExecutor {

    private final MaintenancePlugin plugin;

    public MaintenanceCommand(MaintenancePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Player viewer = sender instanceof Player p ? p : null;
        TextUtil.Placeholders base = plugin.basePlaceholders();

        if (args.length == 0) {
            sender.sendMessage(plugin.message("usage", base, viewer));
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        boolean admin = sender.hasPermission("maintenance.admin");
        boolean reloadOnly = sender.hasPermission("maintenance.reload");

        switch (sub) {
            case "on" -> {
                if (!admin) return noPermission(sender, viewer, base);
                String reason = args.length > 1 ? String.join(" ", Arrays.copyOfRange(args, 1, args.length)) : "";
                plugin.setMaintenance(true, reason);
                sender.sendMessage(plugin.message("broadcast-on", base.put("reason", reason), viewer));
            }
            case "off" -> {
                if (!admin) return noPermission(sender, viewer, base);
                plugin.setMaintenance(false, "");
                sender.sendMessage(plugin.message("broadcast-off", base, viewer));
            }
            case "status" -> {
                if (!admin) return noPermission(sender, viewer, base);
                sender.sendMessage(plugin.message("status",
                        base.put("reason", plugin.state().reason())
                                .component("list", plugin.state().isIpBypassEnabled()
                                        ? plugin.rawMessage("state-on") : plugin.rawMessage("state-off")),
                        viewer));
            }
            case "reload" -> {
                if (!admin && !reloadOnly) return noPermission(sender, viewer, base);
                plugin.reloadAll(sender);
            }
            case "add" -> {
                if (!admin) return noPermission(sender, viewer, base);
                if (args.length < 2) return usage(sender, viewer, base);
                String added = plugin.configManager().addStaff(args[1]);
                if (added == null) {
                    sender.sendMessage(plugin.message("staff-not-found", base.put("list", args[1]), viewer));
                } else {
                    sender.sendMessage(plugin.message("staff-added", base.put("list", added), viewer));
                }
            }
            case "remove" -> {
                if (!admin) return noPermission(sender, viewer, base);
                if (args.length < 2) return usage(sender, viewer, base);
                String removed = plugin.configManager().removeStaff(args[1]);
                if (removed == null) {
                    sender.sendMessage(plugin.message("staff-not-found", base.put("list", args[1]), viewer));
                } else {
                    sender.sendMessage(plugin.message("staff-removed", base.put("list", removed), viewer));
                }
            }
            case "list" -> {
                if (!admin) return noPermission(sender, viewer, base);
                PluginConfig cfg = plugin.config();
                List<String> entries = new ArrayList<>();
                cfg.bypass().uuids().forEach(u -> entries.add(u.toString()));
                cfg.bypass().names().forEach(entries::add);
                String joined = entries.isEmpty() ? "-" : String.join(", ", entries);
                sender.sendMessage(plugin.message("staff-list", base.put("list", joined), viewer));
            }
            case "ip" -> {
                if (!admin) return noPermission(sender, viewer, base);
                if (args.length < 2) {
                    sender.sendMessage(plugin.message("ip-status", ipPlaceholders(base), viewer));
                    return true;
                }
                String mode = args[1].toLowerCase(Locale.ROOT);
                switch (mode) {
                    case "on", "off" -> {
                        plugin.state().setIpBypass("on".equals(mode));
                        sender.sendMessage(plugin.message("ip-set", ipPlaceholders(base), viewer));
                    }
                    case "status" -> sender.sendMessage(plugin.message("ip-status", ipPlaceholders(base), viewer));
                    default -> {
                        return usage(sender, viewer, base);
                    }
                }
            }
            default -> sender.sendMessage(plugin.message("unknown-sub", base.put("reason", sub), viewer));
        }
        return true;
    }

    /** /mt ip 的 %state% 指回环豁免自身，不是维护开关；覆盖 base 中的同名字段。 */
    private TextUtil.Placeholders ipPlaceholders(TextUtil.Placeholders base) {
        return base.put("state", plugin.state().isIpBypassEnabled()
                ? plugin.rawMessage("state-on") : plugin.rawMessage("state-off"))
                .put("trust", plugin.state().isTrustForwardedIp()
                        ? plugin.rawMessage("state-on") : plugin.rawMessage("state-off"));
    }

    private boolean noPermission(CommandSender sender, Player viewer, TextUtil.Placeholders base) {
        sender.sendMessage(plugin.message("no-permission", base, viewer));
        return true;
    }

    private boolean usage(CommandSender sender, Player viewer, TextUtil.Placeholders base) {
        sender.sendMessage(plugin.message("usage", base, viewer));
        return true;
    }
}
