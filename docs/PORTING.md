# PORTING — the HostAdapter contract

> Status: **draft v0.1 (Phase 0) — documentation only.**
> This defines what a host-game adapter *is*. It is not a code framework. Do **not** create
> `IHostAdapter`, `IHostRegistry`, `CapabilityRegistry`, a DI container, or a plugin manager yet —
> there is only one host, and premature abstraction is explicitly out of scope.

---

## 1. What a HostAdapter is

The adapter is the game-specific half of the bridge. It knows how to talk to one specific game.

```text
protocol/ + bindings/    game-independent
minecraft/               mostly game-independent
hosts/<Game>/            fully game-specific adapter
```

---

## 2. Two flavours

| Flavour | Games | Language | Injection |
|---|---|---|---|
| Managed | Unity + Mono + BepInEx (How to Fish) | C# | BepInEx loads the plugin; Harmony patches the game |
| Native | SKSE / RE Engine / MT Framework etc. | C++ | loader proxy + MinHook / inline hooks + RE |

How to Fish is the **managed** flavour and the easier case: no DLL proxying or address signature
scanning — the game exposes managed types and Harmony can patch render/input entry points.

There is **no single shared interface** across flavours. The list in §4 is a specification, not a
shared vtable. Each flavour has its own binding runtime.

---

## 3. Capabilities (conceptual)

A host declares what it can do; consumers degrade gracefully. Documented now, **not** a code
registry.

```text
hasDepth              // can supply a depth buffer for occlusion
canCompositeInScene   // can draw the overlay inside its own scene (before UI)
canHidePlayer         // can hide the host's own player model (for a stand-in)
ownsPlayer            // host owns the player; MC mirrors it (else MC owns, host is overwritten)
inputMode             // { none | hostToMc | bidirectional | keySwitch }
coordinateHandedness  // per-axis sign mapping to MC space
unitsPerBlock         // host units per MC block
```

Phase 1 needs only a plain on-top rectangle: `canCompositeInScene = false`, everything else none.

---

## 4. Contract methods (specification + thread)

Hard rule: methods tagged `[game]` may only run on the game's own main/render thread. Pure
shared-memory reads/writes may run on any thread.

```text
capabilities()                     [any]    -> flags + units + handedness
onFrameBegin(frameId)              [game]   -> once per host frame; the only safe place to touch the game
getPlayerTransform()               [game]   -> feet pos/rot in host space
getCamera()                        [game]   -> view matrix, projection, FOV, partial-tick alpha
setCamera(mcPose)                  [game]   -> overwrite host camera (only when MC owns)
setPlayerTransform(mcPose)         [game]   -> move host stand-in (only when canHidePlayer)
submitOverlay(color, hand, gui)    [game]   -> write layer(s) into the triple buffer (Phase 4)
submitDepth(depth)                 [game]   -> write depth (Phase 4)
handleInput(ring)                  [game]   -> produce input events for MC (Phase 3)
onFrameEnd(frameId)                [game]   -> flush, publish, heartbeat
```

---

## 5. How to Fish specifics (managed / BepInEx)

Candidate hook points (to be verified against the current build in Phase 1/2):

- **Per-frame hook** — BepInEx plugin `Update()`/`LateUpdate()`, or a Harmony patch on the main
  loop. This is the `onFrameBegin`/`onFrameEnd` anchor.
- **Camera** — the verified prototype access: `Player.LocalPlayer`, `player.Transform.position`,
  `player.CamObject.eulerAngles` (X=pitch, Y=yaw, Z=roll). Note `player.Transform.eulerAngles` is
  always 0; the real view is on `CamObject`.
- **Overlay compositing** — How to Fish is **URP**. Candidate paths, preferred first:
  1. `RenderPipelineManager.endCameraRendering` + `CommandBuffer` drawing a quad;
  2. a dedicated overlay `Camera` / `RenderTexture` blit;
  3. a UGUI raw-image layer.
  The choice is an adapter detail and must be verified before Phase 1.
- **Depth** (Phase 4) — read the URP depth attachment; mechanism TBD.
- **Input** (Phase 3) — Unity Input System vs legacy; decide at Phase 3.

Coordinate mapping for this host: left-handed, prototype `(x,y,z) -> (-x, y, z)`, scale 1:1
(pending the Phase 2 ownership decision).

---

## 6. Checklist to port a NEW host

Derived from the reference projects. For a managed/Unity host most rows are cheap.

| Need | Purpose | Unity/BepInEx |
|---|---|---|
| Per-frame hook on the game thread | safe place to talk to the game | easy (plugin Update) |
| Camera (pos/rot/FOV) + override point | drive the view | easy (managed) |
| Collision query / raycast | terrain | easy (`Physics.Raycast`) if needed |
| Character list + hitboxes/health | entities | game-dependent |
| Player object + hide/keep-alive | stand-in | game-dependent |
| Final frame + depth attachment + present point | compositing/occlusion | medium (URP) |

---

## 7. Non-goals for this document (Phase 0)

No plugin discovery, no cross-language adapter ABI, no capability registry in code.
`hosts/HowToFish/` may look adapter-specific for a long time — that is correct.
