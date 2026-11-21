# MaintenanceMode

Paper / Purpur **1.21.11** 维护模式插件，Java 21 + Gradle Groovy，单 jar 交付。
本 README 即工程文档；仓库内每个 Java 源文件都是全文，无省略、无 TODO。

---

## 一、假设与口径

| 议题 | 本工程采用的口径 |
| --- | --- |
| 事件选型 | 主拦截用 `org.bukkit.event.player.AsyncPlayerPreLoginEvent`：登录链路最早能拿到 UUID 与对端 `InetAddress`，在被真正放进服务器前拒绝，不触发 Join/Quit；但它跑在异步线程。兜底用同步 `PlayerLoginEvent`：防配置刚 reload、权限组变更或其它插件改写事件结果。预登录一旦 `disallow`，LoginEvent 不会触发，所以不存在同一玩家被踢两次；日志仍按 UUID 做 10 秒去重。 |
| IP 判定边界 | 用 `InetAddress#isLoopbackAddress()` 归一化判断，覆盖 `127.0.0.0/8`、`::1`、`0:0:0:0:0:0:0:1`，`localhost` 经 `getByName` 解析后同样命中。绝不做裸字符串 equals。`trust-forwarded-ip` 默认 **false**：插件只把事件里的对端地址当真实玩家地址；**不解析** `X-Forwarded-For` / `X-Real-IP` 等请求头。 |
| 反代风险 | 若服务端在 Velocity / Bungee / 反代后且对端为代理的 `127.0.0.1`，则**所有玩家**都会被回环规则误判为豁免。正确做法：要么把 `ip-bypass.enabled` 设为 false，要么先配置 PROXY 协议 / Velocity modern forwarding + `forwarding-secret`，确认 Paper 已把事件地址改写为真实玩家 IP 后，才把 `trust-forwarded-ip` 设为 true。插件启动时会对这个组合打一条 WARN。 |
| 白名单语义 | `maintenance.staff-bypass`（落到 config.yml 的 `bypass.staff`）是**插件自建的独立豁免名单**，与 `server.properties` / vanilla `whitelist.json` 完全无关；插件**不调用** `Bukkit.setWhitelist()`、**不修改**任何原版白名单文件。 |
| 持久化策略 | 默认 `persist-state: true`，状态镜像写到 `state.yml`（含 enabled / reason / since / ip-bypass）；设为 false 时每次启动都当作未开启维护。写盘只在主线程，且写前生成 `.bak` 备份。 |
| MiniMessage / 旧色码 | `TextUtil` 自动探测：原文已有 MiniMessage 标签就不转换 `&`（避免 URL 查询串被误伤）；否则把 `&c`、`&x&f&f&0&0&0&0` 转成等价 MiniMessage 标签。占位符 `%key%` 先换成 `<mm_key>` 再用 `Placeholder.component` 注入，玩家名含 `<` `>` 也不会破坏标签。使用宽松解析器 + try/catch，非法标签降级为「剥标签 + 旧色码序列化」，绝不崩。 |
| 权限与异步 | `AsyncPlayerPreLoginEvent` 中不能直接调用同步 Bukkit API；权限豁免用 `Bukkit.getScheduler().callSyncMethod(...).get(2s)` 桥接主线程，超时保守判为无权限，再由同步 LoginEvent 兜底。`OfflinePlayer` 在 1.21 不实现 `Permissible`，离线侧只能读 OP 标记，非 OP 由 LoginEvent 阶段按在线 Player 精确判定。 |
| `plugin.yml` vs `paper-plugin.yml` | 本工程默认交付 `plugin.yml`（传统插件加载器，兼容 Purpur，支持 `softdepend`）。若改用 `paper-plugin.yml`，差异：① 用 `dependencies` / `load-after` 取代 `softdepend`，依赖需写 `{name, load, required, join-classpath}`；② 支持 `bootstrapper`、库加载器与更强的插件隔离；③ 部分旧事件/API 在隔离类加载器下行为不同；④ Purpur 对 `paper-plugin.yml` 的支持需实测。需要极致隔离或显式依赖管理时再切换，本次不切换。 |
| 包路径替换 | 占位包为 `cn.yourname.maintenance`。需要替换时改 **3 处**：`build.gradle` 的 `group`、每个 Java 文件的 `package` 声明、`plugin.yml` 的 `main`。 |
| 版本以工件为准 | 在线 javadoc 的摘要与 1.21.11 实际工件可能不一致（本次就遇到 javadoc 摘要把 `Result` 常量写错）。所有签名以 `paper-api:1.21.11-R0.1-SNAPSHOT` 实际 jar / javap 结果为准；工程已通过该版本真实编译。 |

