# bindings/java

Thin Java runtime over the CrossMC shared memory, used by the Minecraft Fabric mod (`minecraft/`).

**Status: implemented and self-tested (`ShmSelfTest: PASS`).**

## What is here

- `dev.crossmc.bridge.Protocol` — mirrors `protocol/bridge_protocol.h`: magic, version, region
  offsets, header/slot field offsets and constants. Keep it identical to the C header and the C#
  binding; bump `VERSION` in all three together.
  `Protocol.mappingPath()` resolves the shared file from `config/crossmc.properties`
  (`mapping.path`), with `-Dcrossmc.config` / `CROSSMC_CONFIG` overrides and a built-in default
  (see the main README "Configuration").
- `dev.crossmc.bridge.BridgeMemory` — opens the file-backed mapping (default
  `%LOCALAPPDATA%\CrossMC\bridge_v3.bin`, from config), reads/writes the header and heartbeats,
  implements the lock-free **triple buffer** and the **HostState/McState seqlocks**:
  - frame writer: `publishFrame(byte[] | ByteBuffer, w, h, flags)` (the `ByteBuffer` overload is
    the single-copy path for `glReadPixels` output);
  - frame reader: `acquire()` / `frontSlot()` + slot header/pixel accessors;
  - state: `readHostState()/writeHostState()`, `readMcState()/writeMcState()`.
- `dev.crossmc.bridge.HostState` / `dev.crossmc.bridge.McState` — Java mirrors of the C structs.
- `dev.crossmc.bridge.ShmSelfTest` — standalone verification with two independent mappings.

## Build / test

Standalone (Java 21, no Minecraft/Loom); uses the wrapper from the `minecraft` project:

```powershell
..\minecraft\gradlew.bat -p . selftest
```

Or manually with any JDK 21:

## What does NOT belong here

- Minecraft game logic, rendering hooks, readback (that is `minecraft/`).
- Any host-game-specific behaviour.

## Run the self-test

```powershell
$j21="$env:APPDATA\.minecraft\runtime\java-runtime-delta"   # a JDK 21 with javac
$proj=(Get-Location).Path                                     # this bindings/java directory
& "$j21\bin\javac.exe" --release 21 -d "$proj\build-selftest" `
  "$proj\src\main\java\dev\crossmc\bridge\Protocol.java" `
  "$proj\src\main\java\dev\crossmc\bridge\BridgeMemory.java" `
  "$proj\src\main\java\dev\crossmc\bridge\HostState.java" `
  "$proj\src\main\java\dev\crossmc\bridge\McState.java" `
  "$proj\src\test\java\dev\crossmc\bridge\ShmSelfTest.java"
& "$j21\bin\java.exe" -cp "$proj\build-selftest" dev.crossmc.bridge.ShmSelfTest
# expected: ShmSelfTest: PASS
```

## Threading

Pure memory access, safe from any thread. Minecraft game APIs are only touched on the client thread
by the mod.
