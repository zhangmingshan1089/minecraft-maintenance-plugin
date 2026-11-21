package cn.yourname.maintenance;

import cn.yourname.maintenance.api.PluginConfig;
import cn.yourname.maintenance.command.MaintenanceCommand;
import cn.yourname.maintenance.command.MaintenanceTabCompleter;
import cn.yourname.maintenance.config.BypassSnapshot;
import cn.yourname.maintenance.config.ConfigManager;
import cn.yourname.maintenance.config.Settings;
import cn.yourname.maintenance.config.StateStore;
import cn.yourname.maintenance.listener.LoginListener;
import cn.yourname.maintenance.listener.PingListener;
import cn.yourname.maintenance.listener.PlayerQuitListener;
import cn.yourname.maintenance.listener.PreLoginListener;
import cn.yourname.maintenance.text.PlaceholderHook;
import cn.yourname.maintenance.text.TextUtil;
import cn.yourname.maintenance.util.IpUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * 主类：持有唯一不可变配置快照 + 可变状态存储。
 *
 * <p>线程模型：
 * <ul>
 *   <li>所有 Bukkit 同步 API（踢人、广播、发消息、写盘）只在主线程执行；</li>
 *   <li>AsyncPlayerPreLoginEvent 里只读 volatile 状态与不可变快照，权限检查用 callSyncMethod 桥接；</li>
 *   <li>命令若从非主线程调用，也统一经 scheduler.runTask 落到主线程。</li>
 * </ul>
 * 事件与命令只在 onEnable 注册一次；reload 只换快照，不重复注册。
 */
public final class MaintenancePlugin extends JavaPlugin {

    private static MaintenancePlugin instance;

    private ConfigManager configManager;
    private StateStore state;
    private volatile PluginConfig config;
    private TextUtil text;
    private PlaceholderHook papi;

    /** 同一 UUID 10 秒内只记一条拒绝日志，防刷屏；玩家退出时清理。 */
    private final ConcurrentMap<UUID, Long> denyCooldown = new ConcurrentHashMap<>();

    public static MaintenancePlugin get() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            getLogger().severe("[Maintenance] 无法创建数据目录，插件停用。");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.configManager = new ConfigManager(this);
        this.config = configManager.load();
        this.state = new StateStore(this);
        state.load(config.settings());
        state.applySettings(config.settings());
        this.text = new TextUtil(getLogger());
        this.papi = new PlaceholderHook(getLogger());