---

## 二、完整项目树

```
MinecraftMaintenance/
├── settings.gradle
├── build.gradle
├── src/main/resources/
│   ├── plugin.yml
│   ├── config.yml
│   └── messages.yml
└── src/main/java/cn/yourname/maintenance/
    ├── MaintenancePlugin.java             主类：状态切换、线程调度、文案入口、豁免判定
    ├── api/
    │   └── PluginConfig.java              不可变配置视图（settings + bypass + messages）
    ├── config/
    │   ├── Settings.java                  config.yml 的 record 快照
    │   ├── BypassSnapshot.java            不可变豁免名单 + UUID 规范化/容错
    │   ├── ConfigManager.java             读写、缺键兜底、备份、名单增删
    │   └── StateStore.java                维护状态 + state.yml 持久化 + IP 口径
    ├── text/
    │   ├── TextUtil.java                  MiniMessage/旧色码自动探测与兜底
    │   └── PlaceholderHook.java           PlaceholderAPI 反射软依赖
    ├── util/
    │   └── IpUtil.java                    InetAddress 归一化
    ├── listener/
    │   ├── PreLoginListener.java          异步主拦截
    │   ├── LoginListener.java             同步兜底拦截
    │   ├── PingListener.java              服务器列表 MOTD / maxPlayers / 样本
    │   └── PlayerQuitListener.java        退出清理去重记录
    └── command/
        ├── MaintenanceCommand.java        全部子命令与权限校验
        └── MaintenanceTabCompleter.java   Tab 补全
```

每个 Java 文件的**全文**就是仓库中对应路径的源码（可直接点开查看），没有省略段、没有 TODO、没有伪代码。`build/` 是编译产物目录，jar 位于 `build/libs/MaintenanceMode-1.0.0.jar`。

---

## 三、plugin.yml 全文

```yaml
name: MaintenanceMode
version: '${version}'
main: cn.yourname.maintenance.MaintenancePlugin
api-version: '1.21'
author: YourName
description: 'Paper/Purpur 1.21.x 维护模式插件：独立员工豁免名单、Async 登录双保险拦截、Component MOTD、热重载与状态持久化。'
website: https://github.com/zhangmingshan1089/minecraft-maintenance-plugin

# PlaceholderAPI 是软依赖：不安装也能正常启动；安装了则在文案中额外支持 %papi_xxx% 之外的原生 PAPI 占位符
softdepend: [PlaceholderAPI]

commands:
  maintenance:
    description: 维护模式总命令
    aliases: [mt, maint]
    usage: /<command> <on|off|status|reload|add|remove|list|ip>

permissions:
  maintenance.admin:
    description: 全部维护命令（含 add/remove/ip）
    default: op
    children:
      maintenance.reload: true
      maintenance.notify: true
      maintenance.bypass: true
  maintenance.reload:
    description: 仅允许 reload
    default: op
  maintenance.notify:
    description: 接收维护开关广播
    default: op
  maintenance.bypass:
    description: 维护期间豁免进入（也可仅靠此权限进入，不必进名单）
    default: false
```

---

## 四、config.yml 与 messages.yml 默认模板全文

### config.yml

