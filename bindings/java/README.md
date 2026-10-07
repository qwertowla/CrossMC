# bindings/java

Thin Java runtime over the CrossMC shared memory, used by the Minecraft Fabric mod (`minecraft/`).

**Status: implemented and self-tested (`ShmSelfTest: PASS`).**

## What is here

- `dev.crossmc.bridge.Protocol` — mirrors `protocol/bridge_protocol.h`: magic, version, region
  offsets, header/slot field offsets and constants. Keep it identical to the C header and the C#
  binding; bump `VERSION` in all three together.
- `dev.crossmc.bridge.BridgeMemory` — opens the file-backed mapping
  `%LOCALAPPDATA%\CrossMC\bridge_v1.bin`, reads/writes the header and heartbeats, and implements the
  lock-free **triple buffer**:
  - writer: `publishFrame(byte[] | ByteBuffer, w, h, flags)` (the `ByteBuffer` overload is the
    single-copy path for `glReadPixels` output);
  - reader: `acquire()` / `frontSlot()` + slot header/pixel accessors.
- `dev.crossmc.bridge.ShmSelfTest` — standalone verification with two independent mappings (no
  Minecraft, no Gradle needed).

## What does NOT belong here

- Minecraft game logic, rendering hooks, readback (that is `minecraft/`).
- Any host-game-specific behaviour.

## Run the self-test

```powershell
$j21='C:\Users\21310\AppData\Roaming\.minecraft\runtime\java-runtime-delta'
$proj='C:\Users\21310\Documents\CrossMC\CrossMC\bindings\java'
& "$j21\bin\javac.exe" --release 21 -d "$proj\build-selftest" `
  "$proj\src\main\java\dev\crossmc\bridge\Protocol.java" `
  "$proj\src\main\java\dev\crossmc\bridge\BridgeMemory.java" `
  "$proj\src\test\java\dev\crossmc\bridge\ShmSelfTest.java"
& "$j21\bin\java.exe" -cp "$proj\build-selftest" dev.crossmc.bridge.ShmSelfTest
# expected: ShmSelfTest: PASS
```

## Threading

Pure memory access, safe from any thread. Minecraft game APIs are only touched on the client thread
by the mod.
