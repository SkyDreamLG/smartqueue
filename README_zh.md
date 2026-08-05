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

SmartQueue 用一套可配置的、带优先级的排队系统替代了原版 Minecraft 的"服务器已满"拒绝机制。当服务器达到玩家上限后，新连接会被挂起在 NeoForge 的配置阶段（Configuration Phase）—— 他们会看到一个实时更新的排队界面，显示当前位置和预计等待时间，并在有空位时自动入场。Staff 和 VIP 玩家享有优先排队位置和更快的入场速度，中途断线的玩家可以在宽限时间内重新连接并恢复原来的排队位置。

### 功能特性

- **可配置的玩家上限** — 将 `effective_max_players` 设得比 `server.properties max-players` 更低，预留管理员通道或强制启用排队
- **实时排队界面** — 显示当前位置、排队总人数、前方等待人数、预计等待时间
- **优先级分级** — Staff（最高优先级）、VIP、普通玩家，各等级使用独立的入场间隔
- **断线重连恢复位置** — 在宽限时间内断开重连，可以恢复原来的排队位置
- **自动补位** — 每个 tick 检查是否有空位，确保有空位时立即补入排队玩家
- **暂停/恢复** — 维护期间可冻结排队，不踢出任何玩家
- **完整的多语言支持** — 内置英文（`en_us`）和简体中文（`zh_cn`）
- **配置文件热重载** — 运行时编辑 TOML 文件，修改自动生效
- **游戏内管理命令** — 使用 `/smartqueue` 命令开关、暂停、查看状态、管理 Staff/VIP 名单，无需重启服务器

## 运行要求

| 组件 | 版本 |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.248+ |
| Java | 21+ |

SmartQueue **需要同时安装在服务端和客户端**。服务端处理排队逻辑、入场控制和优先级管理。客户端负责渲染排队界面 GUI 和处理"离开队列"按钮 — 这需要模组代码在客户端上运行。

## 安装

### 服务端 & 客户端