```yaml
# ============================================================================
#  MaintenanceMode / config.yml   —— 行为与开关
#  中文注释为说明用途；Bukkit 的 YamlConfiguration 在自动补写缺失键时会丢失注释，
#  插件会在补写前自动生成 .bak 备份，详见 README《假设与口径》。
#
#  【重要】本文件的 bypass.staff 是插件自建的独立「测试工作人员豁免名单」，
#  与 server.properties / vanilla whitelist.json / Bukkit.setWhitelist() 完全无关，
#  插件绝不修改原版白名单。
# ============================================================================

# 是否把维护开关与原因持久化到 state.yml（重启后仍保持维护态）。默认 true。
# 设为 false 时每次启动都视为「未开启维护」，且不会写 state.yml。
persist-state: true

# 豁免名单判定口径（满足任意一条即放行）
bypass:
  # UUID 始终优先且始终生效（自动规范化为小写带横线）。
  # 此项仅控制「玩家名大小写不敏感兜底匹配」是否启用。默认 true。
  allow-name-fallback: true

  # 是否检查 maintenance.bypass 权限节点。默认 true。
  # 关闭后即使给了权限也不能进，只能靠名单 / 回环地址。
  permission: true

  # 独立工作人员豁免名单：可写 UUID（推荐，规范化小写带横线）或玩家名。
  # 运行时用 /mt add|remove 增删，立即生效并写回本文件。
  staff:
    - '00000000-0000-0000-0000-000000000000'   # 示例占位（无风险的 nil UUID），请删除后写自己的

# 本机回环地址豁免：127.0.0.1 / ::1 / 0:0:0:0:0:0:0:1 / localhost 经 InetAddress 归一化后匹配。
ip-bypass:
  enabled: true
  # 安全边界（务必读 README）：
  #  - false（默认）：插件把事件里的对端地址当作玩家真实地址。直连本机时 127.0.0.1 可进。
  #    若服务端在 Velocity/Bungee/反代后面且对端就是代理的 127.0.0.1，则所有玩家都会被误判为豁免，
  #    此时应把 enabled 设为 false，或正确配置代理转发后再开启。
  #  - true：仅在已配置 PROXY 协议 / Velocity modern forwarding + forwarding-secret，
  #    且 Paper 已把事件地址改写成真实玩家 IP 后，才声明「信任转发地址」。
  #  - 插件【不会】解析 X-Forwarded-For / X-Real-IP 之类请求头，绝不无条件信任它们。
  trust-forwarded-ip: false

# 行为参数
behavior:
  # 开启维护时踢出在线非豁免玩家的缓冲 tick。0 = 同 tick 立即执行（默认）。
  # >0 时用 scheduler 延迟执行，执行前会再次确认维护态仍在开启，避免「先关后踢」。
  kick-delay-ticks: 0

  # 单批次逐条 INFO 日志上限；超出的玩家只记一条汇总，防刷屏。0 表示只记汇总。
  log-kick-limit: 10

  # /mt reload 后若仍处维护态，是否重新筛一遍在线玩家并踢出新增的非豁免者。默认 true。
  recheck-online-on-reload: true

  # 开关广播是否也发给已豁免的在线员工。默认 true（false 时只有 maintenance.notify 且非豁免者收到）。
  broadcast-include-staff: true

  # %until% 占位符的文本（预计恢复时间）。留空显示「待公告」，不参与任何逻辑判断。
  estimated-restore: '待公告'

# 服务器列表（Server List Ping）显示策略
server-list:
  # 是否在服务器列表展示维护态。默认 true。
  enabled: true

  # 伪装 maxPlayers；-1 = 不改写（保留真实值）。
  override-max-players: -1

  # 伪装版本字符串；留空 = 不改写。
  # 注意：Paper 1.21 的 version 只有 String API（setVersion），没有 Component 版；MOTD 用 Component 版。
  version-text: ''

  # 是否隐藏在线人数 / 玩家样本（客户端显示 ???）。默认 false。
  hide-players: false

  # hover 玩家样本，每行一条；首行建议放维护提示。留空列表 = 不改写样本。
  player-sample:
    - '服务器维护中'
    - '请关注公告'
```

### messages.yml

