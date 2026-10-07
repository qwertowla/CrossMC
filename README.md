# CrossMC

[中文](README_ZH.md) | **English**

> ⚠️ **This project is unfinished (work in progress).** The protocol layout, the bindings and the
> repository structure are unstable and may change at any time.

**CrossMC** is a general, game-agnostic **cross-process bridge framework** that connects
**Minecraft (Java Edition, Fabric)** with a **standalone host game**. Over shared memory the two
processes exchange rendered frames, state and input: Minecraft acts as the renderer/tool side, and
the host game displays and composites it.

The first host implementation is **CrossMC-HowToFish** (under `hosts/HowToFish/`): it composites
Minecraft's rendered frame into **How to Fish** (Unity 6 / Mono / BepInEx).

**CrossMC** 是一个通用的、与具体游戏无关的**跨进程桥接框架**：把 **Minecraft（Java 版，Fabric）**
与一个**独立的宿主游戏**连接起来。两个进程通过共享内存交换渲染帧、状态与输入：Minecraft 作为渲染 /
工具侧，宿主游戏负责显示与合成。

第一个宿主实现是 **CrossMC-HowToFish**（位于 `hosts/HowToFish/`）：它把 Minecraft 渲染出的画面
合成进 **How to Fish**（Unity 6 / Mono / BepInEx）。

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

One repository. The framework (protocol + bindings + Minecraft mod) is game-independent; each
host adapter lives under `hosts/` and is the only game-specific code.

```text
CrossMC/
├─ protocol/bridge_protocol.h     # single source of truth for the shared-memory layout
├─ bindings/
│  ├─ java/                       # thin runtime used by the Minecraft mod (implemented)
│  └─ csharp/                     # thin runtime used by C# hosts (placeholder)
├─ minecraft/                     # Fabric mod (mostly game-independent)
├─ hosts/
│  └─ HowToFish/                  # first host adapter (game-specific)
├─ docs/
│  ├─ ARCHITECTURE.md
│  ├─ PORTING.md
│  └─ ROADMAP.md
├─ LICENSE
├─ README.md
└─ README_ZH.md
```

New hosts are added as `hosts/<Game>/` (e.g. `hosts/EldenRing/`), **not** as branches and **not**
as separate repositories.

`bindings/cpp/` is intentionally **not created** yet — it will be added only when a native
(non-managed) host game is actually ported.

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
