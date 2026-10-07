// bridge_protocol.h — CrossMC shared-memory protocol (DRAFT v1, Phase 0)
//
// This header is the SINGLE SOURCE OF TRUTH for the byte layout. The C# binding
// (bindings/csharp) and the Java binding (bindings/java) mirror it. If anything here
// changes, change both mirrors and bump kVersion.
//
// Design notes
// ------------
// * SHARED MEMORY IS FILE-BACKED. The Java (Minecraft) side cannot open Win32 named
//   sections (Local\...); it can only map a file. So both processes map the same file
//   under %LOCALAPPDATA% (see kMappingSubPath). This is real cross-process shared
//   memory (the OS page cache backs both mappings); it is just addressed by path.
// * All multi-byte values are little-endian. Fixed-size POD structs only; no
//   serialisation library on the hot path.
// * Coordinates in this protocol are in MINECRAFT SPACE (blocks, +Y up, +Z south)
//   unless a field documents otherwise. The host adapter converts to/from its space.
// * Two sync primitives, our own encoding:
//     - seqlock     : small latest-value structs (single writer)
//     - triple buffer: frames (lock-free, never blocks, never tears)
//
// Scope of v1: single host (How to Fish) and a frame-only vertical slice. The input,
// terrain and entity areas are laid out but UNUSED in v1.
//
// This is a layout draft; it does not implement the IPC API.

#pragma once

#include <cstdint>

namespace crossmc::proto
{
	// ---- identity ----------------------------------------------------------------------
	// "CMCB" (CrossMC Bridge) read as bytes C,M,C,B.
	inline constexpr std::uint32_t kMagic   = 0x42434D43u;  // little-endian of 'C','M','C','B'
	inline constexpr std::uint32_t kVersion = 1;
	// File-backed mapping, relative to %LOCALAPPDATA%. Both sides must resolve it identically.
	inline constexpr wchar_t       kMappingSubPath[] = L"CrossMC\\bridge_v1.bin";

	// ---- frame geometry (v1 hard cap; pages are committed lazily by the OS) -------------
	inline constexpr std::uint32_t kMaxFrameW = 3840;
	inline constexpr std::uint32_t kMaxFrameH = 2160;
	inline constexpr std::uint32_t kBytesPerPixel = 4;   // BGRA8
	inline constexpr std::uint64_t kFrameSlotBytes =
		std::uint64_t(kMaxFrameW) * kMaxFrameH * kBytesPerPixel;

	// ---- region offsets ----------------------------------------------------------------
	inline constexpr std::uint64_t kOffHeader      = 0x0000;   // Header
	inline constexpr std::uint64_t kOffHostState   = 0x0100;   // HostState  (host -> MC, seqlock)
	inline constexpr std::uint64_t kOffMcState     = 0x0200;   // McState    (MC  -> host, seqlock)
	inline constexpr std::uint64_t kOffOverlayCtl  = 0x0300;   // OverlayCtl (triple buffer state)
	inline constexpr std::uint64_t kOffOverlayHdrs = 0x0340;   // OverlaySlotHdr[3] (3 * 0x40)
	inline constexpr std::uint64_t kOffInputRing   = 0x1000;   // reserved (Phase 3)
	inline constexpr std::uint64_t kOffFrames      = 0x100000; // 1 MiB align; 3 pixel slabs
	inline constexpr std::uint64_t kMappingBytes   = kOffFrames + kFrameSlotBytes * 3;

	// ====================================================================================
	// Header @0x0000
	// ====================================================================================
	struct Header
	{
		std::uint32_t magic;             // kMagic
		std::uint32_t version;           // kVersion
		std::uint32_t mappingBytes;      // total size reserved
		std::uint32_t flags;             // reserved, 0
		std::uint32_t hostPid;           // host (How to Fish) process id
		std::uint32_t mcPid;             // Minecraft process id
		// Heartbeats are Unix-epoch milliseconds (a clock both processes share), NOT
		// GetTickCount64() (which has a per-boot origin and is not comparable across PIDs).
		std::uint64_t hostHeartbeatMs;   // System.currentTimeMillis / DateTimeOffset.UtcNow
		std::uint64_t mcHeartbeatMs;
	};
	static_assert(sizeof(Header) == 0x28, "Header size");

	inline constexpr std::uint64_t kHeartbeatTimeoutMs = 2000;  // peer assumed gone beyond this

	// ====================================================================================
	// HostState @0x0100 — host -> Minecraft. seqlock: seq is odd while writing.
	// Reserved for Phase 2 (camera/player). Unused by the Phase 1 frame slice.
	// ====================================================================================
	enum HostFlags : std::uint32_t
	{
		kHostInGame   = 1u << 0,
		kHostMenuOpen = 1u << 1,
		kHostLoading  = 1u << 2,
	};

	struct HostState
	{
		std::uint32_t seq;               // seqlock counter
		std::uint32_t flags;             // HostFlags
		std::uint32_t worldId;           // opaque host world/scene id
		std::uint32_t collisionEpoch;    // bumps on world change
		double        posX, posY, posZ;  // host player feet, MC space
		float         yaw, pitch;        // authoritative look (MC degrees)
		std::uint32_t teleportSeq;
		std::uint32_t viewportW, viewportH;
		float         unitsPerBlock;     // host units per Minecraft block (How to Fish ~ 1.0)
		std::uint32_t pad;
	};
	static_assert(sizeof(HostState) <= 0x100, "HostState must fit its region");

