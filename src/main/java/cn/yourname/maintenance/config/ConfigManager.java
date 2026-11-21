package cn.yourname.maintenance.config;

import cn.yourname.maintenance.MaintenancePlugin;
import cn.yourname.maintenance.api.PluginConfig;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * 配置管理器：读取 config.yml / messages.yml，构建不可变快照。
 *
 * <p>不做「静默吞掉」：缺键时用代码里的默认值兜底，写回前先生成 .bak 备份。
 * 注意 Bukkit 的 YamlConfiguration 写回会丢失原文件注释，README 已注明。
 */
public final class ConfigManager {

    private static final Map<String, Object> CONFIG_DEFAULTS = new LinkedHashMap<>();
    private static final Map<String, String> MESSAGE_DEFAULTS = new LinkedHashMap<>();

    static {
        CONFIG_DEFAULTS.put("persist-state", true);
        CONFIG_DEFAULTS.put("bypass.allow-name-fallback", true);
        CONFIG_DEFAULTS.put("bypass.permission", true);
        CONFIG_DEFAULTS.put("bypass.staff", List.of());
        CONFIG_DEFAULTS.put("ip-bypass.enabled", true);
        CONFIG_DEFAULTS.put("ip-bypass.trust-forwarded-ip", false);
        CONFIG_DEFAULTS.put("behavior.kick-delay-ticks", 0);
        CONFIG_DEFAULTS.put("behavior.log-kick-limit", 10);
        CONFIG_DEFAULTS.put("behavior.recheck-online-on-reload", true);
        CONFIG_DEFAULTS.put("behavior.broadcast-include-staff", true);
        CONFIG_DEFAULTS.put("behavior.estimated-restore", "待公告");
        CONFIG_DEFAULTS.put("server-list.enabled", true);
        CONFIG_DEFAULTS.put("server-list.override-max-players", -1);
        CONFIG_DEFAULTS.put("server-list.version-text", "");
        CONFIG_DEFAULTS.put("server-list.hide-players", false);
        CONFIG_DEFAULTS.put("server-list.player-sample", List.of());

        // 与 messages.yml 默认值逐字对应；即使文件被删也能跑
        MESSAGE_DEFAULTS.put("prefix", "<gray>[<red>维护<gray>]<reset> ");
        MESSAGE_DEFAULTS.put("login-deny", """
                <red><bold>服务器正在维护中</bold>
                <gray>请稍后再试，感谢理解。""");
        MESSAGE_DEFAULTS.put("kick-online", """
                <red>服务器已进入维护状态
                <gray>原因：<white>%reason%""");
        MESSAGE_DEFAULTS.put("motd", """
                <gradient:#ff5555:#ffaa00><bold>    服务器维护中    </bold></gradient>
                <gray>    请关注官方公告""");
        MESSAGE_DEFAULTS.put("broadcast-on", "%prefix%<red>维护模式已开启</red> <gray>原因：%reason%");
        MESSAGE_DEFAULTS.put("broadcast-off", "%prefix%<green>维护模式已关闭</green> <gray>服务器恢复正常。");
        MESSAGE_DEFAULTS.put("no-permission", "%prefix%<red>你没有权限执行该命令。");
        MESSAGE_DEFAULTS.put("reload-done", "%prefix%<green>配置已重载。</green> <gray>豁免名单 %staff_count% 人，维护状态未丢失。");
        MESSAGE_DEFAULTS.put("staff-added", "%prefix%<green>已加入豁免名单：</green><white>%list%</white> <gray>（当前 %staff_count% 人）");
        MESSAGE_DEFAULTS.put("staff-removed", "%prefix%<yellow>已从豁免名单移除：</yellow><white>%list%</white> <gray>（当前 %staff_count% 人）");
        MESSAGE_DEFAULTS.put("staff-not-found", "%prefix%<red>未在豁免名单中找到：</red><white>%list%</white>");
        MESSAGE_DEFAULTS.put("staff-list", "%prefix%<yellow>豁免名单（%staff_count% 人）：\n<gray>%list%");
        MESSAGE_DEFAULTS.put("ip-status", "%prefix%<gray>回环地址豁免：<white>%state%</white> <gray>（信任转发 IP：%trust%）");
        MESSAGE_DEFAULTS.put("ip-set", "%prefix%<green>回环地址豁免已设为：</green><white>%state%</white>");
        MESSAGE_DEFAULTS.put("state-on", "开启");
        MESSAGE_DEFAULTS.put("state-off", "关闭");
        MESSAGE_DEFAULTS.put("usage", "%prefix%<gray>用法：<white>/mt <on|off|status|reload|add|remove|list|ip>");
        MESSAGE_DEFAULTS.put("unknown-sub", "%prefix%<red>未知子命令：</red><white>%reason%</white> <gray>输入 /mt 查看用法。");
        MESSAGE_DEFAULTS.put("status", """
                %prefix%<yellow>维护状态：<white>%state%</white>
                <gray>原因：<white>%reason%</white>
                <gray>开启时间：<white>%time%</white>
                <gray>预计恢复：<white>%until%</white>
                <gray>豁免名单：<white>%staff_count%</white> 人
                <gray>回环豁免：<white>%list%</white>""");
    }

    private final MaintenancePlugin plugin;
    private final Logger logger;

