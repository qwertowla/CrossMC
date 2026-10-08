# PROTOCOL — CrossMC v4

> The byte layout is defined by `protocol/bridge_protocol.h` (single source of truth) and mirrored
> by `bindings/java` and `bindings/csharp`. This document describes the *semantics*: coordinate
> space, identity, state vs event, lifecycle, capabilities and connection state.

---

## 1. Data authority

| Data | Authoritative owner | Notes |
|---|---|---|
| Minecraft player, MC blocks, MC entity logic, MC rules | **Minecraft** | CrossMC never re-implements MC rules |
| Host entity presentation, host colliders, host-specific attributes | **Host adapter** | e.g. `HowToFishMC` |
| Protocol, CrossEntityId mapping, State/Event transport, capabilities | **CrossMC** | transport only; owns no gameplay state |

A field has exactly one writer. `HostState`/`McState`/collider/entity tables are written by one
side; the other only reads. Rings have a single producer and a single consumer.

### Player control flow

**The product is "play Minecraft, with How to Fish integrated as a second world" — not "play
How to Fish".** Minecraft is the main game and the Minecraft player is the **one** authoritative
player. The host player/camera mirror it.

```text
player keyboard/mouse
        ↓
   Minecraft native input           ← the REAL player control path
        ↓
   Minecraft Player                 ← authoritative position / rotation / health / hunger
        ↓
      McState
        ↓
   host adapter (CoordinateMapper, fixed origin)
        ↓
   host player + host camera (representations / followers)
```

- The player's control is **Minecraft's own input**. `InputRing` is a generic CrossMC capability
  (host → MC events), **not** the normal player control path.
- Minecraft → host = `McState` (authoritative position/rotation/health/hunger/flags): the host player
  and host camera mirror it; nothing in Minecraft reads `HostState` to move the player.
- `HostState` is the host's **own** environment/avatar (viewport, camera mode); its position/rotation
  are informational and must not drive the Minecraft player.

---

## 2. Coordinate space

- **CrossMC defines positions in Minecraft World Space**: right-handed, **+Y up**, **+Z south**,
  units = **Minecraft blocks** (double precision in state, float in tables).
- **Rotation** is in **degrees**, Minecraft convention: `yaw` about +Y (0 = +Z, increasing
  clockwise seen from above), `pitch` about the X axis (-90..+90), `roll` about Z.
- **The protocol always carries MC space.** Host → MC and MC → Host conversion (origin, scale,
  axis flip, handedness) is the **Host adapter's** responsibility, because it is game-specific.
  The How to Fish adapter exposes `transform.origin*`, `transform.scale`, `transform.flipX`.
- There is no `1 host unit = 1 block` assumption; `HostState.unitsPerBlock` carries the ratio.

---

## 3. Identity — CrossEntityId

```text
Minecraft entity id   <->   CrossEntityId   <->   host entity id
```

- `CrossEntityId` is the **stable CrossMC key** (`EntityMap.crossEntityId`, `DamageEvent.crossEntityId`).
- The host allocates it once per host entity and keeps it for the entity's whole life.
- `hostEntityId` is the host's own id (e.g. FishNet `NetworkObject.ObjectId`) — for mapping/debug,
  never the primary key.
- `mcEntityId` is filled in by Minecraft for the bound proxy entity.

Mapping queries: `crossEntityId → {hostEntityId, mcEntityId}` and both directions are carried in the
same row, so a consumer can build lookup tables without guessing.

---

## 4. State vs Event

| Kind | Meaning | Structures | Overwrite semantics |
|---|---|---|---|
| **State** | current world snapshot | `HostState`, `McState`, `ColliderTable`, `EntityTable`, overlay frame | latest wins; readers judge freshness by `timestampMs`/`sequence`/`revision` |
| **Event** | something happened | `DamageRing`/`DamageEvent`, `InputRing`/`InputEvent`, `BlockEditRing`/`BlockEdit` | SPSC ring; monotonic `sequence` for ordering + de-dup |

- State is transported as a whole snapshot under a **seqlock** (collider/entity tables) or a
  latest-value seqlock (`HostState`/`McState`), and the overlay frame as a **triple buffer**.
- Events are transported as a single-producer/single-consumer **ring** with monotonic
  `head`/`tail`; each event carries `sequence` and `timestampMs`.

---

## 5. Collider lifecycle (incremental-ready)

`Collider` carries `id` (stable), `revision` (bump on any change), `flags` with lifecycle bits
`ADDED` / `UPDATED` / `REMOVED`, and `ENABLED` (active). A producer may resend the whole table or,
later, send only changed rows. A consumer diffs by `id`+`revision` and reacts to lifecycle bits.
Idle colliders need not be resent every frame.

---

## 6. Capabilities

`Header.hostCapabilities` / `Header.mcCapabilities` are feature bitmasks (`CROSSMC_CAP_*`):
`FRAME, STATE, ENTITY, COLLISION, DAMAGE, INPUT, DEPTH, BLOCK_EDIT`. A feature is active only when
**both** peers advertise it. There is no negotiation handshake beyond the header.

---

## 7. Connection state

- Each side stamps its Unix-epoch-ms heartbeat (`hostHeartbeatMs` / `mcHeartbeatMs`) and pid.
- `hostAlive(now)` / `mcAlive(now)` are true while the heartbeat is newer than
  `CROSSMC_HEARTBEAT_TIMEOUT_MS` (2000 ms).
- When the host is not alive, Minecraft treats host data (colliders, entities, frames) as **stale**
  and clears its proxies. Reconnect is implicit: when the heartbeat resumes, the snapshot tables are
  re-read and the proxies/colliders are rebuilt (no separate resync protocol yet).

---

## 8. InputRing (generic capability — NOT the player control path)

`InputEvent` / `InputRing` is a **generic** CrossMC host → Minecraft event channel. The normal
player is controlled by **Minecraft's own input**, so a host adapter does not need it for normal
play. It is reserved for host-specific / non-player interaction and future use.

- `InputEvent.type` = `CROSSMC_INPUT_*`; `code` is, for `KEY_*`, a CrossMC keyboard semantic
  (`CROSSMC_KEY_*`), and for `MOUSE_*`, `0` left / `1` right / `2` middle. `sequence` is monotonic.
- If a host *does* use it, Minecraft consumes it by injecting into **its own** input
  (`KeyBinding.setPressed` / `KeyBinding.onKeyPressed`, mouse delta into `Mouse.cursorDeltaX/Y`) — no
  custom movement — and releases everything on `INPUT_RELEASE_ALL`, heartbeat timeout or an open GUI.
- `HostInputConsumer` / `MouseMixin` on the Minecraft side implement this, but they only act when a
  host actually sends events. **Host input capture is off by default** (`input.capture=false`).
