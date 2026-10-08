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

**Minecraft Player is authoritative.** The host game captures input and *follows* the result; it
never moves the Minecraft player.

```text
host keyboard/mouse
        ↓  (host captures only)
     InputRing
        ↓
    Minecraft            ← Minecraft decides movement/physics/collision
        ↓
  Minecraft Player
        ↓
     McState
        ↓
    host adapter          ← CoordinateMapper
        ↓
  host player (follows)
```

- Host → Minecraft player channel = `InputRing` (input events). There is **no** "host transform →
  Minecraft player" path.
- Minecraft → host = `McState` (authoritative position/rotation/flags); the host's
  `CoordinateMapper` turns it into host space and moves the host player.
- `HostState` is the host's **own** avatar/environment (viewport, camera mode); its position/rotation
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
