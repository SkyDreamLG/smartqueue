# SmartQueue

<div align="center">

**NeoForge 1.21.1 智能玩家排队系统**

[![NeoForge](https://img.shields.io/badge/NeoForge-21.1.248-orange?style=flat-square)](https://neoforged.net/)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-green?style=flat-square)](https://minecraft.net/)
[![License](https://img.shields.io/badge/License-LGPL%203.0-blue?style=flat-square)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21-red?style=flat-square)](https://adoptium.net/)

</div>

**中文** | [English](./README.md)

---

## 概述

SmartQueue 用一套可配置的、带优先级的排队系统替代了原版 Minecraft 的"服务器已满"拒绝机制。当服务器达到玩家上限后，新连接会被挂起在 NeoForge 的配置阶段（Configuration Phase）—— 他们会看到一个实时更新的排队界面，显示当前位置和预计等待时间，并在有空位时自动入场。Staff 和 VIP 玩家享有优先排队位置和更快的入场速度，排队中断线的玩家可以在宽限时间内重连并原地恢复位置，不影响其他玩家排位。

### 功能特性

- **可配置的玩家上限** — 将 `effective_max_players` 设得比 `server.properties max-players` 更低，预留管理员通道或强制启用排队
- **VIP 专属槽位** — 将部分服务器名额预留给 VIP 玩家，确保赞助玩家始终能够进入
- **Staff 专属槽位** — 在 Staff 跳过排队模式下，可在有效上限之上额外增加 Staff 专属容量，Staff 加入不占用普通玩家名额
- **实时排队界面** — 显示当前位置、排队总人数、前方等待人数、预计等待时间
- **排队详情展示** — 按队列分类展示各队列（Staff、优先重连、VIP、普通）的总人数和前方人数，可配置开关；实时更新，按实际放行顺序（含比例放行和防失衡状态）正确计算前方各队列人数
- **优先级分级** — Staff（最高优先级）、VIP、普通玩家，支持可配置的放行模式
- **比例放行模式** — 可选的比例放行（如"3个VIP后放2个普通"），配合防失衡保护，防止普通玩家被无限插队
- **四个独立队列** — Staff、优先重连、VIP、普通四个队列，严格按优先级顺序放行
- **断线重连恢复位置** — 在宽限时间内断开重连，可以恢复原来的排队位置
- **重连频率限制** — 可配置时间窗口内优先重连的次数上限，防止玩家反复利用优先重连插队
- **断线位置保留** — 排队玩家短暂断线后在可配置的宽限时间内保持队列位置；重连后无缝恢复，不影响其他玩家排位
- **自动补位** — 每个 tick 检查是否有空位，确保有空位时立即补入排队玩家
- **暂停/恢复** — 维护期间可冻结排队，不踢出任何玩家
- **完整的多语言支持** — 内置英文（`en_us`）和简体中文（`zh_cn`）
- **配置文件热重载** — 运行时编辑 TOML 文件，修改自动生效
- **游戏内管理命令** — 使用 `/smartqueue` 命令开关、暂停、查看状态、管理 Staff/VIP 名单，无需重启服务器
- **公共状态命令** — `/smartqueue status` 面向所有玩家开放（无需权限），任何人都可以查看排队情况。OP 和 staff（可配置）看到包含玩家名和身份标签的完整详情；普通玩家只看到简化版，显示玩家名但不暴露 VIP/staff 身份标签
- **音效反馈** — 进入队列、离开队列、排队完成入场时播放提示音效

## 运行要求

| 组件 | 版本 |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.248+ |
| Java | 21+ |

SmartQueue **需要同时安装在服务端和客户端**。服务端处理排队逻辑、入场控制和优先级管理。客户端负责渲染排队界面 GUI 和处理"离开队列"按钮 — 这需要模组代码在客户端上运行。

## 安装

### 服务端 & 客户端

1. 从 [Releases](#) 下载最新的 `smartqueue-1.5.0-NeoForge-1.21.1.jar`
2. 分别放入**服务端**的 `mods/` 目录和**每个玩家客户端**的 `mods/` 目录
3. 启动服务器。`config/` 目录下会自动生成三个配置文件：
   - `smartqueue-server.toml` — 排队参数设置
   - `smartqueue-staff.toml` — Staff 名单
   - `smartqueue-vip.toml` — VIP 名单
4. 根据需要编辑配置文件，修改会自动生效（无需重启）

### 单机 / 局域网

模组同样支持单机模式。在世界设置中将 `maxPlayers` 设为大于 `effective_max_players` 的值即可在本地测试排队功能。

## 工作原理

### 配置阶段挂起

当玩家连接时，如果当前活跃玩家数已达到 `effective_max_players`，SmartQueue 会拦截 `PlayerList.placeNewPlayer()` 并取消原版的玩家放置。玩家不会直接进入游戏世界，而是被挂起在 NeoForge 的**配置阶段**（Configuration Phase）——即登录完成到进入游戏之间的协议状态。

在此阶段：
- 服务端每 5 秒向客户端发送 `QueueStatusPayload` 数据包，包含当前位置、排队总人数、前方人数和预计等待时间
- 客户端显示 `QueueScreen` 排队界面，由模组在客户端渲染
- 通过 Mixin 注入 `ServerConfigurationPacketListenerImpl.tick()`，重置原版的超时计时器并移除 Netty 的 `ReadTimeoutHandler`，确保排队连接不会因超时被断开

### 入场机制

SmartQueue 支持两种放行模式，通过 `proportional_mode` 配置项切换。

#### 传统模式（`proportional_mode = false`，默认）

每个服务端 tick，两个独立的入场计时器并行运行：
- **VIP 计时器**（默认：每 40 tick / 2 秒）— 从队列中放行第一个 Staff 或 VIP 玩家
- **普通计时器**（默认：每 100 tick / 5 秒）— 从队列中放行第一个普通玩家

两个计时器仅在 `activeCount() < effective_max_players` 时触发。此外还有每 tick 运行的兜底检查（安全网），按优先级顺序立即填补空位：Staff → 优先重连 → VIP → 普通。

#### 比例放行模式（`proportional_mode = true`）

使用单一计时器（走 `normal_admit_interval_ticks` 间隔），按可配置的比例交替放行：

```
每次计时器触发的放行顺序：
  1. Staff                （始终最先，不受配额限制）
  2. 优先重连              （游戏中断线重连的玩家，FIFO 顺序）
  3. 防失衡补放行          （偿还被跳过的普通名额 — 见下文）
  4. 比例交替              （VIP:普通 比例循环）
```

比例循环维护当前阶段（VIP 阶段或普通阶段）和计数器：
- **VIP 阶段**：放行最多 `proportional_vip_count` 个 VIP，达到配额后切换到普通阶段
- **普通阶段**：放行最多 `proportional_normal_count` 个普通玩家，达到配额后切换回 VIP 阶段
- 如果当前阶段的队列为空，立即切换阶段，不浪费放行机会
- 安全网（玩家退出时的即时补位）也遵循比例阶段并正确更新计数器，保证快节奏进出时比例不被打破

**防失衡保护：** 比例循环到达普通阶段时，如果普通槽位已满（受 `vip_exclusive_slots` 限制），且 VIP 和普通队列都有人在等：
1. 记录跳过：`skippedNormalCount + 1`
2. 立即切回 VIP 阶段继续放行，不让空位闲置
3. 当普通槽位被释放后，系统进入**防失衡模式**：暂停比例交替，改为按真实加入顺序（`joinOrder`，跨 VIP 和普通队列统一排序，谁先排谁先进）放行
4. 放行的是**普通玩家** → `skippedNormalCount - 1`（偿还债务）
5. 放行的是**VIP 玩家** → 计数不变（VIP 不被卡住，但不减少债务）
6. 当 `skippedNormalCount` 归零 → 恢复正常比例放行

这确保了 VIP 永远不会完全饿死普通玩家 — 每次被跳过的普通放行机会都最终会被偿还。

入场时，模组设置 `ADMITTING` 标志（通过 `ThreadLocal` 传递），然后调用 `placeNewPlayer()` 真正将玩家放入世界。由于 `ADMITTING` 标志的存在，Mixin 守卫不会再次拦截。入场后向客户端发送 `admitted=true` 的状态包，排队界面自动关闭，玩家进入游戏世界。客户端只看到统一的"前方还有 X 位玩家"计数 — 所有的内部分队列和比例逻辑对玩家不可见。

### 断线检测与超时保护

当排队玩家的连接断开时，SmartQueue **不会立即将其移出**。玩家的位置会在队列中保留一段可配置的宽限时间（`queue_disconnect_grace_ticks`，默认 60 秒）。在此期间：

- 断线的条目保留在队列列表中 — 其他玩家的排位保持稳定
- 放行**跳过**断线条目；其后方第一个在线的玩家会被放行
- 如果玩家在宽限时间内重连，位置无缝恢复（无需"重连" — 同一槽位直接激活）
- 宽限期过后，条目永久移除 — 玩家下次连接需重新排队

| 机制 | 位置 | 说明 |
|---|---|---|
| **断线事件** | `ServerConfigDisconnectMixin` | 捕获配置阶段的 `onDisconnect` → 标记为 DISCONNECTED（若 `queue_disconnect_grace_ticks = 0` 则直接移除） |
| **Tick 清理** | `QueueManager.cleanupDisconnected()` | 每 tick 遍历所有排队连接，将失活连接标记为 DISCONNECTED |
| **过期清理** | `QueueManager.cleanupExpiredDisconnected()` | 每 tick 移除宽限期已过的 DISCONNECTED 条目 |

防止原版踢出空闲排队玩家：
| 机制 | 位置 | 说明 |
|---|---|---|
| **计时器重置** | `ConfigTickHeadMixin` | 每 tick 重置 `keepAlivePending`、`keepAliveTime`、`closedListenerTime` |
| **超时处理器移除** | `ConfigTickHeadMixin` | 从 Netty channel 管道中移除 `ReadTimeoutHandler`（30 秒读超时的根源） |

### "离开队列"按钮

排队界面提供"离开队列"按钮。点击后：
1. 客户端从 NeoForge 的 `IPayloadContext` 中获取当前 `Connection` 引用（收到状态包时缓存）
2. 调用 `Connection.disconnect()` 关闭 TCP 连接
3. 跳转至标题界面
4. 服务端检测到断线 → 标记 DISCONNECTED，宽限期内保留位置

### 连接看门狗

客户端监控 `QueueStatusPayload` 数据包的到达情况来检测连接异常：

| 阶段 | 条件 | 行为 |
|---|---|---|
| **正常** | 约每 5 秒收到一个数据包 | 排队界面正常更新 |
| **警告** | >30 秒未收到数据包 | 排队界面显示橙色 `[!] 服务器连接异常 — 等待恢复中...` 警告。位置和 ETA 冻结在上次收到的值。如果数据包恢复，警告自动清除。 |
| **连接断开** | TCP channel 变为 inactive（例如服务端进程被杀死） | 客户端通过 Netty channel 状态立即检测到 `!isConnected()`，几秒内返回标题界面。 |
| **放弃等待** | >60 秒未收到数据包 | 客户端断开连接并返回标题界面。这是兜底机制，处理 TCP channel 仍然 open 但服务端不发送数据的情况（如 tick 线程卡死）。 |

如果服务端重启，客户端几乎立即检测到死掉的 TCP channel（通过 OS 发送的 TCP RST），返回标题界面。玩家无需等待超时就可以立刻重新连接。但请注意，排队数据存储在服务端内存中，服务端重启意味着所有排队位置和重连记录丢失 — 玩家需要重新排队。

## 配置文件

### `smartqueue-server.toml`

所有参数位于 `[queue]` 节下。

| 键 | 类型 | 默认值 | 范围 | 说明 |
|---|---|---|---|---|
| `enabled` | bool | `true` | — | 总开关。设为 `false` 时，所有排队玩家立即入场，新玩家跳过排队直接进入。 |
| `effective_max_players` | int | `20` | 1–1024 | 最大活跃（非排队）玩家数。设为比 `server.properties max-players` 更低的值可以预留管理员通道。 |
| `max_queue_size` | int | `50` | 0–1024 | 最大排队人数。超出此限制的新连接会被断连并提示服务器已满。 |
| `normal_admit_interval_ticks` | int | `100` | 1–72000 | 每放行一个普通玩家的间隔 tick 数。20 tick = 1 秒（默认 5 秒放一个）。 |
| `vip_admit_interval_ticks` | int | `40` | 1–72000 | 每放行一个 Staff/VIP 玩家的间隔 tick 数（默认 2 秒放一个）。 |
| `rejoin_grace_ticks` | int | `6000` | 0–1728000 | WAS_PLAYING 重连宽限时间：正在游戏中的玩家断线后重连服务器已满时，进入优先重连队列。0 = 禁用。默认 6000 tick（5 分钟）。 |
| `queue_disconnect_grace_ticks` | int | `6000` | 0–72000 | 排队玩家断线后保留其队列位置的时长（tick）。在此期间重连可无缝恢复位置。超时后永久移除。0 = 立即移除（不保留位置）。默认 6000 tick（5 分钟）。 |
| `staff_bypass_queue` | bool | `false` | — | Staff 在服务器满时的行为。`false`（默认）= Staff 进入队列但排到最前面（插队）。`true` = Staff 完全跳过排队直接进入。**设为 `true` 时，请确保 `effective_max_players` 低于 `server.properties max-players`**，为 staff 预留直接进入的容量。推荐配合 `staff_exclusive_slots`（见下方）使用，避免降低普通玩家上限。 |
| `staff_exclusive_slots` | bool | `false` | — | 启用 Staff 专属额外槽位。仅在 `staff_bypass_queue = true` 时生效。启用后，前 N 个 Staff（由 `staff_exclusive_slots_count` 设定）不计入 `effective_max_players`，在不减少普通玩家容量的前提下为 Staff 提供额外通道。 |
| `staff_exclusive_slots_count` | int | `2` | 0–1024 | Staff 专属额外槽位数量。仅在 `staff_exclusive_slots = true` 时使用。> 0 时，最多这么多 Staff 不计入上限。设为 `0` 表示无限（仅受 `server.properties max-players` 限制）。超出此数量的 Staff 仍可跳过排队，但会占用普通槽位。 |
| `vip_exclusive_slots` | int | `0` | 0–1024 | 为 VIP 玩家保留的专属槽位数量。> 0 时，普通玩家上限 = `effective_max_players - vip_exclusive_slots`。剩余槽位仅供 VIP（以及 `staff_bypass_queue=false` 时的 staff）使用。示例：`effective_max_players=35`, `vip_exclusive_slots=5` → 普通玩家上限为 30 人。若误设为大于 `effective_max_players` 的值，会自动钳制为有效上限。 |
| `proportional_mode` | bool | `false` | — | 启用比例放行模式。设为 `true` 时，VIP 和普通玩家按可配置的比例交替放行（如每放 3 个 VIP 后放 1 个普通，循环往复）。Staff 不受比例限制，始终最先放行。设为 `false` 时使用传统的双计时器模式（VIP 和普通各自有独立的放行间隔）。 |
| `proportional_vip_count` | int | `2` | 1–100 | 每个比例周期放行的 VIP 数量。仅在 `proportional_mode = true` 时生效。 |
| `proportional_normal_count` | int | `1` | 1–100 | 每个比例周期放行的普通玩家数量。仅在 `proportional_mode = true` 时生效。 |
| `staff_see_detailed_status` | bool | `true` | — | 非 OP 的 staff 成员能否看到完整排队详情（包含玩家名和身份标签）。OP（权限等级 2+）始终可见完整视图。设为 `false` 时，staff 看到的简化视图与普通玩家一致。 |
| `show_queue_detail` | bool | `true` | — | 向客户端展示各队列详细人数。设为 `true` 时，排队玩家可看到各队列（Staff、优先重连、VIP、普通）的总人数以及前方各类队列的人数（按实际放行顺序计算）。设为 `false` 时，客户端仅显示简单位次数字。若 `staff_bypass_queue = true`，客户端不显示 Staff 队列行。 |
| `rejoin_rate_limit_enabled` | bool | `false` | — | 启用重连频率限制。设为 `true` 时，如果玩家在 `rejoin_rate_limit_window_ticks` 时间窗口内超过 `rejoin_rate_limit_max_count` 次优先重连，后续重连将被视为新连接（不享受优先重连待遇）。重连链中断后（未在宽限期内重连）计数器归零。 |
| `rejoin_rate_limit_window_ticks` | int | `36000` | 1–1728000 | 重连频率限制的时间窗口（tick）。默认 36000 tick = 30分钟。 |
| `rejoin_rate_limit_max_count` | int | `3` | 1–1000 | 时间窗口内允许的最大优先重连次数。默认 3 次。 |

### `smartqueue-staff.toml`

```toml
staff = ["Admin1", "OwnerName"]
```

- 大小写不敏感的用户名列表
- Staff 玩家享有**最高优先级** — 排在 VIP 和普通玩家之前
- Staff 按 **VIP 间隔**（更快）入场

### `smartqueue-vip.toml`

```toml
vip = ["Supporter1", "FriendName"]
```

- 大小写不敏感的用户名列表
- VIP 玩家享有**中等优先级** — 排在 Staff 之后、普通玩家之前
- VIP 按 **VIP 间隔**（更快）入场

### 热重载

三个配置文件均由 NeoForge 内置的文件监控机制监听。服务器运行时直接编辑 `.toml` 文件，修改会在几秒内生效。使用 `/smartqueue reload` 确认已重载。

## 命令参考

管理类命令需要**权限等级 2**（管理员）。`/smartqueue status` 面向**所有玩家**开放。根命令：`/smartqueue`

### 队列控制

| 命令 | 说明 |
|---|---|
| `/smartqueue toggle on` | 启用排队 |
| `/smartqueue toggle off` | 禁用排队（立即放行所有排队玩家） |
| `/smartqueue toggle` | 查看当前开关状态 |
| `/smartqueue pause` | 暂停入场（玩家留在队列中，不新放行） |
| `/smartqueue resume` | 恢复入场（重置计时器，继续放行） |
| `/smartqueue reload` | 确认配置已重载 |
| `/smartqueue status` | 查看排队状态。**OP 和 staff**（可通过 `staff_see_detailed_status` 配置关闭）看到活跃玩家数、最大容量、放行模式及比例、VIP 专属槽位使用情况、排队总人数、四个独立队列（Staff / 优先重连 / VIP / 普通）及每个玩家的名字和身份标签。**普通玩家**看到简化版：活跃玩家数、最大容量、排队总人数，以及两个合并队列 — 优先重连队列（断线重返）和普通队列（staff + VIP + 普通合并）— 只显示玩家名，不暴露身份标签。 |

### Staff 管理

| 命令 | 说明 |
|---|---|
| `/smartqueue staff add <名称>` | 将玩家添加到 Staff 名单（最高优先级），持久化到 `smartqueue-staff.toml` |
| `/smartqueue staff remove <名称>` | 从 Staff 名单移除玩家，持久化到文件，更新队列顺序 |
| `/smartqueue staff list` | 列出所有 Staff 名单 |

### VIP 管理

| 命令 | 说明 |
|---|---|
| `/smartqueue vip add <名称>` | 将玩家添加到 VIP 名单（中等优先级），持久化到 `smartqueue-vip.toml` |
| `/smartqueue vip remove <名称>` | 从 VIP 名单移除玩家，持久化到文件，更新队列顺序 |
| `/smartqueue vip list` | 列出所有 VIP 名单 |

## 优先级规则

SmartQueue 维护四个独立队列。放行顺序严格按以下优先级：

| 优先级 | 队列 | 说明 |
|---|---|---|
| 1 | **Staff 队列** | Staff 玩家（来自 `smartqueue-staff.toml`）。始终最先放行，排在其他所有队列之前。 |
| 2 | **优先重连队列** | 正在游戏中断线后重连的玩家（`WAS_PLAYING` 重连）。按 FIFO 顺序放行（先重连的先进入）。 |
| 3 | **VIP 队列** | VIP 玩家（来自 `smartqueue-vip.toml`）。比例模式下按 VIP:普通 比例放行；传统模式下按较快的 VIP 间隔放行。 |
| 4 | **普通队列** | 其他所有玩家。比例模式下按比例放行；传统模式下按较慢的普通间隔放行。 |

### 队列分配规则

玩家进入排队时的分配：

| 场景 | 目标队列 | 位置 |
|---|---|---|
| Staff 玩家 | Staff 队列 | 最前面（位置 0） |
| WAS_PLAYING 重连（非 staff） | 优先重连队列 | 末尾（FIFO） |
| VIP 玩家 | VIP 队列 | 末尾 |
| 普通玩家 | 普通队列 | 末尾 |

- **Staff** 和 **VIP** 互斥 — 如果玩家同时在两个名单中，Staff 优先
- 默认情况下（`staff_bypass_queue = false`），Staff 和 VIP 玩家在服务器满时**同样需要排队**，只是排在前面、入场更快，并非跳过排队直接进入

### Staff 跳过排队模式

当 `staff_bypass_queue = true` 时，Staff 玩家完全跳过排队直接进入服务器 — 即使服务器已经达到 `effective_max_players` 的上限。这确保管理员能随时进入服务器。

**重要警告：** SmartQueue 的 `canPlayerLogin` Mixin 会压制原版的"服务器已满"拒绝。这意味着 Staff 可能让服务器超过 `server.properties max-players` 的限制。例如，`max-players=32`，已有 32 人在线，此时 Staff 加入 — 服务器会达到 **33/32** 人。

**建议：** 推荐使用 `staff_exclusive_slots`（见下方）为 Staff 增加专属容量，无需降低普通玩家上限即可解决问题。如果不使用专属槽位，则始终将 `effective_max_players` 设得比 `server.properties max-players` 低 1–2 个位置。推荐配置：

```
# server.properties
max-players = 34

# smartqueue-server.toml
effective_max_players = 32
staff_bypass_queue = true
staff_exclusive_slots = true
staff_exclusive_slots_count = 2
```

此配置下：32 个普通槽位 + 2 个 Staff 专属槽位 = 34 人上限，Staff 不减普通容量，且不超过 `server.properties max-players`（设为 34）。

### VIP 专属槽位

当 `vip_exclusive_slots` 设置为大于 0 的值时，服务器的一部分容量将专门保留给 VIP 玩家。普通玩家上限为 `effective_max_players - vip_exclusive_slots`，剩余槽位仅供 VIP 资格玩家占据。

**工作原理示例：** `effective_max_players = 35`, `vip_exclusive_slots = 5`

| 场景 | 非 VIP 在线 | VIP 资格在线 | 非 VIP 能进？ | VIP 能进？ |
|---|---|---|---|---|
| 服务器较空 | 20 | 3 | 是（20 < 30） | 是（23 < 35） |
| 非 VIP 达到上限 | 30 | 2 | **排队**（30 ≥ 30） | 是（32 < 35） |
| 服务器全满 | 30 | 5 | **排队**（30 ≥ 30） | **排队**（35 ≥ 35） |

**与 `staff_bypass_queue` 的交互：**

- `staff_bypass_queue = false`（默认）：VIP **和** staff 都计入 VIP 专属槽位。排队中的 staff 会被视为"VIP 资格"玩家占用槽位。
- `staff_bypass_queue = true`：仅 VIP 玩家计入 VIP 专属槽位。Staff 完全跳过排队，不计入 VIP 槽位计数（但仍占据服务器上的一个普通位置）。

**自动钳制：** 如果 `vip_exclusive_slots` 被误设为大于 `effective_max_players` 的值，会自动钳制为 `effective_max_players`（即全部槽位均为 VIP 专属），防止配置错误导致异常。

### Staff 专属槽位

当 `staff_bypass_queue = true` 且 `staff_exclusive_slots = true` 时，服务器可以在**不减少普通玩家容量**的前提下，额外容纳 Staff 玩家。前 N 个 Staff（由 `staff_exclusive_slots_count` 配置）占用 `effective_max_players` 之外的专属扩容槽位。

**工作原理示例：** `effective_max_players = 32`, `staff_exclusive_slots_count = 2`

| 场景 | 非 Staff 在线 | Staff 在线 | 实际人数 | 非 Staff 能进？ | Staff 能进？ |
|---|---|---|---|---|---|
| 服务器未满 | 25 | 1 | 26 | 是（25 < 32） | 是（跳过排队） |
| 非 Staff 满额 | 32 | 0 | 32 | **排队** | 是（跳过排队，占第 1/2 个专属位） |
| Staff 占满专属位 | 32 | 2 | 34 | **排队**（非 Staff=32） | 是（跳过排队，但占用普通槽位） |
| 配合 VIP 专属 | 27常+5VIP | 2 | 34 | **排队**（非 VIP=27=上限） | 是（跳过排队） |

**关键行为：**

- **普通玩家**只看到 `effective_max_players` 作为服务器上限（如 32）— Staff 专属槽位对其不可见
- **OP 和 Staff**（当 `staff_see_detailed_status = true` 时）看到带标注的容量如 `32（Staff专属+2）` 及详细的 Staff 槽位使用情况
- **`staff_exclusive_slots_count = 0`** 表示 Staff 专属槽位**无限制** — 所有 Staff 均不占用普通名额（仅受 `server.properties max-players` 约束）
- 超出专属槽位数量的 Staff 仍可跳过排队，但会**占用普通槽位**，减少普通玩家可用容量
- Staff 专属槽位与 VIP 专属槽位**相互独立** — Staff 不占用 VIP 配额

**建议：** 将 `server.properties max-players` 设为至少 `effective_max_players + staff_exclusive_slots_count`，确保原版上限不会阻止 Staff 进入。

## 断线与重连

SmartQueue 对两种断线场景采用不同机制：

### 1. 排队中断线 — 位置保留

玩家在**排队等待中**断线时，其位置在队列中保留 `queue_disconnect_grace_ticks`（默认 60 秒）。在此窗口内重连可无缝恢复原位置。超时后条目永久移除，玩家需重新排队。

此机制由上文的 [断线检测与超时保护](#断线检测与超时保护) 章节描述的位置保留功能处理。

### 2. 游戏中断线（WAS_PLAYING）— 优先重连

玩家**正在游戏中**断线后，在 `rejoin_grace_ticks`（默认 5 分钟）内重连到已满服务器时，进入**优先重连队列** — 排在 Staff 之后、所有 VIP 和普通玩家之前。

| 配置项 | 默认值 | 用途 |
|---|---|---|
| `queue_disconnect_grace_ticks` | 6000（5分钟） | 排队中断线的位置保留时长 |
| `rejoin_grace_ticks` | 6000（5分钟） | 游戏中断线后的优先重连窗口 |

## 玩家体验

### 排队界面

当玩家连接时服务器已满，他们会看到：

```
┌──────────────────────────────────────┐
│         服务器排队中                  │
│                                      │
│    排队位置：第 5 / 16 位             │
│                                      │
│    --- 队列概览 ---                  │
│    Staff:     1人, 前方0人            │
│    优先重连:  1人, 前方0人            │
│    VIP:       3人, 前方2人            │
│    普通:      6人, 前方2人            │
│                                      │
│    前方还有 4 位玩家                  │
│    ETA: 35s                          │
│                                      │
│    请耐心等待，你正在排队中。          │
│                                      │
│    请不要关闭游戏。                   │
│                                      │
│         [ 离开队列 ]                  │
└──────────────────────────────────────┘
```

- 排队位置随玩家入场或离开实时更新
- 当 `show_queue_detail = true` 时，显示各队列详细人数（总人数和前方人数），按实际放行顺序计算
- ETA 根据前方 Staff/VIP/普通玩家的混合比例动态计算
- 排队暂停时，标题变为"服务器排队中 [已暂停]"并显示红色暂停提示
- 到达第 1 位时，前方人数变为绿色的"下一个就是你！"
- **按 ESC 无效** — 排队界面无法意外关闭
- 点击"离开队列"断开连接并返回标题界面

### 音效反馈

SmartQueue 在排队的关键时刻播放提示音效：

| 事件 | 音效 | 说明 |
|---|---|---|
| 进入队列 | `join_queue` | 首次进入队列时播放（每次位置更新不重复播放） |
| 离开队列 | `leave_queue` | 主动离开队列、连接中断或超时退出时播放 |
| 排队完成入场 | `queue_completed` | 被放行进入游戏世界时播放 |

音效文件（`.ogg`）位于 `assets/smartqueue/sounds/`。如需自定义音效，替换这些文件或修改 `sounds.json` 指向不同的音频资源。

### 完整流程

1. 连接到已满的服务器
2. 听到入场音效，看到排队界面，显示"排队位置：第 1 / 1 位"
3. 实时看到位置和 ETA 随后方玩家加入而更新
4. 到达"下一个就是你！"→ 听到入场完成音效 → 游戏世界加载

## 架构

```
┌──────────────────────────────────────────────────────────┐
│                      服务端                              │
│                                                          │
│  PlayerListMixin (placeNewPlayer)                        │
│    │   需要排队？                                         │
│    ├──► QueueManager.enqueue() ──► 玩家挂起在配置阶段     │
│    │                                                      │
│  QueueManager.onServerTick()                             │
│    ├── 清理断线/过期条目                                   │
│    ├── 放行玩家（传统双计时器 或 比例模式）               │
│    ├── 防失衡补放行（比例模式）                            │
│    └── 每 100 tick 广播 QueueStatusPayload                │
│                                                          │
│  ConfigTickHeadMixin                                     │
│    └── 重置 keepAlive 计时器 + 移除 Netty 超时处理器      │
│                                                          │
│  ServerConfigDisconnectMixin (onDisconnect)              │
│    └── 标记 DISCONNECTED + 保留位置                       │
├──────────────────────────────────────────────────────────┤
│                      网络层                              │
│                                                          │
│  QueueStatusPayload（服务端 → 客户端，配置阶段通道）      │
│    - 位置、总人数、前方人数、是否入场、暂停状态、ETA      │
│                                                          │
│  QueueActionPayload（客户端 → 服务端）                    │
│    - LEAVE_QUEUE（预留；当前通过 TCP 断连实现）           │
├──────────────────────────────────────────────────────────┤
│                      客户端                              │
│                                                          │
│  ClientQueueState.captureConnection()                    │
│    - 从网络上下文缓存 Connection 对象                     │
│                                                          │
│  ClientQueueState.update()                               │
│    - 更新位置/ETA → 刷新 QueueScreen                      │
│                                                          │
│  QueueClientEvents.onClientTick()                        │
│    - 每 tick 重新确认 QueueScreen 显示                    │
│                                                          │
│  QueueScreen                                             │
│    - 渲染位置、ETA、离开按钮                             │
│    - onClose() 若仍在排队则重新打开                       │
└──────────────────────────────────────────────────────────┘
```

### Mixin 清单

| Mixin | 目标类 | 用途 |
|---|---|---|
| `PlayerListMixin` | `PlayerList` | 覆写"服务器已满"拒绝；拦截 `placeNewPlayer` 进行排队 |
| `ServerConfigDisconnectMixin` | `ServerConfigurationPacketListenerImpl` | 捕获排队玩家的断线事件 |
| `ConfigTickHeadMixin` | `ServerConfigurationPacketListenerImpl` | 重置 keepalive 计时器，移除 Netty ReadTimeoutHandler |
| `ConfigTickMixin` | `ServerCommonPacketListenerImpl` | `keepAlivePending`、`keepAliveTime`、`closedListenerTime`、`connection` 字段的 Accessor |
| `ConnectionAccessor` | `Connection` | Netty `channel` 字段的 Accessor |
| `MinecraftAccessor` | `Minecraft`（客户端） | `pendingConnection` 字段的 Accessor |

### 网络数据包

| 数据包 | 方向 | 通道 | 用途 |
|---|---|---|---|
| `QueueStatusPayload` | 服务端 → 客户端 | `smartqueue:queue_status` | 位置、ETA、入场通知 |
| `QueueActionPayload` | 客户端 → 服务端 | `smartqueue:queue_action` | 离开队列（预留；当前通过 TCP 断连处理） |

### 数据流向

```
玩家连接
  │
  ├─► 服务器已满？
  │     ├─ 否 → 正常进入游戏
  │     └─ 是 → 挂起在配置阶段
  │               │
  │               ├─► QueueStatusPayload（每 5s）
  │               │     └─► 客户端：更新 QueueScreen 的位置/ETA
  │               │
  │               ├─► 空位出现 → 入场
  │               │     └─► QueueStatusPayload(admitted=true)
  │               │           └─► 客户端：关闭 QueueScreen，进入游戏
  │               │
  │               └─► 玩家点击"离开队列"
  │                     └─► TCP 断连
  │                           └─► 服务端：标记 DISCONNECTED，保留位置
```

## 从源码构建

### 前置条件

- JDK 21+
- Git

### 构建

```bash
git clone <仓库地址>
cd smartqueue
./gradlew build
```

编译产物位于 `build/libs/smartqueue-1.5.0-NeoForge-1.21.1.jar`。

### 开发

```bash
# 启动带模组的 Minecraft 客户端
./gradlew runClient

# 启动带模组的专用服务器
./gradlew runServer

# 运行游戏测试
./gradlew runGameTestServer
```

## 常见问题

### 排队超过 30 秒被踢出

安装 SmartQueue 后不应该出现此问题。模组会移除排队连接的 Netty `ReadTimeoutHandler` 并每 tick 重置原版 keepalive 计时器。如果仍然遇到此问题，请检查：
- SmartQueue 是否安装在**服务端**（仅客户端安装无效）
- 是否有其他模组干扰了 Netty 管道处理器
- 调试日志中是否有 Mixin 应用失败的信息

### 排队界面没有出现

- 确认模组已**同时在服务端和客户端**安装
- 检查 `smartqueue-server.toml` 中 `enabled = true`
- 确认 `effective_max_players` 小于实际玩家数量
- Staff 名单中的玩家享有最高优先级（排在队列最前面；若 `staff_bypass_queue = true` 则直接跳过排队）

### 服务器重启后 Staff/VIP 名单丢失

确认 `config/` 目录有写入权限。通过命令修改的 Staff/VIP 名单会通过 `Files.writeString()` 持久化到 `smartqueue-staff.toml` 和 `smartqueue-vip.toml`。如果目录只读，修改无法保存。

### "离开队列"按钮点击无效

此问题已在最新版本中修复。客户端从 NeoForge 网络上下文中获取活跃的 `Connection` 对象并正确断开。如果问题仍然存在，请查看调试日志中的 `onLeave()` 相关信息。

## 多语言支持

SmartQueue 内置以下语言的完整翻译：

| 语言 | 代码 |
|---|---|
| 英文（美国） | `en_us` |
| 简体中文 | `zh_cn` |

所有可见字符串 — 排队界面文字、命令反馈、错误提示 — 均可翻译。要添加新语言，按照现有翻译文件的键名创建 `assets/smartqueue/lang/<语言代码>.json` 即可。

## 开源协议

SmartQueue 采用 **GNU Lesser General Public License v3.0**（LGPL-3.0）协议开源。详见 [LICENSE](LICENSE) 文件。

## 致谢

- **作者：** SkyDreamLG
- **Minecraft 版本：** 1.21.1
- **模组加载器：** [NeoForge](https://neoforged.net/) 21.1.248
- **构建系统：** Gradle + [moddev 插件](https://github.com/neoforged/moddevgradle) 2.0.143
