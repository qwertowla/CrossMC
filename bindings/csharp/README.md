# bindings/csharp

Thin C# runtime over the CrossMC shared memory, used by C# host adapters (first: How to Fish,
under `hosts/HowToFish/` on the `HowToFishMC` branch).

**Status: implemented (mirrors `bindings/java`). Builds with `dotnet build` (netstandard2.1).**

## What is here

- `Protocol.cs` — mirror of `protocol/bridge_protocol.h` (v3): magic, version, region offsets,
  field offsets, sizes, kinds/flags. Keep it identical to the C header and the Java binding.
- `Config.cs` — resolves `mapping.path` the same way as Java (`CROSSMC_CONFIG` →
  `./config/crossmc.properties` → `%LOCALAPPDATA%/CrossMC/crossmc.properties` → default), with
  `%VAR%`/`~` expansion.
- `BridgeMemory.cs` — opens the file-backed mapping (`MemoryMappedFile.CreateFromFile`), and
  implements:
  - the lock-free **triple buffer** (`Acquire`, slot accessors, `ReadPixels`); the state word is a
    real atomic via `Interlocked` on a pointer into the mapping;
  - **HostState seqlock write** and **McState seqlock read**;
  - the whole-table seqlocks for the **collider** table (write) and **entity** table (write);
  - the **damage ring** consumer (`PollDamage`).
- `Data.cs` — `HostState`, `McState`, `Collider`, `EntityMap`, `DamageEvent` mirrors.

## What does NOT belong here

- Any How to Fish / Unity / game API access (that is `hosts/HowToFish/` on the `HowToFishMC` branch).
- Texture upload or overlay drawing, damage application rules, multipliers.

## Build

```powershell
dotnet build -c Release
# -> bin/Release/netstandard2.1/CrossMC.Bindings.dll
```

## Threading

Everything in this binding is pure memory access and may run on any thread. Handing data to the
game (texture upload, drawing, damage application) must be marshalled onto the Unity main thread by
the adapter.