1. 从 [Releases](#) 下载最新的 `smartqueue-1.0.0.jar`
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

每个服务端 tick，两个独立的入场计时器并行运行：
- **VIP 计时器**（默认：每 40 tick / 2 秒）— 从队列中放行第一个 Staff 或 VIP 玩家
- **普通计时器**（默认：每 100 tick / 5 秒）— 从队列中放行第一个普通玩家

两个计时器仅在 `activeCount() < effective_max_players` 时触发。此外还有每 tick 运行的兜底检查，确保有空位时立即补入。

入场时，模组设置 `ADMITTING` 标志（通过 `ThreadLocal` 传递），然后调用 `placeNewPlayer()` 真正将玩家放入世界。由于 `ADMITTING` 标志的存在，Mixin 守卫不会再次拦截。入场后向客户端发送 `admitted=true` 的状态包，排队界面自动关闭，玩家进入游戏世界。

### 断线检测与超时保护

通过 Mixin 注入 `ServerConfigurationPacketListenerImpl.onDisconnect()` 捕获排队玩家的断线事件。另外还有两套兜底机制：

| 机制 | 位置 | 说明 |
|---|---|---|
| **断线事件** | `ServerConfigDisconnectMixin` | 捕获配置阶段的 `onDisconnect` → 移出队列，保存重连记录 |
| **Tick 清理** | `QueueManager.cleanupDisconnected()` | 每 tick 遍历所有排队连接，移除 `!isConnected()` 的条目 |

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
4. 服务端检测到断线 → 保存重连记录 → 将玩家移出队列

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
| `rejoin_grace_ticks` | int | `6000` | 0–1728000 | 断线后重连保留排队位置的宽限时间。0 = 禁用。默认 6000 tick（5 分钟）。 |
| `staff_bypass_queue` | bool | `false` | — | Staff 在服务器满时的行为。`false`（默认）= Staff 进入队列但排到最前面（插队）。`true` = Staff 完全跳过排队直接进入。**设为 `true` 时，请确保 `effective_max_players` 低于 `server.properties max-players`**，为 staff 预留直接进入的容量。 |

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

所有命令需要**权限等级 2**（管理员）。根命令：`/smartqueue`

### 队列控制

| 命令 | 说明 |
|---|---|
| `/smartqueue toggle on` | 启用排队 |
| `/smartqueue toggle off` | 禁用排队（立即放行所有排队玩家） |
| `/smartqueue toggle` | 查看当前开关状态 |
| `/smartqueue pause` | 暂停入场（玩家留在队列中，不新放行） |
| `/smartqueue resume` | 恢复入场（重置计时器，继续放行） |
| `/smartqueue reload` | 确认配置已重载 |
| `/smartqueue status` | 查看排队状态：开关状态、暂停状态、活跃玩家数、排队人数、带优先级标签的玩家列表 |

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

玩家进入队列时的插入位置由以下优先级决定（从高到低）：

```
1. 重连（WAS_QUEUING） →  恢复到原来保存的位置
2. 重连（WAS_PLAYING） →  排在最后一个 Staff/VIP 之后
3. Staff                →  排在最后一个 Staff 之后
4. VIP                  →  排在最后一个 Staff/VIP 之后
5. 普通玩家              →  队列末尾
```

- **Staff** 和 **VIP** 互斥 — 如果玩家同时在两个名单中，Staff 优先
- 默认情况下（`staff_bypass_queue = false`），Staff 和 VIP 玩家在服务器满时**同样需要排队**，只是排在前面、入场更快，并非跳过排队直接进入

### Staff 跳过排队模式

当 `staff_bypass_queue = true` 时，Staff 玩家完全跳过排队直接进入服务器 — 即使服务器已经达到 `effective_max_players` 的上限。这确保管理员能随时进入服务器。

**重要警告：** SmartQueue 的 `canPlayerLogin` Mixin 会压制原版的"服务器已满"拒绝。这意味着 Staff 可能让服务器超过 `server.properties max-players` 的限制。例如，`max-players=32`，已有 32 人在线，此时 Staff 加入 — 服务器会达到 **33/32** 人。

**建议：** 使用此选项时，始终将 `effective_max_players` 设得比 `server.properties max-players` 低 1–2 个位置。例如：

```
# server.properties
max-players = 32

# smartqueue-server.toml
effective_max_players = 30
staff_bypass_queue = true
```

这样预留了 2 个位置给 Staff，他们永远不会超出原版人数上限。

## 断线重连

排队中或游戏中的玩家断线后，在宽限时间内重新连接可保留或恢复排队位置。

### 重连类型

| 类型 | 触发条件 | 恢复方式 |
|---|---|---|
| `WAS_QUEUING` | 玩家在排队中断开连接 | 恢复到原来的排队位置（上限为当前队列长度） |
| `WAS_PLAYING` | 玩家正在游戏中，断开后重连时服务器已满 | 排在 Staff/VIP 之后（优先于普通玩家） |

### 配置

- 将 `rejoin_grace_ticks` 设为正值（如 `6000` = 5 分钟）开启功能
- 将 `rejoin_grace_ticks` 设为 `0` 完全禁用位置恢复
- 过期的重连记录每个 tick 自动清理

## 玩家体验

### 排队界面

当玩家连接时服务器已满，他们会看到：

```
┌──────────────────────────────────────┐
│         服务器排队中                  │
│                                      │
│    排队位置：第 3 / 12 位             │
│    前方还有 2 位玩家                  │
│    ETA: 45s                          │
│                                      │
│    请耐心等待，你正在排队中。          │
│                                      │
│    请不要关闭游戏。                   │
│                                      │
│         [ 离开队列 ]                  │
└──────────────────────────────────────┘
```

- 排队位置随玩家入场或离开实时更新
- ETA 根据前方 Staff/VIP/普通玩家的混合比例动态计算
- 排队暂停时，标题变为"服务器排队中 [已暂停]"并显示红色暂停提示
- 到达第 1 位时，前方人数变为绿色的"下一个就是你！"
- **按 ESC 无效** — 排队界面无法意外关闭
- 点击"离开队列"断开连接并返回标题界面

### 完整流程

1. 连接到已满的服务器
2. 看到排队界面，显示"排队位置：第 1 / 1 位"
3. 实时看到位置和 ETA 随后方玩家加入而更新
4. 到达"下一个就是你！"→ 自动入场 → 游戏世界加载

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
│    ├── 清理断开连接                                        │
│    ├── 按 VIP/普通间隔放行玩家                            │
│    └── 每 100 tick 广播 QueueStatusPayload                │
│                                                          │
│  ConfigTickHeadMixin                                     │
│    └── 重置 keepAlive 计时器 + 移除 Netty 超时处理器      │
│                                                          │
│  ServerConfigDisconnectMixin (onDisconnect)              │
│    └── 保存重连记录 + 移出队列                            │
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
  │                           └─► 服务端：保存重连记录，移出队列
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

编译产物位于 `build/libs/smartqueue-1.0.0.jar`。

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
- Staff 名单中的玩家会直接跳过排队（但当前版本中他们也需排队，仅排在前面）

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
