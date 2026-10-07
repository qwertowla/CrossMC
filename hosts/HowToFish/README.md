# hosts/HowToFish

The first CrossMC **host adapter** — game: **How to Fish** (Unity 6, Mono, FishNet, BepInEx 5, C#).

Adapter name: **CrossMC-HowToFish**.

**Status: Phase 1 consumer not started.** The Minecraft frame producer is already implemented
(`minecraft/`); this adapter still needs the C# binding and the overlay plugin.

This adapter is game-specific by design. It may know about Unity, BepInEx, Harmony, `Player`,
`Camera` and `Transform`; the CrossMC protocol and bindings must not.

## Responsibility

- Open the CrossMC shared memory (`%LOCALAPPDATA%\CrossMC\bridge_v1.bin`) as the frame **consumer**.
- On the Unity main thread: upload the received BGRA frame to a Unity texture and draw it as an
  overlay rectangle (Phase 1).
- Later: camera/player access, input, depth, in-scene compositing.

## C# binding

The reusable, game-independent part (mapping open, triple-buffer `acquire`, header/slot accessors)
belongs in `bindings/csharp` and is shared by every future C# host. Only How to Fish-specific code
lives here.

## Verified facts from the prototype (carried over as reference only)

- `Player.LocalPlayer` is a static field (global namespace).
- Player position: `player.Transform.position` (the component's own `transform` stays at origin).
- View rotation: `player.CamObject.eulerAngles` (X=pitch, Y=yaw, Z=roll);
  `player.Transform.eulerAngles` is always 0.
- Prototype axis mapping: `(x, y, z) -> (-x, y, z)`, scale 1:1 (pending the Phase 2 ownership
  decision — do not hard-code it).

## Phase 1 compositing candidates (to verify)

How to Fish is **URP**. In order of preference:
1. `RenderPipelineManager.endCameraRendering` + `CommandBuffer` quad;
2. a dedicated overlay `Camera` / `RenderTexture` blit;
3. a UGUI raw-image layer.

The frame arrives **BGRA8** and **bottom-up** (`OVERLAY_BOTTOM_UP`), so the texture upload must flip
rows (or the shader/UVs must account for it).

## Threading (hard rule)

Game APIs (Unity `Camera`/`Texture2D`, `Player.LocalPlayer`, ...) may only be called on the Unity
main thread. Shared-memory reads may happen anywhere; the texture upload and draw must be on the
main thread.

## Not here

The UDP prototype, the position/view follow logic, and the old HUD stay in the prototype repos
(`HowToFishMC`, `HowToFishMC-Fabric`) and are not migrated here.
