# ROADMAP

> Guiding rule: **generalise only when a second host exists and we catch ourselves duplicating
> the first adapter.** Do not build a large framework for hypothetical hosts.

```text
Phase 0  Repository structure + protocol & design docs        (done)
Phase 1  Minecraft Frame -> Shared Memory -> How to Fish Overlay   (MC producer done; host pending)
Phase 2  Player / camera sync
Phase 3  Input
Phase 4  Depth
Phase 5  World / entity interaction
Phase 6  Second host validation
Phase 7  Extract genuinely shared code, based on the second host's real needs
```

---

## Phase 0 — Project structure & protocol docs
- Create the CrossMC monorepo skeleton.
- Define `protocol/bridge_protocol.h` (layout draft only).
- Write `docs/ARCHITECTURE.md`, `docs/PORTING.md`, this `docs/ROADMAP.md`.
- No runtime code.

## Phase 1 — Minimal vertical slice
```text
Minecraft Fabric
    ↓ FrameExporter                      [DONE — minecraft/]
Shared Memory (file-backed, triple buffer)
    ↓
How to Fish BepInEx                      [PENDING — hosts/HowToFish]
    ↓ Unity texture / overlay
```
Success: a live rectangle in How to Fish showing Minecraft's rendered frame.
Validates: protocol, shared memory, triple buffer, frame transport, host composition.
**Not** in this phase: player sync, input, depth, terrain, entities, combat, full camera sync.

Progress (protocol v3):
- **Protocol** (`protocol/bridge_protocol.h`) — C-compatible (gcc/g++ verified): Header size/seq/ts,
  HostState/McState seqlocks, overlay triple buffer, `ColliderTable`, `EntityTable`, `DamageRing`,
  reserved `InputRing`/`DepthFrame`/`BlockEditRing`.
- **Java binding** (`bindings/java`) — `ShmSelfTest` PASS: mapping, triple buffer, HostState/McState
  seqlocks, collider/entity tables, damage ring. Standalone Gradle build.
- **C# binding** (`bindings/csharp`) — mirrors the Java binding; builds with `dotnet build`.
- **Minecraft** (`minecraft/`) — builds: frame producer (BGRA8 bottom-up, throttled, config-driven
  path bundled in the jar), `McState` publisher, host-collision proxies via
  `World#getBlockState` mixin, hidden armor-stand entity proxies on the integrated server, and
  native damage capture (`ServerLivingEntityEvents.AFTER_DAMAGE`).
- **Host adapter** (`hosts/HowToFish`) — builds: frame overlay, HostState/collider/entity export,
  damage consumption with `host.properties` multipliers.
- **Not verified in-game yet**: frame content/orientation, collision proxy movement, proxy
  entity binding, damage application (especially `Creature.LocalHit`). See the adapter README.

## Phase 2 — Player / camera sync
Decide player/camera ownership for this host, wire transform/camera exchange.

## Phase 3 — Input
Forward input from the host to Minecraft (and the arbitration/routing rules).

## Phase 4 — Depth
Supply a depth buffer and do per-pixel occlusion (World / Hand / GUI layering).

## Phase 5 — World / entity interaction
Terrain (ray -> invisible blocks), entities, damage.

## Phase 6 — Second host validation
Port a second host to prove the boundaries. This is the trigger for any generalisation.

## Phase 7 — Extract shared code
Only now, and only as far as the second host actually requires: move what is genuinely common
into `protocol/` + `bindings/` and a documented host-adapter contract.