	// ====================================================================================
	// McState @0x0200 — Minecraft -> host. seqlock: seq is odd while writing.
	// ====================================================================================
	enum McFlags : std::uint32_t
	{
		kMcInWorld    = 1u << 0,
		kMcScreenOpen = 1u << 1,
		kMcOnGround   = 1u << 2,
		kMcSneaking   = 1u << 3,
		kMcSprinting  = 1u << 4,
		kMcDead       = 1u << 5,
		kMcSwimming   = 1u << 6,
		kMcFlying     = 1u << 7,
	};

	struct McState
	{
		std::uint32_t seq;
		std::uint32_t flags;             // McFlags
		double        x, y, z;           // interpolated feet position (MC space)
		float         yaw, pitch;        // MC rotation (degrees)
		float         eyeHeight;         // blocks above feet
		std::uint64_t frameCounter;      // frames rendered since start
		float         fovDeg;            // effective vertical FOV
		std::uint32_t cameraMode;        // 0 first person, 1 behind, 2 front
		float         cameraDistance;
		// 20 Hz tick echo so the host can interpolate on its own frame clock without
		// judder from the two games' different frame phases.
		std::int64_t  tickQpc;           // QueryPerformanceCounter at the tick
		double        prevX, prevY, prevZ;
		double        curX,  curY,  curZ;
		float         tickMs;            // ms per tick (50 unless the tick rate changed)
		std::uint32_t pad;
	};
	static_assert(sizeof(McState) <= 0x100, "McState must fit its region");

	// ====================================================================================
	// OverlayCtl @0x0300 — lock-free triple buffer control.
	//
	// state word encoding (single 32-bit atomic):
	//   bits 0..1  : index of the "middle" slot (neither the writer's private back slot
	//                nor the reader's private front slot)
	//   bit  2     : kOverlayFresh — the middle slot holds a frame not yet consumed
	//   bits 3..31 : reserved, 0
	//
	// Writer (Minecraft) owns "back"; reader (host) owns "front". Each keeps its own index
	// privately and touches the shared word only through one atomic exchange:
	//   publish: state = exchange(state, back | kOverlayFresh); back = old & kOverlayIndexMask
	//   acquire: if (state & kOverlayFresh) { old = exchange(state, front);
	//              front = old & kOverlayIndexMask; }   // else keep the current front
	// ====================================================================================
	inline constexpr std::uint32_t kOverlayFresh     = 1u << 2;
	inline constexpr std::uint32_t kOverlayIndexMask = 0x3u;

	struct OverlayCtl
	{
		std::uint32_t state;             // see encoding above
		std::uint32_t pad;
		std::uint64_t framesPublished;   // monotonic count of published frames
	};
	static_assert(sizeof(OverlayCtl) == 0x10, "OverlayCtl size");

	// ---- per-slot frame metadata ---------------------------------- --------------------
	enum OverlayFormat : std::uint32_t
	{
		kFormatBGRA8 = 1,                // 4 bytes/pixel, B,G,R,A
	};

	enum OverlayFlags : std::uint32_t
	{
		kOverlayBottomUp = 1u << 0,      // rows are bottom-up; D3D readback is top-down
	};

	// 0x40-byte header stored just before each slab's pixels.
	struct OverlaySlotHdr
	{
		std::uint32_t width;
		std::uint32_t height;
		std::uint32_t strideBytes;       // bytes per row
		std::uint32_t format;            // OverlayFormat
		std::uint32_t flags;             // OverlayFlags
		std::uint32_t pad0;
		std::uint64_t frameId;           // writer frame counter for this slot
		std::uint64_t timestampMs;       // GetTickCount64() when filled
		std::uint8_t  reserved[0x40 - 0x28];
	};
	static_assert(sizeof(OverlaySlotHdr) == 0x40, "OverlaySlotHdr size");

	// Pixel slab i (0..2) at:  kOffFrames + i * kFrameSlotBytes
	// Its header at:           kOffOverlayHdrs + i * 0x40
	inline constexpr std::uint64_t overlaySlab(std::uint32_t i) { return kOffFrames + std::uint64_t(i) * kFrameSlotBytes; }
	inline constexpr std::uint64_t overlayHdr(std::uint32_t i)  { return kOffOverlayHdrs + std::uint64_t(i) * 0x40; }

	// ====================================================================================
	// InputRing @0x1000 — RESERVED (Phase 3). SPSC ring (host produces, MC consumes) with
	// head/tail counters. Laid out for reference; unused in v1.
	// ====================================================================================
	inline constexpr std::uint32_t kInputRingEntries = 2048;
	struct InputEntry
	{
		std::uint16_t type;              // key / mouse button / scroll / cursor / text / release-all
		std::uint16_t code;
		std::int32_t  a;
		std::int32_t  b;
	};
	// Head @0x1000, tail @0x1040, entries @0x1080.

} // namespace crossmc::proto