```yaml
# ============================================================================
#  MaintenanceMode / messages.yml   —— 全部文案（简体中文默认模板）
#
#  兼容两套写法，可混用：
#   1) 旧色码：&c &e &l &r，以及 6 位十六进制 &x&f&f&0&0&0&0
#   2) Adventure MiniMessage：<red> <gradient:#ff0000:#ff8800> <newline>
#      <click:run_command:'/mt status'> ... </click>
#  非法 MiniMessage 标签由 TextUtil 捕获并兜底为纯文本，不会让插件崩溃。
#
#  内置占位符：%player% %uuid% %ip% %reason% %staff_count% %time% %until%
#              %prefix% %state% %count% %list%
#  额外：如已安装 PlaceholderAPI，文案中的 %papi_xxx% 之外的原生占位符亦会被展开
#        （通过软依赖反射调用 PlaceholderAPI#setPlaceholders）。
# ============================================================================

# 统一前缀；TextUtil 会在未显式写 %prefix% 的文案前自动加上它
prefix: '<gray>[<red>维护<gray>]<reset> '

# 登录被拒（AsyncPlayerPreLoginEvent / PlayerLoginEvent）——玩家只会看到这段，无额外报错
login-deny: |-
  <red><bold>服务器正在维护中</bold>
  <gray>请稍后再试，感谢理解。
  <dark_gray>—— 维护组

# 开启维护瞬间踢出在线非豁免玩家
kick-online: |-
  <red>服务器已进入维护状态
  <gray>原因：<white>%reason%
  <gray>预计恢复：<white>%until%

# 服务器列表 MOTD（多行用 <newline>）
motd: |-
  <gradient:#ff5555:#ffaa00><bold>    服务器维护中    </bold></gradient>
  <gray>    预计恢复：%until%    <newline><yellow>    请关注官方公告    </yellow>

# 开关广播（发给 maintenance.notify 权限者；是否包含豁免员工由 behavior.broadcast-include-staff 决定）
broadcast-on: '%prefix%<red>维护模式已开启</red> <gray>原因：%reason%'
broadcast-off: '%prefix%<green>维护模式已关闭</green> <gray>服务器恢复正常。'

# 命令回执
no-permission: '%prefix%<red>你没有权限执行该命令。'
reload-done: '%prefix%<green>配置已重载。</green> <gray>豁免名单 %staff_count% 人，维护状态未丢失。'
staff-added: '%prefix%<green>已加入豁免名单：</green><white>%list%</white> <gray>（当前 %staff_count% 人）'
staff-removed: '%prefix%<yellow>已从豁免名单移除：</yellow><white>%list%</white> <gray>（当前 %staff_count% 人）'
staff-not-found: '%prefix%<red>未在豁免名单中找到：</red><white>%list%</white>'
staff-list: |-
  %prefix%<yellow>豁免名单（%staff_count% 人）：
  <gray>%list%
ip-status: '%prefix%<gray>回环地址豁免：<white>%state%</white> <gray>（信任转发 IP：%trust%）'
ip-set: '%prefix%<green>回环地址豁免已设为：</green><white>%state%</white>'
state-on: '开启'
state-off: '关闭'
usage: '%prefix%<gray>用法：<white>/mt <on|off|status|reload|add|remove|list|ip>'
unknown-sub: '%prefix%<red>未知子命令：</red><white>%reason%</white> <gray>输入 /mt 查看用法。'

# /mt status 输出（%state% 为「开启 / 关闭」，%time% 为当前时间）
status: |-
  %prefix%<yellow>维护状态：<white>%state%</white>
  <gray>原因：<white>%reason%</white>
  <gray>开启时间：<white>%time%</white>
  <gray>预计恢复：<white>%until%</white>
  <gray>豁免名单：<white>%staff_count%</white> 人
  <gray>回环豁免：<white>%list%</white>
```

> 说明：Bukkit 的 `YamlConfiguration` 在自动补齐缺失键时会重写文件并丢失行内注释。本工程只在检测到缺键时写回，且写回前自动生成 `config.yml.bak` / `messages.yml.bak`。首次正常启动不会重写带注释的模板。

---

## 五、速查表

### 命令

| 命令 | 权限 | 说明 |
| --- | --- | --- |
| `/mt on [原因]` | `maintenance.admin` | 开启；踢出非豁免在线玩家 |
| `/mt off` | `maintenance.admin` | 关闭；列表 MOTD 自动还原 |
| `/mt status` | `maintenance.admin` | 查看状态/原因/名单人数 |
| `/mt reload` | `maintenance.admin` 或 `maintenance.reload` | 热重载；不丢状态、不重注册 |
| `/mt add <名或UUID>` | `maintenance.admin` | 加入豁免名单，立即生效 |
| `/mt remove <名或UUID>` | `maintenance.admin` | 移出豁免名单，立即生效 |
| `/mt list` | `maintenance.admin` | 列出豁免名单 |
| `/mt ip <on\|off\|status>` | `maintenance.admin` | 运行时切换回环豁免 |

别名：`/mt`、`/maint`；控制台默认拥有全部权限。

### 权限

| 节点 | 默认 | 作用 |
| --- | --- | --- |
| `maintenance.admin` | op | 全部命令（含 reload / notify / bypass 子权限） |
| `maintenance.bypass` | false | 豁免进入，无需进名单 |
| `maintenance.notify` | op | 接收开关广播 |
| `maintenance.reload` | op | 仅 `/mt reload` |

### 配置键

