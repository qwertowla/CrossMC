# hosts/HowToFish

The first CrossMC **host adapter** — game: **How to Fish** (Unity 6, Mono, FishNet, BepInEx 5, C#).

Adapter name: **CrossMC-HowToFish**.

**Status: implemented (Phase 1 + collision/entity/damage plumbing). Compiles with `dotnet build`;
runtime behaviour is not yet verified in-game.**

This adapter is game-specific by design. It may know about Unity, BepInEx, Harmony, `Player`,
`Creature`, `Collider` and `Transform`; the CrossMC protocol and bindings must not.

## What it does

- Opens the shared memory as the frame **consumer** and draws the newest Minecraft frame as a
  screen rectangle (`FrameOverlay`, IMGUI; rows are bottom-up and match Unity's texture origin).
- Publishes the host player/camera as `HostState` (`Player.LocalPlayer`, `player.Transform.position`,
  `player.CamObject.eulerAngles`).
- Publishes host world colliders (`Physics.OverlapSphereNonAlloc` around the player, AABBs to MC
  space) so Minecraft can build collision proxies.
- Publishes host creatures (`Creature`, including the boss) with `NetworkObject.ObjectId` as the
  stable host entity id, position and HP.
- Consumes Minecraft damage events and applies this adapter's configured multipliers
  (`host.properties`) to the mapped host entity or the local player.

## Verified facts from the prototype (carried over as reference)

- `Player.LocalPlayer` is a static field (global namespace).
- Player position: `player.Transform.position`; view: `player.CamObject.eulerAngles`
  (X=pitch, Y=yaw, Z=roll); `player.Transform.eulerAngles` is always 0.
- Coordinate mapping is configurable (`transform.origin*`, `transform.scale`, `transform.flipX`).

## Configuration (`host.properties`, this folder)

Lives with the adapter, **not** in the protocol. Damage multipliers and the world→MC transform:

```properties
transform.scale=1.0
transform.flipX=true
damage.default=1.0
damage.explosion=0.5
damage.projectile=0.8
damage.fall=0.2
```

Lookup order: `%LOCALAPPDATA%/CrossMC/howtofish.properties` → this folder's `host.properties` →
built-in defaults. Copied next to `CrossMC.HowToFish.dll` at install time (see build script).

## Threading (hard rule)

All game access (`Player`, `Creature`, `Physics`, `Texture2D`, drawing) runs on the Unity main
thread. Only the shared-memory reads/writes are thread-agnostic.

## Build

```powershell
dotnet build -c Release
# -> bin/Release/CrossMC.HowToFish.dll  (copy to How to Fish/BepInEx/plugins/)
```

The game folder is set by the `GameDir` MSBuild property (default points at the local Steam
install).

## Not yet verified / known limits

- The **creature damage entry point** (`Creature.LocalHit`) has a long gameplay-specific signature
  that has not been verified against the current build; it is invoked best-effort via reflection and
  failures are logged. Player damage uses the typed `PlayerVitals.TakeDamage(...)`.
- Collider export currently uses `Physics.OverlapSphereNonAlloc` bounds (AABBs); it does not yet
  distinguish Box/Sphere/Capsule precisely, nor preserve rotation.
- The frame overlay is IMGUI (`OnGUI`); the URP `CommandBuffer` path is a later refinement.
- The old UDP prototype, position/view follow and HUD stay in the prototype repos and are not
  migrated here.
