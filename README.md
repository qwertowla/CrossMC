# CrossMC

[中文](README_ZH.md) | **English**

> ⚠️ **This project is unfinished (work in progress).** The protocol layout, the bindings and the
> repository structure are unstable and may change at any time.

**CrossMC** is a general, game-agnostic **cross-process bridge framework** that connects
**Minecraft (Java Edition, Fabric)** with a **standalone host game**. Over shared memory the two
processes exchange rendered frames, state and input: Minecraft acts as the renderer/tool side, and
the host game displays and composites it.

The first host implementation is **HowToFishMC** — a separate sibling repository: it composites
Minecraft's rendered frame into **How to Fish** (Unity 6 / Mono / BepInEx).

**CrossMC is built primarily for Minecraft 1.21.1 + Fabric** — that is the only supported Minecraft
setup for now.

> **Status: Phase 0 complete. Phase 1 Minecraft frame producer implemented; the host consumer is
> next.** No player/camera/input/depth sync yet.

Long-term shape:

```text
                ┌─ HowToFish        (first host adapter, only one for now)
Minecraft ─ Core ─┤─ <future host>
                └─ <future host>
```

Neither game is rewritten. Minecraft runs its own game logic; the host game runs its own
world/renderer. The bridge only **translates** between them.

---

## Layout

One repository for the framework. The framework (protocol + bindings + Minecraft mod) is
game-independent; each host adapter is a **separate repository** (e.g. `HowToFishMC`) and is the
only game-specific code.

```text
CrossMC/
├─ protocol/bridge_protocol.h     # single source of truth for the shared-memory layout
├─ bindings/
│  ├─ java/                       # thin runtime used by the Minecraft mod (implemented)
│  └─ csharp/                     # thin runtime used by C# hosts (placeholder)
├─ minecraft/                     # Fabric mod (mostly game-independent)
├─ config/
│  └─ crossmc.properties          # configuration (shared-memory path etc.)
├─ docs/
│  ├─ ARCHITECTURE.md
│  ├─ PORTING.md
│  ├─ ROADMAP.md
│  └─ VERIFICATION.md
├─ LICENSE
├─ README.md
└─ README_ZH.md
```

Each host adapter is its own sibling repository (e.g. `HowToFishMC`, later `EldenRingMC`), depending
on this one. CrossMC itself owns all the game-independent logic.

`bindings/cpp/` is intentionally **not created** yet — it will be added only when a native
(non-managed) host game is actually ported.

---

## Configuration

`config/crossmc.properties` controls where the shared memory lives (`mapping.path`). Both processes
must resolve the same absolute file. The value supports `%VAR%` placeholders and a leading `~`, e.g.
`%LOCALAPPDATA%/CrossMC/bridge_v3.bin`.

The config file is found in this order (first match wins):

1. `-Dcrossmc.config=<path>` (JVM) or `CROSSMC_CONFIG=<path>` (environment);
2. `./config/crossmc.properties` (relative to the working directory);
3. `%LOCALAPPDATA%/CrossMC/crossmc.properties` (user-level override);
4. the bundled `crossmc.properties` resource (the mod jar ships `config/crossmc.properties`);
5. the built-in default.

So the Minecraft mod always has a working default from its own jar; a user file or env override
wins.

---

## Core = protocol + bindings (not a shared in-process library)

Two processes, different languages, different address spaces. What is truly shared is:

- `protocol/` — the byte layout, magic, version, seqlock, triple buffer, frame format, and the
  common state structs;
- `bindings/` — a thin per-language runtime that opens the mapping and implements the memory
  primitives.

Everything game-specific (player access, camera hook, render/present hook, input hook) lives in a
host adapter, not in the core.

---

## First milestone (Phase 1)

```text
Minecraft Fabric ── FrameExporter ──▶ Shared Memory ──▶ How to Fish (BepInEx) ──▶ Unity Overlay
```

Goal: Minecraft's live frame travels through file-backed shared memory and appears as a live rectangle
inside How to Fish. This single slice validates: protocol, shared memory, triple buffer, frame
transport, host composition.

Fixed for Phase 0/1: **Windows only**, **Minecraft 1.21.1 + Fabric**, **file-backed shared memory**,
**CPU readback**, **triple buffer**, **How to Fish as the only host**.

Progress: the Java binding and the Minecraft frame producer (`minecraft/FrameExporter`) are
implemented and build; the C# host consumer is not started. See `minecraft/README.md` and
`docs/ROADMAP.md`.

---

## Collision, entities and damage (protocol v3)

Beyond the frame path, CrossMC maps the two worlds onto each other while keeping Minecraft as the
logical/rules side:

- **Host colliders → Minecraft collision proxies.** The host publishes collider AABBs; Minecraft
  voxelises them and serves them as invisible solid cells through `World#getBlockState`, so
  Minecraft's **native** collision, raycast and block placement see host space (no rules
  re-implemented, real blocks never hidden).
- **Entities.** Host creatures map to hidden Minecraft proxy entities via a stable host entity id
  (`NetworkObject.ObjectId`).
- **Damage.** Minecraft's native damage events on those proxies (melee, projectile, explosion/TNT,
  fall, fire, modded) are forwarded to the host, which applies its own rules/multipliers. Damage
  multipliers live in the host adapter repository (`HowToFishMC`), never in `protocol/`.

Status: all modules build; in-game behaviour is not yet verified. See `docs/ARCHITECTURE.md` §12 and
`docs/ROADMAP.md`.

---

## References

Architecture is **inspired by** (not copied from):

- **SkyCraft** — https://github.com/chasmlol/SkyCraft
- **minecraft-crossover-bridge** — https://github.com/justbustin/minecraft-crossover-bridge

See `docs/ARCHITECTURE.md` for what is borrowed conceptually and the licence/attribution policy.

---

## Prototype

The earlier experiments live outside this repo and are **kept as prototypes**:

- the `HowToFishMC` prototype (BepInEx plugin + UDP)
- the `HowToFishMC-Fabric` prototype (Fabric mod + HUD)

They are not migrated into CrossMC wholesale; only verified facts (player/camera access,
coordinate mapping) are carried over as reference.

---

## Licence

MIT — see [LICENSE](LICENSE). CrossMC is an independent implementation; see the attribution
policy in `docs/ARCHITECTURE.md`.
