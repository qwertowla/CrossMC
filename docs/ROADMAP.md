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

Progress:
- Java binding (`bindings/java`) — done, `ShmSelfTest` PASS.
- Minecraft frame producer (`minecraft/FrameExporter`) — done: `WorldRenderEvents.END` →
  `glReadPixels` (BGRA8, bottom-up) → `BridgeMemory.publishFrame`. Needs real-client verification
  (image content/orientation, readback cost).
- Host consumer (`bindings/csharp` + the `hosts/HowToFish` BepInEx plugin) — not started.

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
