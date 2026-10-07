# bindings/csharp

Thin C# runtime over the CrossMC shared memory, used by C# host adapters (first: How to Fish,
under `hosts/HowToFish/`).

**Status: placeholder (Phase 0). No code yet.**

## What belongs here

- Open/close the file-backed mapping (default `%LOCALAPPDATA%\CrossMC\bridge_v2.bin`, resolved from
  the same `config/crossmc.properties` as the Java side).
- Seqlock read/write helpers for `HostState` / `McState`.
- Triple-buffer reader/writer for overlay frame slots.
- Struct definitions mirrored from `protocol/bridge_protocol.h` (keep sizes identical).

## What does NOT belong here

- Any How to Fish / Unity / game API access (that is `hosts/HowToFish/`).
- Texture upload or overlay drawing (host adapter, on the Unity main thread).

## Threading

Everything in this binding is pure memory access and may run on any thread. Handing data to the
game (texture upload, drawing) must be marshalled onto the Unity main thread by the adapter.
