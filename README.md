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

SmartQueue replaces vanilla Minecraft's "Server Full" rejection with a configurable, priority-based player queue. When the server reaches its player limit, new connections are parked in NeoForge's Configuration Phase — they see a real-time queue screen with position and ETA, and are admitted automatically as slots open. Staff and VIP players get priority placement and faster admission intervals, and players who disconnect have their position held in the queue for a configurable grace period, resuming seamlessly on reconnect.

### Features

- **Configurable player cap** — set `effective_max_players` lower than `server.properties max-players` to reserve slots or enforce queueing
- **VIP exclusive slots** — reserve a portion of server capacity exclusively for VIP players, ensuring premium users can always get in
- **Staff exclusive slots** — when staff bypass queue is enabled, extra slots can be added on top of `effective_max_players` exclusively for staff, so staff joining doesn't consume normal player capacity
- **Real-time queue screen** — position, total queued, players ahead, estimated wait time
- **Queue detail display** — per-queue breakdown showing total and ahead counts for each queue (Staff, Priority Rejoin, VIP, Normal), configurable on/off; real-time updates reflecting actual dispatch order including proportional mode and anti-imbalance state
- **Priority tiers** — Staff (highest), VIP, and Normal players, with configurable admission modes
- **Proportional admission mode** — optional ratio-based admission (e.g., "3 VIPs then 2 normals") with anti-imbalance protection to prevent normals from being starved
- **First-position lock** — the player at the front of the dispatch order is locked in place and cannot be displaced by newly arriving higher-priority players, ensuring fair queue progression
- **Slot-blocked indicator** — when a normal player reaches the front but cannot enter (VIP-exclusive slots full), the queue screen shows a distinct warning instead of misleadingly saying "You are next!"
- **Admission certainty display** — the `/smartqueue status` detail view marks the guaranteed next player with a green `>>` and uncertain candidates (multiple possible next depending on slot type) with a yellow `?`
- **Four independent queues** — Staff, Priority Rejoin, VIP, and Normal queues with strict admission order
- **Rejoin with position recovery** — disconnect and come back within the grace window to keep your place in line
- **Rejoin rate limiting** — configurable limit on how many times a player can use priority rejoin within a time window, preventing abuse of the rejoin system
- **Disconnect position hold** — briefly disconnected queue players hold their position for a configurable grace period; reconnect seamlessly without losing their spot or shifting other players' positions
- **Automatic slot refill** — safety net on every tick ensures no slot stays empty when players are waiting
- **Pause / resume** — freeze the queue during maintenance without kicking anyone
- **Full i18n** — English (`en_us`) and Simplified Chinese (`zh_cn`) included
- **Hot-reloadable config** — edit TOML files on disk while the server runs; changes take effect automatically
- **In-game management** — `/smartqueue` commands to toggle, pause, view status, and manage staff/VIP lists without restarting
- **Public status command** — `/smartqueue status` is available to all players (no permission required) so anyone can check the queue. OPs and staff (configurable) see full details including player names and identity tags; regular players see a simplified view with player names only — no VIP/staff identity tags exposed
- **Sound effects** — audio feedback when entering the queue, leaving the queue, and being admitted to the server

## Requirements

| Component | Version |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.248+ |
| Java | 21+ |

SmartQueue requires installation on **both the server and the client**. The server handles queue logic, admission, and priority management. The client renders the queue screen GUI and handles the "Leave Queue" button — this requires the mod code to be present on the client.

## Installation

### Server & Client

