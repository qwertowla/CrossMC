# CrossMC

[中文](README_ZH.md) | **English**

> ⚠️ **Work in progress.** The protocol, bindings and repository structure are unstable and may
> change at any time.

**CrossMC** is a general, game-agnostic **cross-process bridge framework** between **Minecraft
(Java Edition, Fabric)** and a **standalone host game**. The two processes share rendered frames,
state and events over shared memory. Minecraft stays the **logical/rules side** (the player,
blocks, entity rules); the host game is the **world/presentation side**; the bridge only
**translates** between them — neither game is rewritten.

The first host adapter is **HowToFishMC** (a sibling repository): it bridges **How to Fish**
(Unity 6 / Mono / BepInEx). Future games get their own sibling repositories (`EldenRingMC`, ...).

Built primarily for **Minecraft 1.21.1 + Fabric** — the only supported Minecraft setup for now.

```text
                    ┌─ HowToFishMC   (first host adapter — its own repository)
Minecraft ──────────┼─ <future host>  each host is a separate repo depending on CrossMC
  (CrossMC)         └─ <future host>
```

---

## Layout

CrossMC is the **framework** repository. Every host adapter is a **separate sibling repository**
and is the only place game-specific code lives.

```text
CrossMC/
├─ protocol/bridge_protocol.h     # single source of truth for the shared-memory layout (v5)
├─ bindings/
│  ├─ java/                       # thin runtime used by the Minecraft mod
│  └─ csharp/                     # thin runtime used by C# host adapters
├─ minecraft/                     # Fabric client mod (game-independent)
├─ config/
│  └─ crossmc.properties          # configuration (shared-memory path etc.)
├─ tools/TestHost/                # minimal dotnet host to exercise the protocol (no Unity)
├─ docs/
│  ├─ PROTOCOL.md                 # wire semantics: coords, identity, state/event, capabilities
│  ├─ ARCHITECTURE.md
│  ├─ PORTING.md
│  ├─ ROADMAP.md
│  └─ VERIFICATION.md
├─ LICENSE
├─ README.md
└─ README_ZH.md
```

`bindings/cpp/` is intentionally **not created** yet — it will be added only when a native
(non-managed) host game is actually ported.

---

## What the framework provides

- **Protocol** (`protocol/bridge_protocol.h`, v5) — C-compatible byte layout: capability bitmasks,
  heartbeats, `HostState`/`McState` seqlocks, an overlay **triple buffer**, a `ColliderTable`
  (id + revision + lifecycle), an `EntityTable` with a stable **`CrossEntityId`**, a native-damage
  `DamageRing`, an `InputRing`, and reserved `DepthFrame`/`BlockEditRing`. See `docs/PROTOCOL.md`.
- **Bindings** — `bindings/java` (used by the Minecraft mod) and `bindings/csharp` (used by C#
  hosts): file-backed mapping, atomics, seqlocks, triple buffer, table and ring accessors, plus
  capability/liveness helpers. Both mirror the header; bump `VERSION` in all three together.
- **Minecraft mod** — frame producer (`FrameExporter`), `McState` publisher, host-collision proxies
  (voxelised, injected through `World#getBlockState`), hidden proxy entities for host entities, and
  native damage capture — all behind the shared-memory protocol, with no host-specific code.
- **Test host** (`tools/TestHost`) — a minimal .NET console host that exercises shared memory,
  capabilities, sequence, entity lifecycle, state/event and disconnect without Minecraft or Unity.

---

## Configuration

`config/crossmc.properties` controls where the shared memory lives (`mapping.path`). Both processes
must resolve the same absolute file. The value supports `%VAR%` placeholders and a leading `~`, e.g.
`%LOCALAPPDATA%/CrossMC/bridge_v5.bin`.

The config file is found in this order (first match wins):

1. `-Dcrossmc.config=<path>` (JVM) or `CROSSMC_CONFIG=<path>` (environment);
2. `./config/crossmc.properties` (relative to the working directory);
3. `%LOCALAPPDATA%/CrossMC/crossmc.properties` (user-level override);
4. the bundled `crossmc.properties` resource (the mod jar ships `config/crossmc.properties`);
5. the built-in default.

So the Minecraft mod always has a working default from its own jar; a user file or env override wins.

---

## Capabilities and status

| Capability | Protocol | Minecraft side | Host adapter |
|---|---|---|---|
| Frame | ✅ | ✅ producer | ✅ (HowToFishMC overlay) |
| Host frame (host→MC) | ✅ | ✅ consumer → world-space quad | ✅ camera capture (HowToFishMC) |
| State (`HostState`/`McState`) | ✅ | ✅ publishes `McState` | ✅ publishes `HostState` |
| Collision (`ColliderTable`) | ✅ | ✅ proxies via `World#getBlockState` | ✅ exports colliders |
| Entity (`CrossEntityId`) | ✅ | ✅ proxy entities + damage | ✅ allocates ids |
| Damage (`DamageRing`) | ✅ | ✅ captures native damage | ✅ applies multipliers |
| Input (`InputRing`) | ✅ generic | ✅ can inject into `KeyBinding`/`Mouse`¹ | ⛔ off by default |
| Depth / BlockEdit | ✅ reserved | ⛔ | ⛔ |

¹ `InputRing` is a **generic** CrossMC capability, not the player path. If a host uses it,
`HostInputConsumer` injects into Minecraft's **own** input and release/`RELEASE_ALL`/heartbeat-timeout
clears held input. The user plays Minecraft with Minecraft's own input.

**Player authority:** Minecraft is the main game and the Minecraft player is the one authoritative
player. The host **player and camera mirror `McState`** (fixed coordinate mapping; health/hunger too);
the host transform is never written back onto the Minecraft player. `HostState` is host
environment/avatar info only.

All modules build and the Java binding self-test passes; **in-game behaviour is not verified yet**.
See `docs/ROADMAP.md`, `docs/VERIFICATION.md` and `docs/PROTOCOL.md`.

---

## References

Architecture is **inspired by** (not copied from):

- **SkyCraft** — https://github.com/chasmlol/SkyCraft
- **minecraft-crossover-bridge** — https://github.com/justbustin/minecraft-crossover-bridge

See `docs/ARCHITECTURE.md` for what is borrowed conceptually and the licence/attribution policy.

---

## Prototype

The earlier experiments live outside this repo and are **kept as prototypes**:

- the early BepInEx + UDP prototype
- the Fabric + HUD prototype

They are not migrated into CrossMC wholesale; only verified facts (player/camera access,
coordinate mapping) are carried over as reference.

---

## Licence

MIT — see [LICENSE](LICENSE). CrossMC is an independent implementation; see the attribution
policy in `docs/ARCHITECTURE.md`.
