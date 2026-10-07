# minecraft

The Minecraft side of CrossMC: a **Fabric client mod** (Minecraft 1.21.1, Fabric Loader 0.19.5,
Fabric API 0.116.17+1.21.1). Mostly game-independent across hosts.

**Status: Phase 1 frame path implemented. Player/camera/input/depth not yet.**

## Responsibility

- Produce frames: render target → CPU readback → write into a triple-buffer slot → publish.
- Later: publish `McState` (position/rotation/eye/tick echo), consume `HostState`.
- Later: consume input from the host (Phase 3).

## Implemented

### `CrossMcMinecraftClient`

Opens the file-backed shared memory on client init, creates the header + seeds the triple buffer if
it is the first process, otherwise registers its pid on the existing header. Writes the MC heartbeat.

The mapping path comes from `config/crossmc.properties` (`mapping.path`). The mod jar bundles that
file as `/crossmc.properties`, so it always has a default; a user file at the instance's
`config/crossmc.properties`, or `CROSSMC_CONFIG`, overrides it (see the main README "Configuration").

### `FrameExporter`

Registered on Fabric's `WorldRenderEvents.END`. On the render thread it:

1. binds the main framebuffer's **read** target only (`glBindFramebuffer(GL_READ_FRAMEBUFFER, fbo)`),
   so drawing is untouched;
2. sets `GL_PACK_ALIGNMENT = 1` and reads the colour attachment back as **BGRA8** with
   `glReadPixels` into one reused direct `ByteBuffer`;
3. publishes that buffer through `BridgeMemory.publishFrame(ByteBuffer, ...)` — one copy straight
   into the shared mapping;
4. stamps the MC heartbeat.

Design choices:

- **Hook point = `WorldRenderEvents.END`**: the world image only. It fires before the hand and the
  GUI/HUD are drawn, so the rectangle excludes them. Moving the hook after GUI rendering would
  include the HUD.
- **Rows are bottom-up** (OpenGL origin). Flagged with `Protocol.OVERLAY_BOTTOM_UP` so the host can
  flip as needed.
- **Throttled** to `MAX_EXPORT_FPS` (default 15, override with `-Dcrossmc.exportFps=N`; 0 = every
  frame). `glReadPixels` stalls the pipeline, so this keeps the game playable during validation.
- Readback is full-resolution and CPU-side. GPU interop is out of scope for Phase 1.

## Build

Loom 1.18.2 requires the Gradle JVM to be **JDK ≥ 25**; the mod targets **Java 21**:

```powershell
$env:JAVA_HOME='<path to a JDK 25 installation>'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
Set-Location minecraft
.\gradlew.bat build --no-daemon --console=plain
# -> build\libs\crossmc-fabric-0.1.0.jar
```

## Notes

- Readback and publishing run on the client/render thread; protocol writes are plain memory.
- Struct layouts mirror `protocol/bridge_protocol.h` via `bindings/java`.
- No host-specific code lives here.

## Still to verify on a real client

- That the end-of-world hook yields the expected image (no HUD/hand) and correct orientation.
- Readback cost at the target resolution and the practical `exportFps`.
