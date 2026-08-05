# SmartQueue

<div align="center">

**A smart player queue system for NeoForge 1.21.1**

[![NeoForge](https://img.shields.io/badge/NeoForge-21.1.248-orange?style=flat-square)](https://neoforged.net/)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-green?style=flat-square)](https://minecraft.net/)
[![License](https://img.shields.io/badge/License-LGPL%203.0-blue?style=flat-square)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21-red?style=flat-square)](https://adoptium.net/)

</div>

**English** | [中文](./README_zh.md)

---

## Overview

SmartQueue replaces vanilla Minecraft's "Server Full" rejection with a configurable, priority-based player queue. When the server reaches its player limit, new connections are parked in NeoForge's Configuration Phase — they see a real-time queue screen with position and ETA, and are admitted automatically as slots open. Staff and VIP players get priority placement and faster admission intervals, and players who disconnect can rejoin within a configurable grace period without losing their queue position.

### Features

- **Configurable player cap** — set `effective_max_players` lower than `server.properties max-players` to reserve slots or enforce queueing
- **Real-time queue screen** — position, total queued, players ahead, estimated wait time
- **Priority tiers** — Staff (highest), VIP, and Normal players, each with independent admission intervals
- **Rejoin with position recovery** — disconnect and come back within the grace window to keep your place in line
- **Automatic slot refill** — safety net on every tick ensures no slot stays empty when players are waiting
- **Pause / resume** — freeze the queue during maintenance without kicking anyone
- **Full i18n** — English (`en_us`) and Simplified Chinese (`zh_cn`) included
- **Hot-reloadable config** — edit TOML files on disk while the server runs; changes take effect automatically
- **In-game management** — `/smartqueue` commands to toggle, pause, view status, and manage staff/VIP lists without restarting

## Requirements

| Component | Version |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.248+ |
| Java | 21+ |

SmartQueue requires installation on **both the server and the client**. The server handles queue logic, admission, and priority management. The client renders the queue screen GUI and handles the "Leave Queue" button — this requires the mod code to be present on the client.

## Installation

### Server & Client

1. Download the latest `smartqueue-1.0.0.jar` from [Releases](#).
2. Place it in **both** the server's `mods/` directory and each player's client `mods/` directory.
3. Start the server. Three config files will be generated in `config/`:
   - `smartqueue-server.toml` — queue settings
   - `smartqueue-staff.toml` — staff username list
   - `smartqueue-vip.toml` — VIP username list
4. Edit the configs as needed. Changes are applied automatically (no restart required).

### Single-player / LAN

The mod also works in single-player. Set `effective_max_players` to a value lower than `maxPlayers` in your world settings to test the queue on a local world.

## How It Works

### The Configuration Phase

When a player exceeds the server's `effective_max_players`, SmartQueue intercepts `PlayerList.placeNewPlayer()` and cancels the vanilla player placement. Instead of joining the world, the player is parked in NeoForge's **Configuration Phase** — the protocol state between login and gameplay.

During this phase:
- The server sends `QueueStatusPayload` packets periodically (every 5 seconds) with the player's current position, total queued, players ahead, and ETA
- The client displays the `QueueScreen` GUI, rendered by the mod on the client side
- A mixin on `ServerConfigurationPacketListenerImpl.tick()` resets vanilla timeout timers and removes the Netty `ReadTimeoutHandler` so the connection survives indefinitely

### Admission

Each server tick, two independent admission timers run:
- **VIP timer** (default: every 40 ticks / 2 seconds) — admits the first queued Staff or VIP player
- **Normal timer** (default: every 100 ticks / 5 seconds) — admits the first queued Normal player

Both timers only fire when `activeCount() < effective_max_players`. A safety net also fires every tick to fill any open slot immediately.

When admitted, the player's `placeNewPlayer()` is called for real (bypassing the mixin guard via a `ThreadLocal<Boolean> ADMITTING` flag), the queue screen closes, and they join the game world.

### Disconnect & Timeout Protection

A mixin on `ServerConfigurationPacketListenerImpl.onDisconnect()` catches disconnects from queued players. Two additional mechanisms ensure cleanup:

| Mechanism | Location | Description |
|---|---|---|
| **Disconnect event** | `ServerConfigDisconnectMixin` | Catches `onDisconnect` on the config listener → removes from queue, saves rejoin entry |
| **Tick cleanup** | `QueueManager.cleanupDisconnected()` | Every tick, iterates all queued connections and removes any where `!isConnected()` |

To prevent vanilla from kicking idle queued players:
| Mechanism | Location | Description |
|---|---|---|
| **Timer reset** | `ConfigTickHeadMixin` | Resets `keepAlivePending`, `keepAliveTime`, and `closedListenerTime` every tick |
| **Timeout removal** | `ConfigTickHeadMixin` | Removes Netty's `ReadTimeoutHandler` (30s read timeout) from the channel pipeline |

### "Leave Queue" Button

The queue screen includes a "Leave Queue" button. When clicked:
1. The client captures the active `Connection` (obtained from NeoForge's `IPayloadContext` when status packets arrive)
2. Calls `Connection.disconnect()` to close the TCP channel
3. Navigates to the title screen
4. The server detects the disconnect → saves a rejoin entry → removes the player from the queue

### Connection Watchdog

The client monitors incoming `QueueStatusPayload` packets to detect connection issues:

| Stage | Condition | Behavior |
|---|---|---|
| **Normal** | Packets arrive every ~5 seconds | Queue screen updates as usual |
| **Warning** | >30 seconds without a packet | Orange `[!] Server connection lost — waiting for recovery...` alert appears on the queue screen. Position and ETA freeze at last known values. If packets resume, the alert clears automatically. |
| **Dead connection** | TCP channel becomes inactive (e.g., server process killed) | Client detects `!isConnected()` immediately via Netty channel state and returns to the title screen — typically within seconds of the server going down. |
| **Give up** | >60 seconds without a packet | Client disconnects and returns to the title screen. This is the fallback for cases where the TCP channel remains open but the server sends no data (e.g., tick thread hung). |

If the server restarts, the client detects the dead TCP channel almost immediately (via TCP RST from the OS) and returns to the title screen. The player can reconnect right away without waiting for any timeout. However, queue state is stored in memory on the server, so a server restart means all queue positions and rejoin records are lost — the player starts fresh.

## Configuration

### `smartqueue-server.toml`

All values are under the `[queue]` section.

| Key | Type | Default | Range | Description |
|---|---|---|---|---|
| `enabled` | bool | `true` | — | Master switch. When `false`, all queued players are admitted immediately and new players bypass the queue. |
| `effective_max_players` | int | `20` | 1–1024 | Maximum active (non-queued) players. Set this lower than `server.properties max-players` to reserve operator slots or enforce queueing. |
| `max_queue_size` | int | `50` | 0–1024 | Maximum players waiting in the queue. Connections beyond this are disconnected with a "server full" message. |
| `normal_admit_interval_ticks` | int | `100` | 1–72000 | Ticks between admitting each normal player. 20 ticks = 1 second (default: 5 s). |
| `vip_admit_interval_ticks` | int | `40` | 1–72000 | Ticks between admitting each Staff/VIP player (default: 2 s). |
| `rejoin_grace_ticks` | int | `6000` | 0–1728000 | Time window after disconnecting during which a rejoining player keeps their queue position. 0 = disabled. Default: 6000 ticks (5 minutes). |
| `staff_bypass_queue` | bool | `false` | — | Staff behavior when the server is full. `false` = staff enter the queue at the front (priority insert). `true` = staff skip the queue entirely and join directly. **When `true`, ensure `effective_max_players` is lower than `server.properties max-players`** to reserve slots for staff. |

### `smartqueue-staff.toml`

```toml
staff = ["Admin1", "OwnerName"]
```

- Case-insensitive usernames
- Staff players get **highest priority** in the queue — placed ahead of VIPs and normals
- Staff are admitted at the **VIP interval** (faster than normals)

### `smartqueue-vip.toml`

```toml
vip = ["Supporter1", "FriendName"]
```

- Case-insensitive usernames
- VIP players get **medium priority** — placed after Staff but before Normals
- VIPs are admitted at the **VIP interval** (faster than normals)

### Hot Reload

All three config files are monitored by NeoForge's built-in config watcher. Edit any `.toml` file while the server is running, and changes take effect within seconds. Use `/smartqueue reload` to confirm.

## Commands

All commands require **permission level 2** (operator). Root command: `/smartqueue`

### Queue Control

| Command | Description |
|---|---|
| `/smartqueue toggle on` | Enable the queue |
| `/smartqueue toggle off` | Disable the queue (admits all queued players immediately) |
| `/smartqueue toggle` | Show current on/off state |
| `/smartqueue pause` | Pause admission (players stay queued, no new admits) |
| `/smartqueue resume` | Resume admission (resets timers, continues admitting) |
| `/smartqueue reload` | Confirm config reload |
| `/smartqueue status` | Show queue status: enabled, paused, active players, queue size, and numbered player list with priority tags |

### Staff Management

| Command | Description |
|---|---|
| `/smartqueue staff add <name>` | Add a player to the staff list (highest priority). Persists to `smartqueue-staff.toml`. |
| `/smartqueue staff remove <name>` | Remove a player from the staff list. Persists to file. Updates queue order. |
| `/smartqueue staff list` | List all staff entries |

### VIP Management

| Command | Description |
|---|---|
| `/smartqueue vip add <name>` | Add a player to the VIP list (medium priority). Persists to `smartqueue-vip.toml`. |
| `/smartqueue vip remove <name>` | Remove a player from the VIP list. Persists to file. Updates queue order. |
| `/smartqueue vip list` | List all VIP entries |

## Queue Priority System

When a player joins the queue, their insertion position is determined by this priority hierarchy (highest to lowest):

```
1. Rejoin (WAS_QUEUING)  →  original saved position
2. Rejoin (WAS_PLAYING)  →  after last Staff/VIP entry
3. Staff                 →  after last Staff entry
4. VIP                   →  after last Staff/VIP entry
5. Normal                →  end of queue
```

- **Staff** and **VIP** are mutually exclusive — if a player is both, Staff takes precedence.
- By default (`staff_bypass_queue = false`), Staff and VIP players are still required to queue when the server is full; they simply get priority placement and faster admission, not a bypass.

### Staff Bypass Mode

When `staff_bypass_queue = true`, staff players skip the queue entirely and join the server directly — even when it is "full" (as defined by `effective_max_players`). This allows staff to always access the server regardless of player count.

**Important:** SmartQueue's `canPlayerLogin` mixin suppresses vanilla's "Server Full" rejection. This means staff can push the server beyond `server.properties max-players`. For example, with `max-players=32`, 32 players online, and a staff member joining — the server would reach **33/32** players.

**Recommendation:** Always set `effective_max_players` at least 1–2 slots lower than `server.properties max-players` when using this option. For example:

```
# server.properties
max-players = 32

# smartqueue-server.toml
effective_max_players = 30
staff_bypass_queue = true
```

This reserves 2 slots for staff, ensuring they never need to exceed the vanilla limit.

## Rejoin System

Players who disconnect while in the queue (or while playing, then the server fills up before they return) can rejoin within the grace period and keep their position.

### Rejoin Types

| Type | Trigger | Recovery |
|---|---|---|
| `WAS_QUEUING` | Player disconnects while waiting in the queue | Restored at original position (capped at current queue size) |
| `WAS_PLAYING` | Player was actively playing, then disconnected and tries to rejoin a full server | Placed after Staff/VIP entries (priority over normals) |

### Configuration

- Set `rejoin_grace_ticks` to a positive value (e.g., `6000` = 5 minutes) to enable
- Set `rejoin_grace_ticks` to `0` to disable rejoin position recovery entirely
- Expired rejoin entries are purged each tick

## Client Experience

### Queue Screen

When a player connects and the server is full, they see:

```
┌──────────────────────────────────────┐
│         Server Queue                 │
│                                      │
│    Position: 3 / 12                  │
│    2 player(s) ahead of you          │
│    ETA: 45s                          │
│                                      │
│    Please wait, you are in the       │
│    queue.                            │
│                                      │
│    Do not close the game.            │
│                                      │
│         [ Leave Queue ]              │
└──────────────────────────────────────┘
```

- Position updates in real-time as players are admitted or leave
- ETA is calculated dynamically based on the mix of Staff/VIP/Normal players ahead
- When paused, the title changes to "Server Queue [PAUSED]" and a red pause notice appears
- "You are next!" (green) replaces the ahead count when the player reaches position 1
- **Pressing ESC does nothing** — the queue screen cannot be dismissed accidentally
- Clicking "Leave Queue" disconnects and returns to the title screen

### What the player sees

1. Connect to a full server
2. See the queue screen with position "Position: 1 / 1"
3. Watch the position and ETA update as players join behind them
4. Position reaches "You are next!" → admitted → game world loads

## Architecture

```
┌──────────────────────────────────────────────────────────┐
│                    Server Side                           │
│                                                          │
│  PlayerListMixin (placeNewPlayer)                        │
│    │   should queue?                                     │
│    ├──► QueueManager.enqueue() ──► Player parked in      │
│    │                               Config Phase          │
│    │                                                     │
│  QueueManager.onServerTick()                             │
│    ├── Cleanup disconnected connections                  │
│    ├── Admit players on VIP/Normal intervals             │
│    └── Broadcast QueueStatusPayload every 100 ticks      │
│                                                          │
│  ConfigTickHeadMixin                                     │
│    └── Reset keepAlive timers + remove Netty timeout     │
│                                                          │
│  ServerConfigDisconnectMixin (onDisconnect)              │
│    └── Save rejoin entry + remove from queue             │
├──────────────────────────────────────────────────────────┤
│                    Network Layer                         │
│                                                          │
│  QueueStatusPayload (server → client, config phase)      │
│    - position, total, ahead, admitted, paused, ETA       │
│                                                          │
│  QueueActionPayload (client → server)                    │
│    - LEAVE_QUEUE (unused; client disconnects via TCP)    │
├──────────────────────────────────────────────────────────┤
│                    Client Side                           │
│                                                          │
│  ClientQueueState.captureConnection()                    │
│    - Stores Connection from network context              │
│                                                          │
│  ClientQueueState.update()                               │
│    - Updates position/ETA → QueueScreen                  │
│                                                          │
│  QueueClientEvents.onClientTick()                        │
│    - Re-asserts QueueScreen every tick                   │
│                                                          │
│  QueueScreen                                             │
│    - Renders position, ETA, leave button                 │
│    - onClose() re-opens if still queued                  │
└──────────────────────────────────────────────────────────┘
```

### Mixins

| Mixin | Target | Purpose |
|---|---|---|
| `PlayerListMixin` | `PlayerList` | Override "server full" rejection; intercept `placeNewPlayer` to enqueue |
| `ServerConfigDisconnectMixin` | `ServerConfigurationPacketListenerImpl` | Catch disconnect events for queued players |
| `ConfigTickHeadMixin` | `ServerConfigurationPacketListenerImpl` | Reset keepalive timers and remove Netty ReadTimeoutHandler |
| `ConfigTickMixin` | `ServerCommonPacketListenerImpl` | Accessor for `keepAlivePending`, `keepAliveTime`, `closedListenerTime`, `connection` |
| `ConnectionAccessor` | `Connection` | Accessor for Netty `channel` |
| `MinecraftAccessor` | `Minecraft` (client) | Accessor for `pendingConnection` |

### Network Payloads

| Payload | Direction | Channel | Purpose |
|---|---|---|---|
| `QueueStatusPayload` | Server → Client | `smartqueue:queue_status` | Position, ETA, admission notification |
| `QueueActionPayload` | Client → Server | `smartqueue:queue_action` | Leave queue (reserved; currently handled via TCP disconnect) |

### Data Flow

```
Player Connects
  │
  ├─► Server full?
  │     ├─ No → Join game normally
  │     └─ Yes → Park in Config Phase
  │               │
  │               ├─► QueueStatusPayload (every 5s)
  │               │     └─► Client: update position/ETA on QueueScreen
  │               │
  │               ├─► Slot opens → admitted
  │               │     └─► QueueStatusPayload(admitted=true)
  │               │           └─► Client: close QueueScreen, join game
  │               │
  │               └─► Player clicks "Leave Queue"
  │                     └─► TCP disconnect
  │                           └─► Server: save rejoin entry, remove from queue
```

## Building from Source

### Prerequisites

- JDK 21+
- Git

### Build

```bash
git clone <repository-url>
cd smartqueue
./gradlew build
```

The compiled jar will be at `build/libs/smartqueue-1.0.0.jar`.

### Development

```bash
# Run the Minecraft client with the mod loaded
./gradlew runClient

# Run a dedicated server with the mod loaded
./gradlew runServer

# Run game tests
./gradlew runGameTestServer
```

## Troubleshooting

### Players are being kicked after 30 seconds in the queue

This should not happen with SmartQueue installed. The mod removes the Netty `ReadTimeoutHandler` from queued connections and resets vanilla keepalive timers every tick. If you encounter this, check that:
- SmartQueue is installed on the **server** (not just the client)
- No other mod is interfering with Netty pipeline handlers
- The `smartqueue.mixins.json` is being loaded (check the debug log for Mixin application messages)

### Queue screen doesn't appear for players

- Ensure the mod is installed on **both the server and the client**
- Check that `enabled = true` in `smartqueue-server.toml`
- Verify that `effective_max_players` is lower than the actual player count
- Staff players (listed in `smartqueue-staff.toml`) always bypass the queue screen

### Staff/VIP list changes are lost after server restart

Ensure the mod has write permissions to the `config/` directory. Staff/VIP list changes made via commands are persisted to `smartqueue-staff.toml` and `smartqueue-vip.toml` using `Files.writeString()`. If the directory is read-only, changes cannot be saved.

### "Leave Queue" button does nothing

This was fixed in the latest version. The client captures the active `Connection` from NeoForge's network context and disconnects properly. If this persists, check the debug logs for `onLeave()` messages.

## Internationalization

SmartQueue includes full translations for:

| Language | Code |
|---|---|
| English (US) | `en_us` |
| Simplified Chinese | `zh_cn` |

All visible strings — queue screen text, command feedback, and error messages — are translatable. To add a new language, create a JSON file at `assets/smartqueue/lang/<locale>.json` following the keys in the existing translation files.

## License

SmartQueue is licensed under the **GNU Lesser General Public License v3.0** (LGPL-3.0). See the [LICENSE](LICENSE) file for details.

## Credits

- **Author:** SkyDreamLG
- **Minecraft:** 1.21.1
- **Mod Loader:** [NeoForge](https://neoforged.net/) 21.1.248
- **Build System:** Gradle with [moddev plugin](https://github.com/neoforged/moddevgradle) 2.0.143