| 键 | 默认 | 作用 |
| --- | --- | --- |
| `persist-state` | true | 是否持久化维护态到 state.yml |
| `bypass.allow-name-fallback` | true | 玩家名大小写不敏感兜底 |
| `bypass.permission` | true | 是否检查 `maintenance.bypass` |
| `bypass.staff` | [] | 独立豁免名单（UUID/玩家名） |
| `ip-bypass.enabled` | true | 回环地址是否豁免 |
| `ip-bypass.trust-forwarded-ip` | false | 是否声明信任转发后的真实 IP |
| `behavior.kick-delay-ticks` | 0 | 踢人缓冲 tick |
| `behavior.log-kick-limit` | 10 | 单批次逐条日志上限 |
| `behavior.recheck-online-on-reload` | true | reload 后重筛在线玩家 |
| `behavior.broadcast-include-staff` | true | 广播是否含豁免员工 |
| `behavior.estimated-restore` | 待公告 | `%until%` 文本 |
| `server-list.*` | 见模板 | MOTD / maxPlayers / version / 隐藏 / 样本 |

---

## 六、构建与部署

仓库地址：https://github.com/zhangmingshan1089/minecraft-maintenance-plugin

1. 环境：JDK **21**、Gradle 9.x（wrapper 未附带时直接用系统 gradle）。
2. 构建：

```bash
export JAVA_HOME=/path/to/jdk-21
gradle clean build
```

3. 产物：`build/libs/MaintenanceMode-1.0.0.jar`，复制到服务端的 `plugins/` 目录。
4. 依赖仓库：`https://repo.papermc.io/repository/maven-public/`（仅编译期 `compileOnly`，不 shade 进 jar）。
5. 首次启动会在 `plugins/MaintenanceMode/` 生成 `config.yml`、`messages.yml`（已有则不覆盖）。
6. 验证热重载：`/mt reload`，观察回执与日志；维护态不会丢失，监听器不会重复注册。
7. 目标服务端：Purpur 1.21.11（基于 Paper）。仅用 paper-api 公共 API；**不支持 Folia**，不支持 Spigot / Forge / Fabric。

---

## 七、验收清单

1. **本机 127.0.0.1 可进**：`ip-bypass.enabled: true` 且直连本机时，未进名单的账号也能进入。
2. **另一 IP 只看到维护提示 + 列表 MOTD 也变**：`/mt on` 后异地登录被拒，拒绝框只有维护文案；服务器列表 MOTD 换成维护模板。
3. **`/mt on` 瞬间踢人**：非豁免在线玩家被踢，名单内 / 有 `maintenance.bypass` / 回环地址者留下；日志逐条上限生效。
4. **add/remove 即时生效**：`/mt add <玩家>` 后该玩家立即可进；`/mt remove` 后再次登录被拒，无需重启。
5. **off 后全恢复**：登录放行、MOTD 还原为服务端原值（插件不再改写事件）。
6. **reload 不丢状态、不重复监听**：维护中 reload 仍保持维护态且只触发一次拦截逻辑。
7. **反代两种口径分别测**：直连场景回环豁免生效；开启 `trust-forwarded-ip` 且代理正确转发真实 IP 后，异地玩家仍被拒。
8. **缺配置 / 非法 MiniMessage 标签不崩**：删掉 config.yml 某键后启动自动补默认值并备份；把文案改成 `<not_a_real_tag:???>` 只显示字面文本，控制台不报错。

---

## 八、已知限制

- **API 版本**：`paper-api:1.21.11-R0.1-SNAPSHOT` 的具体签名请以该工件实际 jar 的 javadoc / javap 为准，在线 javadoc 摘要可能滞后或写错（本次 `AsyncPlayerPreLoginEvent.Result` 就以此方式核实）。工程已针对该版本真实编译通过。
- **version 只有 String API**：Paper 1.21 的 `PaperServerListPingEvent#setVersion(String)` 没有 Component 重载；MOTD 则用 `motd(Component)`。`setMotd(String)` 仍可用但不推荐，代码仅在非 Paper 事件的兜底分支使用。
- **Folia 不支持**：本工程依赖 Bukkit 全局调度器与全局在线玩家集合，不适用于 Folia 的区域线程模型。
- **代理信任前提**：`trust-forwarded-ip=true` 只是运维侧的信任声明，插件本身不验证 PROXY 协议头，也不解析 HTTP 转发头；必须由代理侧正确配置转发与 secret。
- **离线权限粒度**：预登录阶段的权限豁免对非 OP 离线玩家只能依靠名单；需要「临时给权限组就能进」时，请确保玩家先以某种方式被权限插件识别，或直接把 UUID 加进名单。
- **配置写回丢注释**：Bukkit YAML 序列化不保留注释，自动补键后原行内注释会消失；备份文件 `.bak` 保留原始内容。
- **PlaceholderAPI**：仅反射调用 `setPlaceholders(Player, String)`，不注册自定义 Expansion；未安装 PAPI 时相关占位符保持字面文本，不影响主流程。