1. Download the latest `smartqueue-1.5.0-NeoForge-1.21.1.jar` from [Releases](#).
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

SmartQueue supports two admission modes, controlled by the `proportional_mode` config option.

#### Legacy Mode (`proportional_mode = false`, default)

Each server tick, two independent admission timers run:
- **VIP timer** (default: every 40 ticks / 2 seconds) — admits the first queued Staff or VIP player
- **Normal timer** (default: every 100 ticks / 5 seconds) — admits the first queued Normal player

Both timers only fire when `activeCount() < effective_max_players`. A safety net also fires every tick to fill any open slot immediately with the highest-priority waiting player (Staff → Priority Rejoin → VIP → Normal).

#### Proportional Mode (`proportional_mode = true`)

A single admission timer runs (using `normal_admit_interval_ticks`), and players are admitted in a configurable ratio cycle:

```
Admission order per timer tick:
  1. Locked Entry        (first-in-line lock — cannot be displaced)
  2. Staff               (always first among remaining, no quota)
  3. Priority Rejoin     (WAS_PLAYING reconnects, skips unadmittable normals)
  4. Anti-Imbalance      (catch-up for skipped normals — see below)
  5. Proportional cycle  (VIP:N ratio, alternating)
```

The proportional cycle maintains a phase (VIP or Normal) and a counter:
- **VIP phase**: admits up to `proportional_vip_count` VIPs, then switches to Normal phase
- **Normal phase**: admits up to `proportional_normal_count` normals, then switches back to VIP phase
- If a queue is empty, the phase switches immediately to avoid wasting admission opportunities
- The safety net (slot refill on player disconnect) also follows the proportional phase and properly updates the phase counter, ensuring the ratio is maintained even during rapid player turnover

**Anti-Imbalance Protection:** When the proportional cycle reaches the Normal phase but normal slots are full (due to `vip_exclusive_slots`), and there are both VIPs and normals waiting:
1. The skip is counted: `skippedNormalCount + 1`
2. The phase immediately switches back to VIP to keep admissions flowing
3. When a normal slot later becomes available, the system enters **anti-imbalance mode**: admissions are made by real join order (oldest first, across both VIP and Normal queues) instead of the VIP/Normal ratio
4. Every normal admitted through **any path** (locked entry, proportional cycle, or anti-imbalance catch-up) decrements `skippedNormalCount` by 1
5. VIP admissions during anti-imbalance do not affect the counter — only normals repay the debt
6. When `skippedNormalCount` reaches 0, the normal proportional cycle resumes
7. If the queue becomes completely empty, `skippedNormalCount` resets to 0 automatically

This ensures that VIPs never completely starve normals — every skipped normal admission is eventually repaid, regardless of which admission path the normal takes.

When admitted, the player's `placeNewPlayer()` is called for real (bypassing the mixin guard via a `ThreadLocal<Boolean> ADMITTING` flag), the queue screen closes, and they join the game world. Clients only see a unified "X players ahead" count — all internal queue separation and proportional logic is invisible to players.

### Disconnect & Timeout Protection

When a queued player's connection drops, SmartQueue does **not** immediately remove them. Instead, the player's position is held in the queue for a configurable grace period (`queue_disconnect_grace_ticks`, default 60 seconds). During this window:

- The disconnected entry stays in the queue list — other players' positions remain stable
- Admission **skips** disconnected entries; the next connected player behind them gets in
- If the player reconnects within the grace period, their position is restored seamlessly (no "rejoin" needed — the same slot is reactivated)
- If the grace period expires, the entry is permanently removed — the player must queue fresh on their next connection

| Mechanism | Location | Description |
|---|---|---|
| **Disconnect event** | `ServerConfigDisconnectMixin` | Catches `onDisconnect` on the config listener → marks entry DISCONNECTED (or removes immediately if `queue_disconnect_grace_ticks = 0`) |
| **Tick cleanup** | `QueueManager.cleanupDisconnected()` | Every tick, iterates all queued connections and marks inactive ones as DISCONNECTED |
| **Expiry cleanup** | `QueueManager.cleanupExpiredDisconnected()` | Every tick, removes DISCONNECTED entries whose grace period has expired |

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
4. The server detects the disconnect → marks the entry DISCONNECTED, holds position for the grace period

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
| `rejoin_grace_ticks` | int | `6000` | 0–1728000 | Time window for WAS_PLAYING rejoin: a player who was in the game, disconnects, and reconnects to a full server gets Priority Rejoin queue placement. 0 = disabled. Default: 6000 ticks (5 minutes). |
| `queue_disconnect_grace_ticks` | int | `6000` | 0–72000 | Time in ticks a disconnected queue player's position is held in place. Reconnect within this window to resume seamlessly. Expired entries are permanently removed. 0 = immediate removal (no position hold). Default: 6000 ticks (5 minutes). |
| `staff_bypass_queue` | bool | `false` | — | Staff behavior when the server is full. `false` = staff enter the queue at the front (priority insert). `true` = staff skip the queue entirely and join directly. **When `true`, ensure `effective_max_players` is lower than `server.properties max-players`** to reserve slots for staff. Consider using `staff_exclusive_slots` (see below) instead of lowering `effective_max_players`. |
| `staff_exclusive_slots` | bool | `false` | — | Enable staff-exclusive extra slots. Only takes effect when `staff_bypass_queue = true`. When enabled, the first N staff players (set by `staff_exclusive_slots_count`) do NOT count toward `effective_max_players`, allowing extra capacity for staff without reducing normal player slots. |
| `staff_exclusive_slots_count` | int | `2` | 0–1024 | Number of staff-exclusive extra slots. Only used when `staff_exclusive_slots = true`. When > 0, up to this many staff don't count toward `effective_max_players`. When `0`, staff have unlimited exclusive slots (constrained only by `server.properties max-players`). Staff beyond this count still bypass the queue but occupy normal player slots. |
| `vip_exclusive_slots` | int | `0` | 0–1024 | Number of slots reserved exclusively for VIP users. When > 0, non-VIP players are capped at `effective_max_players - vip_exclusive_slots`. The remaining slots can only be filled by VIP (and staff, when `staff_bypass_queue=false`). Example: `effective_max_players=35`, `vip_exclusive_slots=5` → non-VIP cap is 30. If misconfigured higher than `effective_max_players`, the value is clamped automatically. |
| `proportional_mode` | bool | `false` | — | Enable proportional admission mode. When `true`, VIP and normal players are admitted in a configurable ratio (e.g., 3 VIPs then 1 normal, alternating). Staff are always admitted first regardless. When `false`, the legacy dual-timer mode is used (VIPs and normals each have their own independent admission interval). |
| `proportional_vip_count` | int | `2` | 1–100 | Number of VIP players to admit per proportional cycle. Only used when `proportional_mode = true`. |
| `proportional_normal_count` | int | `1` | 1–100 | Number of normal players to admit per proportional cycle. Only used when `proportional_mode = true`. |
| `staff_see_detailed_status` | bool | `true` | — | Whether non-OP staff members can see detailed queue status including player names and identity tags. OPs (permission level 2+) always see the full view regardless. When `false`, staff see the same simplified view as regular players. |
| `show_queue_detail` | bool | `true` | — | Show detailed queue breakdown to clients. When `true`, queued players see how many people are in each queue (Staff, Priority Rejoin, VIP, Normal) and how many of each are ahead of them according to actual dispatch order. When `false`, clients see only the simple position number. If `staff_bypass_queue` is `true`, the Staff queue row is hidden from clients. |
| `rejoin_rate_limit_enabled` | bool | `false` | — | Enable rejoin rate limiting. When `true`, players who repeatedly use priority rejoin to skip the queue are rate-limited: if they exceed `rejoin_rate_limit_max_count` rejoins within `rejoin_rate_limit_window_ticks`, subsequent rejoins are treated as new connections (no priority). The counter resets when the rejoin chain is broken (player fails to rejoin within the grace period). |
| `rejoin_rate_limit_window_ticks` | int | `36000` | 1–1728000 | Time window in ticks for rejoin rate limiting. Default: 36000 ticks = 30 minutes. |
| `rejoin_rate_limit_max_count` | int | `3` | 1–1000 | Maximum priority rejoins allowed within the rate limit window. Default: 3. |

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

All administrative commands require **permission level 2** (operator). `/smartqueue status` is available to **all players**. Root command: `/smartqueue`

### Queue Control

| Command | Description |
|---|---|
| `/smartqueue toggle on` | Enable the queue |
| `/smartqueue toggle off` | Disable the queue (admits all queued players immediately) |
| `/smartqueue toggle` | Show current on/off state |
| `/smartqueue pause` | Pause admission (players stay queued, no new admits) |
| `/smartqueue resume` | Resume admission (resets timers, continues admitting) |
| `/smartqueue reload` | Confirm config reload |
| `/smartqueue status` | Show queue status. **OPs and staff** (configurable via `staff_see_detailed_status`) see active players, total capacity (with staff-exclusive breakdown in `X / Y (Z+N)` format), admission mode and ratio, VIP exclusive slot usage, admission certainty (green `>>` for definite next, yellow `?` for uncertain candidates when both VIP and Normal are waiting but one type is slot-blocked), total queued, and four queue sections (Staff / Priority Rejoin / VIP / Normal) with player names and identity tags. **Regular players** see a simplified view: active players, max capacity, total queued, and two merged queues — Priority Rejoin Queue (disconnect rejoin) and Normal Queue (staff + VIP + normal merged) — with player names but no identity tags. |

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

SmartQueue maintains four independent queues. The admission order is strictly:

| Priority | Queue | Description |
|---|---|---|
| 0 | **Locked Entry** | The first player in the dispatch order is locked in place — cannot be displaced by newly arriving higher-priority players. Whether they can actually enter is checked at admission time (e.g., a normal player blocked by VIP-exclusive slots will be skipped until a slot opens). |
| 1 | **Staff Queue** | Staff players (from `smartqueue-staff.toml`). Always admitted first among remaining queues, before all others. |
| 2 | **Priority Rejoin Queue** | Players who were actively playing, disconnected, and then reconnected to a full server (`WAS_PLAYING` rejoin). Admitted in FIFO order (first to reconnect gets in first). Normal players in this queue are skipped if VIP-exclusive slots are full. |
| 3 | **VIP Queue** | VIP players (from `smartqueue-vip.toml`). In proportional mode, admitted according to the VIP:Normal ratio. In legacy mode, admitted at the faster VIP interval. |
| 4 | **Normal Queue** | All other players. In proportional mode, admitted according to the ratio. In legacy mode, admitted at the slower normal interval. |

### Queue Placement

When a player is queued:

| Scenario | Target Queue | Position |
|---|---|---|
| Staff player | Staff Queue | Front (position 0) |
| WAS_PLAYING rejoin (non-staff) | Priority Rejoin Queue | End (FIFO) |
| VIP player | VIP Queue | End |
| Normal player | Normal Queue | End |

- **Staff** and **VIP** are mutually exclusive — if a player is both, Staff takes precedence.
- By default (`staff_bypass_queue = false`), Staff and VIP players are still required to queue when the server is full; they simply get priority placement and faster admission, not a bypass.

### Staff Bypass Mode

When `staff_bypass_queue = true`, staff players skip the queue entirely and join the server directly — even when it is "full" (as defined by `effective_max_players`). This allows staff to always access the server regardless of player count.

**Important:** SmartQueue's `canPlayerLogin` mixin suppresses vanilla's "Server Full" rejection. This means staff can push the server beyond `server.properties max-players`. For example, with `max-players=32`, 32 players online, and a staff member joining — the server would reach **33/32** players.

**Recommendation:** Use `staff_exclusive_slots` (see below) to add dedicated extra slots for staff without reducing normal player capacity. If not using exclusive slots, always set `effective_max_players` at least 1–2 slots lower than `server.properties max-players`. For example:

```
# server.properties
max-players = 34

# smartqueue-server.toml
effective_max_players = 32
staff_bypass_queue = true
staff_exclusive_slots = true
staff_exclusive_slots_count = 2
```

With this setup: 32 normal slots + 2 staff-exclusive slots = 34 max, staff don't reduce normal capacity, and `server.properties max-players` (set to 34) is never exceeded.

### VIP Exclusive Slots

When `vip_exclusive_slots` is set to a value greater than 0, a portion of the server's capacity is reserved exclusively for VIP players. Non-VIP players are capped at `effective_max_players - vip_exclusive_slots`, and the remaining slots can only be occupied by VIP-eligible players.

**How it works — example:** `effective_max_players = 35`, `vip_exclusive_slots = 5`

| Scenario | Non-VIP online | VIP-eligible online | Non-VIP joins? | VIP joins? |
|---|---|---|---|---|
| Server mostly empty | 20 | 3 | Yes (20 < 30) | Yes (23 < 35) |
| Non-VIP cap reached | 30 | 2 | **Queued** (30 ≥ 30) | Yes (32 < 35) |
| Server full | 30 | 5 | **Queued** (30 ≥ 30) | **Queued** (35 ≥ 35) |

**Interaction with `staff_bypass_queue`:**

- `staff_bypass_queue = false` (default): Both VIP **and** staff count toward VIP-exclusive slots. A staff player who is queued counts as "VIP-eligible" for slot occupancy.
- `staff_bypass_queue = true`: Only VIP players count toward VIP-exclusive slots. Staff bypass the queue entirely and do not affect VIP slot counting (but they do occupy a regular slot on the server).

**Auto-clamping:** If `vip_exclusive_slots` is accidentally set higher than `effective_max_players`, it is automatically clamped to `effective_max_players` (treating all slots as VIP-exclusive) to prevent misconfiguration.

### Staff Exclusive Slots

When `staff_bypass_queue = true` and `staff_exclusive_slots = true`, the server can host extra staff players **without reducing normal player capacity**. The first N staff players (configured by `staff_exclusive_slots_count`) occupy dedicated extra slots on top of `effective_max_players`.

**How it works — example:** `effective_max_players = 32`, `staff_exclusive_slots_count = 2`

| Scenario | Non-Staff online | Staff online | Actual players | Non-staff joins? | Staff joins? |
|---|---|---|---|---|---|
| Server not full | 25 | 1 | 26 | Yes (25 < 32) | Yes (bypass) |
| Non-staff at cap | 32 | 0 | 32 | **Queued** | Yes (bypass, uses slot 1/2) |
| Staff in exclusive slots | 32 | 2 | 34 | **Queued** (non-staff=32) | Yes (bypass, but occupies a normal slot) |
| With VIP exclusive | 27 + 5VIP | 2 | 34 | **Queued** (non-VIP=27=limit) | Yes (bypass) |

**Key behaviors:**

- **Normal players** see `effective_max_players` as the server limit (e.g., 32) — staff-exclusive slots are invisible to them
- **OPs and staff** (when `staff_see_detailed_status = true`) see the total capacity with breakdown, e.g. `Active Players: 30 / 34 (32+2)` showing 32 base + 2 staff exclusive, and detailed staff slot usage
- **`staff_exclusive_slots_count = 0`** means **unlimited** staff exclusive slots — all staff are uncapped (constrained only by `server.properties max-players`)
- Staff beyond the exclusive slot count still bypass the queue but **occupy a normal slot**, reducing capacity for regular players
- Staff in exclusive slots do NOT count toward VIP exclusive slot occupancy — the two mechanisms are independent

**Recommendation:** Set `server.properties max-players` to at least `effective_max_players + staff_exclusive_slots_count` to ensure the vanilla limit doesn't block staff.

## Disconnect & Rejoin

SmartQueue handles two types of disconnects differently:

### 1. Queue Disconnect — Position Hold

When a player disconnected **while waiting in the queue**, their position is held for `queue_disconnect_grace_ticks` (default 60 seconds). Reconnect within this window to resume seamlessly at the same position. If the window expires, the entry is permanently removed and the player must queue fresh.

This is handled by the position-hold mechanism described in [Disconnect & Timeout Protection](#disconnect--timeout-protection) above.

### 2. In-Game Disconnect (WAS_PLAYING) — Priority Rejoin

When a player was **actively playing**, disconnects, and reconnects to a full server within `rejoin_grace_ticks` (default 5 minutes), they are placed in the **Priority Rejoin Queue** — admitted after Staff but before all VIP and Normal queues.

| Configuration | Default | Purpose |
|---|---|---|
| `queue_disconnect_grace_ticks` | 6000 (5min) | Position hold for queued player disconnect |
| `rejoin_grace_ticks` | 6000 (5min) | Priority rejoin window for in-game player disconnect |

## Client Experience

### Queue Screen

When a player connects and the server is full, they see:

```
┌──────────────────────────────────────┐
│         Server Queue                 │
│                                      │
│    Position: 5 / 16                  │
│                                      │
│    --- Queue Overview ---            │
│    Staff:     1 total, 0 ahead       │
│    Priority:  1 total, 0 ahead       │
│    VIP:       3 total, 2 ahead       │
│    Normal:    6 total, 2 ahead       │
│                                      │
│    4 player(s) ahead of you          │
│    ETA: 35s                          │
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
- When `show_queue_detail = true`, a per-queue breakdown shows how many people are in each queue (total) and how many of each type are ahead of you (according to actual dispatch order)
- ETA is calculated dynamically based on the mix of Staff/VIP/Normal players ahead
- When paused, the title changes to "Server Queue [PAUSED]" and a red pause notice appears
- "You are next!" (green) replaces the ahead count when the player reaches position 1 and can be admitted
- "First in line, waiting for an available slot..." (yellow) appears when the player is at position 1 but blocked by slot constraints (e.g., VIP-exclusive slots preventing normal entry)
- **Pressing ESC does nothing** — the queue screen cannot be dismissed accidentally
- Clicking "Leave Queue" disconnects and returns to the title screen

### Sound Effects

SmartQueue plays audio feedback at key queueing moments:

| Event | Sound | Description |
|---|---|---|
| Entering the queue | `join_queue` | Played when the player first enters the queue (not on every position update) |
| Leaving the queue | `leave_queue` | Played when the player voluntarily leaves the queue, or when the connection is lost/times out |
| Admitted to server | `queue_completed` | Played when the player is admitted and joins the game world |

Sound files (`.ogg`) are located in `assets/smartqueue/sounds/`. To customize sounds, replace these files or modify `sounds.json` to point to different audio resources.

### What the player sees

1. Connect to a full server
2. Hear the join sound, see the queue screen with position "Position: 1 / 1"
3. Watch the position and ETA update as players join behind them
4. Position reaches "You are next!" → hear the admission sound → game world loads (or "Waiting for slot..." if blocked by VIP-exclusive limits)

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
│    ├── Cleanup disconnected / expired entries             │
│    ├── Admit players (legacy dual-timer or proportional) │
│    ├── Anti-imbalance catch-up (proportional mode)       │
│    └── Broadcast QueueStatusPayload every 100 ticks      │
│                                                          │
│  ConfigTickHeadMixin                                     │
│    └── Reset keepAlive timers + remove Netty timeout     │
│                                                          │
│  ServerConfigDisconnectMixin (onDisconnect)              │
│    └── Mark DISCONNECTED + hold position                 │
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
  │                           └─► Server: mark DISCONNECTED, hold position
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

The compiled jar will be at `build/libs/smartqueue-1.5.0-NeoForge-1.21.1.jar`.

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
- Staff players (listed in `smartqueue-staff.toml`) bypass the queue screen when `staff_bypass_queue = true`

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
