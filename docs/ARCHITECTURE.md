# ARCHITECTURE — CrossMC

> Status: **draft v0.2**. Phase 0 complete; the Phase 1 Minecraft frame producer exists
> (`minecraft/FrameExporter`). This document defines the framework shape and the first vertical
> slice.

---

## 1. Positioning

CrossMC is a general **Minecraft ↔ standalone host game** cross-process bridge framework, not a
single-game mod. How to Fish is the first host adapter (the `HowToFishMC` repository).

```text
                ┌─ HowToFish        (first host adapter, only one for now)
Minecraft ─ Core ─┤─ <future host>
                └─ <future host>
```

**Neither game is rewritten.** Minecraft runs its own game logic; the host runs its own world and
renderer. The bridge only translates between them. If we ever re-implement a Minecraft mechanic
on the host side, or a host mechanic on the Minecraft side, the design has gone wrong.

---

## 2. What "Core" means here

"Core" is **not** one in-process library shared by both sides — the two processes differ in
language and address space. In a cross-process bridge the shared part is:

```text
protocol/   single source of truth for the byte layout (.h), mirrored in C# and Java
bindings/   thin per-language runtime over the shared memory
   csharp/    seqlock read/write, triple-buffer, mapping open
   java/      seqlock read/write, triple-buffer, mapping open
   cpp/       (added only when a native host exists — NOT created yet)
```

Rules:

- Only the **layout and memory primitives** are generic. Even coordinate mapping is per-host
  (axis handedness, units), so it lives in the adapter, not the protocol.
- Game logic, player access, camera/render/present/input hooks always live in a **host adapter**.
- **No** adapter plugin system, capability registry, DI container, or large interface hierarchy
  until a second host actually exists.

---

## 3. Process / role model

```text
HowToFish.exe (Unity 6 / Mono / BepInEx)         javaw.exe (Minecraft 1.21.1 / Fabric)
        host adapter                                     minecraft adapter
             └────────── file-backed shared memory ─────────┘
```

- **Minecraft side (`minecraft/`)** is mostly game-independent: it produces frames (and later
  state) and consumes host state/input.
- **Host side** (its own sibling repository, e.g. `HowToFishMC`) is fully game-specific.

### Roles and player/camera ownership

Do **not** hard-code ownership into the core. Both modes must remain possible:

```text
Mode A: Minecraft owns player/camera; host camera is overwritten
Mode B: Host owns player/camera; Minecraft mirrors the host
```

For **Phase 1** the How to Fish adapter uses a plain "host displays Minecraft's frame" setup
(no camera sync at all). The existing prototype's direction (`How to Fish -> Minecraft state`) is
kept for now; switching to "Minecraft drives the host camera" is a Phase 2 decision.

---

## 4. Threading constraints (hard rules)

1. **Game APIs may only be called on the game's own main/render thread.**
   Examples: `Player.LocalPlayer`, Unity `Camera`/`Texture2D`, any game object access. This is
   never allowed from a shared-memory reader thread.

2. **Cross-thread exchange is pure memory.** Shared memory, seqlocks, rings and the triple
   buffer only read/write memory; they never touch game objects.

Consequences:
- The host reads `McState`/frame buffers on whatever thread, then must marshal any **game-side**
  work (texture upload, overlay draw) onto the game main thread.
- The Minecraft mod does its frame readback and publishing on the client/render thread; the
  protocol writes are plain memory.

---

## 5. Repository layout

One repository holds the **game-independent framework**; each host adapter is a **separate sibling
repository** that depends on it.

```text
CrossMC/                         framework repository
├─ protocol/bridge_protocol.h
├─ bindings/{java,csharp}/
├─ minecraft/
├─ docs/{ARCHITECTURE,PORTING,ROADMAP,VERIFICATION}.md
├─ LICENSE
└─ README.md

HowToFishMC/                     first host adapter repository (sibling)
└─ src/ + host.properties + CrossMC.HowToFish.csproj  (references ../CrossMC/bindings/csharp)
```

Future hosts get their own sibling repositories (`EldenRingMC`, `SkyrimMC`, ...). CrossMC itself
stays game-agnostic and owns the protocol, bindings and Minecraft mod.

---

## 6. First vertical slice — MC Frame → SHM → Host Overlay

```text
Minecraft (Fabric)
  1. render the client main framebuffer (plain; no camera sync in Phase 1)
  2. CPU readback -> BGRA bytes
  3. write into the next free triple-buffer slot
  4. publish the slot (atomic swap)

  ───── file-backed shared memory (path from config/crossmc.properties) ─────

How to Fish (BepInEx)   [on the Unity main thread]
  5. if a fresh overlay slot is available, swap it in
  6. upload BGRA pixels to a Unity texture
  7. draw it as a rectangle on the camera (no depth, no transparency yet)
```

Success: **a live rectangle in How to Fish showing Minecraft's picture, updating.**

Implementation status (Phase 1): the Minecraft producer is implemented in
`minecraft/FrameExporter` — Fabric `WorldRenderEvents.END` on the render thread,
`glReadPixels(GL_BGRA, GL_UNSIGNED_BYTE)` into a reused direct buffer, published via the binding.
The world image only (the hook is before the hand/GUI), rows **bottom-up** (flagged), throttled by
`-Dcrossmc.exportFps`. The host consumer is not started.