        registerOnce();
        warnProxyRisk();
        getLogger().info("[Maintenance] 已启用。维护状态=" + (state.isEnabled() ? "开启" : "关闭")
                + "，豁免名单=" + config.bypass().size() + " 条。");
    }

    @Override
    public void onDisable() {
        // 关闭插件时只落盘状态，不强制踢人；避免关服阶段制造额外断线
        if (state != null) {
            state.save();
            getLogger().info("[Maintenance] 已停用，状态已保存。");
        }
    }

    private void registerOnce() {
        PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(new PreLoginListener(this), this);
        pm.registerEvents(new LoginListener(this), this);
        pm.registerEvents(new PingListener(this), this);
        pm.registerEvents(new PlayerQuitListener(this), this);

        PluginCommand command = getCommand("maintenance");
        if (command == null) {
            getLogger().warning("[Maintenance] plugin.yml 中未找到 maintenance 命令，命令功能不可用。");
            return;
        }
        command.setExecutor(new MaintenanceCommand(this));
        command.setTabCompleter(new MaintenanceTabCompleter(this));
    }

    private void warnProxyRisk() {
        Settings s = config.settings();
        if (s.ipBypassEnabled() && !s.trustForwardedIp()) {
            getLogger().warning("[Maintenance] 回环豁免开启且 trust-forwarded-ip=false："
                    + "若服务端位于 Velocity/Bungee/反代之后且对端地址为 127.0.0.1，所有玩家都会被误判为豁免。"
                    + "请阅读 README《假设与口径》后再决定是否调整。");
        }
    }

    // ------------------------------------------------------------------
    // 豁免判定
    // ------------------------------------------------------------------

    /**
     * 顺序：UUID → 玩家名（可关） → 回环 IP → 权限（可在 async 中经主线程桥接）。
     * player 为 null 表示当前没有在线 Player 对象（预登录阶段）。
     */
    public boolean isBypassed(UUID uuid, String name, InetAddress address, Player player, boolean allowSyncLookup) {
        PluginConfig snapshot = config;
        BypassSnapshot bypass = snapshot.bypass();
        if (bypass.containsUuid(uuid)) return true;
        if (bypass.containsName(name)) return true;
        if (state.isIpBypassed(address)) return true;
        if (bypass.permissionBypass()) {
            if (player != null) return player.hasPermission("maintenance.bypass");
            if (allowSyncLookup && uuid != null) return checkPermissionFromMainThread(uuid);
        }
        return false;
    }

    /**
     * 异步线程不能直接碰 Bukkit 同步 API；callSyncMethod 是官方桥接方式。
     * 2 秒超时后保守判定为「无权限」——PlayerLoginEvent 会在同步阶段再兜底校验一次。
     */
    private boolean checkPermissionFromMainThread(UUID uuid) {
        try {
            return Bukkit.getScheduler().callSyncMethod(this, () -> {
                Player online = Bukkit.getPlayer(uuid);
                if (online != null) return online.hasPermission("maintenance.bypass");
                OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
                // OfflinePlayer 在 1.21 不实现 Permissible，只能安全读取 OP 标记；
                // 非 OP 的离线权限由同步 LoginEvent 阶段再按在线 Player 精确判定
                return offline.isOp();
            }).get(2, TimeUnit.SECONDS);
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 文案渲染
    // ------------------------------------------------------------------

    /** 不自动加 prefix 的原始渲染（给 prefix / 状态词这种小片段用）。 */
    public Component rawMessage(String key) {
        return text.render(config.msg(key), Map.of());
    }

    /** 未在模板里显式写 %prefix% 时自动前置；viewer 非空时再展开 PAPI 占位符。 */
    public Component message(String key, TextUtil.Placeholders placeholders, Player viewer) {
        String raw = config.msg(key);
        if (raw.isEmpty()) return Component.empty();
        String prefix = config.msg("prefix");
        if (!"prefix".equals(key) && !raw.contains("%prefix%") && !prefix.isEmpty()) {
            raw = prefix + raw;
        }
        raw = papi.expand(viewer, raw);
        return text.render(raw, placeholders.build());
    }

    public Component message(String key, TextUtil.Placeholders placeholders) {
        return message(key, placeholders, null);
    }

    /** 所有模板共享的占位符；每次新建，避免跨线程复用可变量。 */
    public TextUtil.Placeholders basePlaceholders() {
        Component prefix = rawMessage("prefix");
        Component stateWord = rawMessage(state.isEnabled() ? "state-on" : "state-off");
        Component trustWord = rawMessage(state.isTrustForwardedIp() ? "state-on" : "state-off");
        return TextUtil.Placeholders.of()
                .component("prefix", prefix)
                .component("state", stateWord)
                .component("trust", trustWord)
                .put("staff_count", config.bypass().size())
                .put("time", state.nowText())
                .put("until", state.untilText());
    }

    // ------------------------------------------------------------------
    // 状态切换
    // ------------------------------------------------------------------

    /** 统一入口：无论调用方在哪个线程，实际动作都落到主线程。 */
    public void setMaintenance(boolean enable, String reason) {
        Bukkit.getScheduler().runTask(this, () -> applyMaintenance(enable, reason));
    }

    private void applyMaintenance(boolean enable, String reason) {
        boolean was = state.isEnabled();
        state.setMaintenance(enable, reason);
        if (enable) {
            int delay = Math.max(0, config.settings().kickDelayTicks());
            Runnable kick = this::kickNonBypassedOnline;
            if (delay == 0) Bukkit.getScheduler().runTask(this, kick);
            else Bukkit.getScheduler().runTaskLater(this, kick, delay);
        }
        // 关闭时不需要「还原」：PingListener 每次都先检查 state.isEnabled()
        if (was != enable) broadcastToggle(enable);
    }

    /** 踢人 + 防刷屏日志；延迟执行前再次确认维护态，避免「先关后踢」。 */
    public void kickNonBypassedOnline() {
        if (!state.isEnabled()) return;
        int limit = config.settings().logKickLimit();
        int total = 0;
        int logged = 0;
        for (Player player : new ArrayList<>(Bukkit.getOnlinePlayers())) {
            if (isBypassed(player.getUniqueId(), player.getName(), IpUtil.of(player.getAddress()), player, false)) continue;
            TextUtil.Placeholders ph = basePlaceholders()
                    .put("player", player.getName())
                    .put("uuid", player.getUniqueId())
                    .put("ip", IpUtil.display(IpUtil.of(player.getAddress())))
                    .put("reason", state.reason());
            player.kick(message("kick-online", ph, player));
            total++;
            if (limit > 0 && logged < limit) {
                getLogger().info("[Maintenance] 已踢出非豁免玩家: " + player.getName()
                        + " (" + player.getUniqueId() + ")");
                logged++;
            }
        }
        if (limit == 0 || total > logged) {
            getLogger().info("[Maintenance] 本次共踢出 " + total + " 名非豁免玩家（逐条日志上限 " + limit + "）。");
        }
    }

    private void broadcastToggle(boolean enable) {
        String key = enable ? "broadcast-on" : "broadcast-off";
        boolean includeStaff = config.settings().broadcastIncludeStaff();
        for (Player player : new ArrayList<>(Bukkit.getOnlinePlayers())) {
            if (!player.hasPermission("maintenance.notify")) continue;
            if (!includeStaff && isBypassed(player.getUniqueId(), player.getName(), IpUtil.of(player.getAddress()), player, false)) {
                continue;
            }
            player.sendMessage(message(key, basePlaceholders(), player));
        }
        Bukkit.getConsoleSender().sendMessage(message(key, basePlaceholders(), null));
    }

    /** reload：换快照 + 按配置重筛在线玩家；不重注册监听器、不丢维护态。 */
    public void reloadAll(CommandSender sender) {
        PluginConfig next = configManager.load();
        config = next;                       // volatile 整体替换，异步读取侧立即看到新值
        state.applySettings(next.settings());
        if (state.isEnabled() && next.settings().recheckOnlineOnReload()) {
            Bukkit.getScheduler().runTask(this, this::kickNonBypassedOnline);
        }
        sender.sendMessage(message("reload-done", basePlaceholders(), sender instanceof Player p ? p : null));
        getLogger().info("[Maintenance] 配置已重载，维护状态=" + (state.isEnabled() ? "开启" : "关闭"));
    }

    /** 拒绝日志去重：同一 UUID 10 秒内只打一条。 */
    public void logDeny(UUID uuid, String name, String ip, String source) {
        long now = System.currentTimeMillis();
        Long last = denyCooldown.get(uuid);
        if (last != null && now - last < 10_000L) return;
        denyCooldown.put(uuid, now);
        getLogger().info("[Maintenance] 拒绝登录(" + source + "): " + name + " / " + uuid + " / " + ip);
    }

    public void clearDenyRecord(UUID uuid) {
        if (uuid != null) denyCooldown.remove(uuid);
    }

    // ------------------------------------------------------------------
    // Getter
    // ------------------------------------------------------------------

    public PluginConfig config() {
        return config;
    }

    public StateStore state() {
        return state;
    }

    public ConfigManager configManager() {
        return configManager;
    }

    public TextUtil text() {
        return text;
    }

    public PlaceholderHook papi() {
        return papi;
    }
}