    public ConfigManager(MaintenancePlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    public PluginConfig load() {
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        File messageFile = new File(plugin.getDataFolder(), "messages.yml");
        if (!configFile.exists()) plugin.saveResource("config.yml", false);
        if (!messageFile.exists()) plugin.saveResource("messages.yml", false);

        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(configFile);
        ensureKeys(cfg, configFile, CONFIG_DEFAULTS);

        YamlConfiguration msg = YamlConfiguration.loadConfiguration(messageFile);
        ensureKeys(msg, messageFile, MESSAGE_DEFAULTS);

        Settings settings = new Settings(
                cfg.getBoolean("persist-state", true),
                cfg.getBoolean("bypass.allow-name-fallback", true),
                cfg.getBoolean("bypass.permission", true),
                cfg.getBoolean("ip-bypass.enabled", true),
                cfg.getBoolean("ip-bypass.trust-forwarded-ip", false),
                Math.max(0, cfg.getInt("behavior.kick-delay-ticks", 0)),
                Math.max(0, cfg.getInt("behavior.log-kick-limit", 10)),
                cfg.getBoolean("behavior.recheck-online-on-reload", true),
                cfg.getBoolean("behavior.broadcast-include-staff", true),
                cfg.getString("behavior.estimated-restore", "待公告"),
                cfg.getBoolean("server-list.enabled", true),
                cfg.getInt("server-list.override-max-players", -1),
                cfg.getString("server-list.version-text", ""),
                cfg.getBoolean("server-list.hide-players", false),
                List.copyOf(cfg.getStringList("server-list.player-sample"))
        );

        BypassSnapshot bypass = buildBypass(cfg, settings);

        Map<String, String> messages = new HashMap<>();
        for (Map.Entry<String, String> e : MESSAGE_DEFAULTS.entrySet()) {
            messages.put(e.getKey(), msg.getString(e.getKey(), e.getValue()));
        }
        return new PluginConfig(settings, bypass, messages);
    }

    private BypassSnapshot buildBypass(YamlConfiguration cfg, Settings settings) {
        Set<UUID> uuids = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (String raw : cfg.getStringList("bypass.staff")) {
            if (raw == null || raw.isBlank()) continue;
            UUID uuid = BypassSnapshot.parseUuid(raw);
            if (uuid != null) {
                uuids.add(BypassSnapshot.normalizeUuid(uuid)); // UUID 优先、统一小写带横线
            } else {
                names.add(raw.trim().toLowerCase(Locale.ROOT)); // 名字兜底、大小写不敏感
            }
        }
        return new BypassSnapshot(uuids, names, settings.allowNameFallback(), settings.permissionBypass());
    }

    /** 缺项兜底 + 写回 + 备份；不缺项就不写，避免每次启动都重写文件丢注释。 */
    private void ensureKeys(YamlConfiguration yaml, File file, Map<String, ?> defaults) {
        boolean missing = false;
        for (String key : defaults.keySet()) {
            if (!yaml.isSet(key)) {
                missing = true;
                break;
            }
        }
        for (Map.Entry<String, ?> e : defaults.entrySet()) {
            yaml.addDefault(e.getKey(), e.getValue());
        }
        if (missing) {
            logger.info("[Maintenance] " + file.getName() + " 存在缺失键，已用默认值补齐并写回（原文件已备份为 .bak）。");
            saveWithBackup(yaml, file);
        }
    }

    /** 运行时增删名单：直接改 config.yml 再整体 reload 快照，保证立即生效。 */
    public String addStaff(String input) {
        return modifyStaff(input, true);
    }

    public String removeStaff(String input) {
        return modifyStaff(input, false);
    }

    private String modifyStaff(String input, boolean add) {
        if (input == null || input.isBlank()) return null;
        File file = new File(plugin.getDataFolder(), "config.yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        List<String> list = new ArrayList<>(yaml.getStringList("bypass.staff"));

        UUID uuid = BypassSnapshot.parseUuid(input);
        String normalized = uuid != null ? uuid.toString().toLowerCase(Locale.ROOT) : input.trim();

        if (add) {
            int index = -1;
            for (int i = 0; i < list.size(); i++) {
                if (sameEntry(list.get(i), normalized, uuid)) {
                    index = i;
                    break;
                }
            }
            if (index >= 0) {
                if (!normalized.equals(list.get(index))) {
                    list.set(index, normalized); // 顺手规范化已有条目
                    yaml.set("bypass.staff", list);
                    saveWithBackup(yaml, file);
                }
            } else {
                list.add(normalized);
                yaml.set("bypass.staff", list);
                saveWithBackup(yaml, file);
            }
            return normalized;
        }

        boolean removed = list.removeIf(s -> sameEntry(s, normalized, uuid));
        if (removed) {
            yaml.set("bypass.staff", list);
            saveWithBackup(yaml, file);
        }
        return removed ? normalized : null;
    }

    private static boolean sameEntry(String existing, String target, UUID targetUuid) {
        if (existing == null || existing.isBlank()) return false;
        if (targetUuid != null) {
            UUID existingUuid = BypassSnapshot.parseUuid(existing);
            return existingUuid != null ? existingUuid.equals(targetUuid) : existing.equalsIgnoreCase(target);
        }
        // 目标不是 UUID：只匹配名字条目，避免误删 UUID 条目
        return BypassSnapshot.parseUuid(existing) == null && existing.equalsIgnoreCase(target);
    }

    /** 备份 + 保存，异常只记录不抛出；供配置管理器和状态存储共用。 */
    public static void saveWithBackup(YamlConfiguration yaml, File file) {
        if (file.exists()) {
            File backup = new File(file.getParentFile(), file.getName() + ".bak");
            try {
                Files.copy(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                // 备份失败不阻止保存，但要留日志
                Logger.getLogger("Maintenance").warning("[Maintenance] 备份失败: " + backup + " -> " + e.getMessage());
            }
        }
        try {
            // 确保默认值也在写回内容里（否则补键后仍缺）
            yaml.options().copyDefaults(true);
            yaml.save(file);
        } catch (IOException e) {
            Logger.getLogger("Maintenance").warning("[Maintenance] 保存失败: " + file + " -> " + e.getMessage());
        }
    }
}