Explicitly **out of scope** for Phase 1: depth occlusion, transparency, World/Hand/GUI layers,
camera sync, input, terrain, entities, combat, GPU sharing, frame lockstep.

---

## 7. Fixed decisions for v1 (Phase 0/1)

| Area | Decision |
|---|---|
| Platform | Windows only (no macOS/Linux/CrossOver) |
| Minecraft | 1.21.1 + Fabric (Loader 0.19.5, Fabric API 0.116.17+1.21.1) |
| Host | How to Fish only (Unity 6 / Mono / BepInEx 5) |
| Transport | File-backed shared memory, path from `config/crossmc.properties` `mapping.path` (default `%LOCALAPPDATA%\CrossMC\bridge_v3.bin`) |
| Frame transfer | CPU readback (no GPU interop yet) |
| Frame buffering | Triple buffer |
| State sync | Seqlock latest-value slots |
| Coordinates | Protocol carries Minecraft space (blocks, Y-up, Z south) unless noted |
| Units | `unitsPerBlock` travels in state; How to Fish ~ 1.0 |
| Depth / Input | Not in v1 |

---

## 8. Synchronisation primitives (our own encoding)

- **Seqlock** for small latest-value structs: a `seq` counter is odd during a write and even after;
  readers retry while odd or changed. Single writer per slot. Implemented in `bindings/java` for
  `HostState`/`McState` (`writeHostState`/`readHostState`, `writeMcState`/`readMcState`).
- **Triple buffer** for frames: one 32-bit atomic `state` word encodes the "middle" slot plus a
  `fresh` bit; the writer fills a private back slab then atomically swaps `state`; the reader
  swaps only when `fresh` is set. Lock-free; never blocks; never tears.

Exact encoding is in `protocol/bridge_protocol.h`.

---

## 9. Coordinates & units

- The protocol always carries **Minecraft space** coordinates; the host adapter converts to/from
  its own space (handedness + scale).
- How to Fish (Unity, left-handed) prototype mapping: `(x, y, z) -> (-x, y, z)`, scale 1:1
  (to be re-verified once camera ownership is decided).

---

## 10. Non-goals (Phase 1)

GPU texture sharing, depth compositing, input forwarding, terrain, entities, combat, frame
lockstep, cross-platform.

---

## 11. References, licence & attribution

Conceptual references (architecture only, **no code copied**):

- **SkyCraft** — https://github.com/chasmlol/SkyCraft — shared-memory protocol, seqlock latest
  values, overlay triple buffer, World/Hand/GUI layering, depth-tested compositing.
- **minecraft-crossover-bridge** — https://github.com/justbustin/minecraft-crossover-bridge —
  multi-host layout, the porting checklist, file-backed shared frames, CPU readback.

Policy:

- CrossMC is an **independent implementation** with its own `CrossMC_v1` protocol.
- Reference the above as inspiration in the README.
- Preserve any copyright/licence notices the reference projects require; do not copy code from
  licences incompatible with MIT.
- If an upstream licence turns out to impose extra terms, add `NOTICE`/attribution later.

---

## 12. Cross-space mapping (protocol v3)

Roles for this phase: **Minecraft is the logical/rules side; the host game is the world/presentation
side.** Minecraft's player is the primary player; host entities map to Minecraft proxy entities;
Minecraft's own collision/raycast/placement/damage rules keep doing the work.

### 12.1 Host colliders → Minecraft collision proxies

```text
host Collider  →  ColliderTable (seqlock, MC space)  →  Minecraft voxelisation
              →  World.getBlockState(cell) = invisible barrier  →  native collision / raycast / placement
```

- The host publishes AABBs (`Collider`, stable `id`, Box/Sphere/Capsule types) in **Minecraft
  space**; the adapter converts from its own space (`transform.origin/scale/flip`).
- Minecraft voxelises them into a cell set and returns a solid, invisible block state from
  `World.getBlockState` for **client** cells that are actually air. No Minecraft rule is
  re-implemented, and existing blocks are never hidden.
- Consequence: client-side movement is constrained by host space using native code; the server does
  not know the proxies, so server-authoritative correction is a later concern.

### 12.2 Entity mapping

```text
host entity (FishNet NetworkObject.ObjectId)  ↔  EntityTable  ↔  Minecraft proxy entity
```

- Each host creature/boss is a hidden armor-stand proxy on the integrated server, tagged
  `crossmc_proxy` + `crossmc_id_<hostEntityId>`.
- `hostEntityId` is the stable key both directions. (`mcEntityId` is reserved for a future
  write-back; resolution currently uses the entity tag.)

### 12.3 Damage events (Minecraft → host)

```text
MC native damage (melee / projectile / explosion / fall / fire / modded)
        ↓  ServerLivingEntityEvents.AFTER_DAMAGE (only proxy entities)
DamageRing (SPSC)  →  host adapter  →  multiplier from the host repo config  →  host entity effect
```

- Minecraft forwards the **real** damage event (amount, source kind, attacker, position), not an
  explosion coordinate.
- Multipliers and how a host entity reacts live in the host adapter repository's configuration
  (e.g. `HowToFishMC/host.properties`), never in `protocol/`.
- Reverse direction (Minecraft blocks constraining host entities) is reserved as `BlockEditRing`.

### 12.4 Status

Implemented and building: protocol v3, Java + C# bindings (with tests for Java), the Minecraft
collision/entity/damage plumbing, and the How to Fish adapter. **Not yet verified in-game**; see
`docs/ROADMAP.md`.
