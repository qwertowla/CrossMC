/* bridge_protocol.h — CrossMC shared-memory protocol (draft v2)
 *
 * This header is the SINGLE SOURCE OF TRUTH for the byte layout. The C# binding
 * (bindings/csharp) and the Java binding (bindings/java) mirror it. If anything here
 * changes, change both mirrors and bump CROSSMC_VERSION.
 *
 * It is C-compatible: plain C99/C11 constructs only, so it can be included from C, C++
 * (and thus C# via P/Invoke-style structs) without a translation layer.
 *
 * Design notes
 * ------------
 * * SHARED MEMORY IS FILE-BACKED. The Java (Minecraft) side cannot open Win32 named
 *   sections (Local\...); it can only map a file. So both processes map the same file
 *   (default %LOCALAPPDATA%\CrossMC\bridge_v2.bin, configurable through
 *   config/crossmc.properties -> mapping.path). This is real cross-process shared memory
 *   (the OS page cache backs both mappings); it is just addressed by path.
 * * All multi-byte values are little-endian. Fixed-size POD structs only; no
 *   serialisation library on the hot path. Natural alignment; sizes are asserted.
 * * Coordinates in this protocol are in MINECRAFT SPACE (blocks, +Y up, +Z south)
 *   unless a field documents otherwise. The host adapter converts to/from its space.
 * * Two sync primitives, our own encoding:
 *     - seqlock      : small latest-value structs (single writer), used by HostState/McState
 *     - triple buffer: frames (lock-free, never blocks, never tears)
 *
 * Scope of v1: single host (How to Fish) and a frame-only vertical slice. The state, input
 * and depth layouts exist but are not exercised by the Phase 1 frame path.
 */

#ifndef CROSSMC_BRIDGE_PROTOCOL_H
#define CROSSMC_BRIDGE_PROTOCOL_H

#include <stdint.h>

#if defined(__cplusplus)
#  define CROSSMC_EXTERN_C_BEGIN           extern "C" {
#  define CROSSMC_EXTERN_C_END             }
#  define CROSSMC_STATIC_ASSERT(cond, msg) static_assert(cond, msg)
#else
#  define CROSSMC_EXTERN_C_BEGIN
#  define CROSSMC_EXTERN_C_END
#  define CROSSMC_STATIC_ASSERT(cond, msg) _Static_assert(cond, msg)
#endif

CROSSMC_EXTERN_C_BEGIN

/* ---- identity ---------------------------------------------------------------------- */
/* "CMCB" (CrossMC Bridge), little-endian bytes 'C','M','C','B'. */
#define CROSSMC_MAGIC   0x42434D43u
#define CROSSMC_VERSION 2u
/* Default file-backed mapping (relative to %LOCALAPPDATA%). Overridden by
 * config/crossmc.properties -> mapping.path; both processes must resolve the same file. */
#define CROSSMC_MAPPING_SUBPATH L"CrossMC\\bridge_v2.bin"

/* ---- frame geometry (v1 hard cap; pages are committed lazily by the OS) ------------- */
#define CROSSMC_MAX_FRAME_W     3840u
#define CROSSMC_MAX_FRAME_H     2160u
#define CROSSMC_BYTES_PER_PIXEL 4u   /* BGRA8 */
#define CROSSMC_FRAME_SLOT_BYTES \
	((uint64_t)CROSSMC_MAX_FRAME_W * CROSSMC_MAX_FRAME_H * CROSSMC_BYTES_PER_PIXEL)

/* ---- region offsets (bytes) -------------------------------------------------------- */
#define CROSSMC_OFF_HEADER        0x0000   /* Header            */
#define CROSSMC_OFF_HOST_STATE    0x0100   /* HostState  (host -> MC, seqlock)  */
#define CROSSMC_OFF_MC_STATE      0x0200   /* McState    (MC  -> host, seqlock) */
#define CROSSMC_OFF_OVERLAY_CTL   0x0300   /* OverlayControl (triple-buffer state) */
#define CROSSMC_OFF_OVERLAY_SLOTS 0x0340   /* OverlayFrameSlot[3] (3 * 0x40) */
#define CROSSMC_OFF_DEPTH_FRAME   0x0400   /* DepthFrame (reserved, Phase 4) */
#define CROSSMC_OFF_INPUT_RING    0x1000   /* InputRing (reserved, Phase 3)  */
#define CROSSMC_OFF_INPUT_EVENTS  0x1040   /* InputEvent[]                   */
#define CROSSMC_OFF_FRAMES        0x100000 /* 1 MiB align; 3 pixel slabs     */
#define CROSSMC_MAPPING_BYTES \
	(CROSSMC_OFF_FRAMES + CROSSMC_FRAME_SLOT_BYTES * 3)

#define CROSSMC_HEARTBEAT_TIMEOUT_MS 2000u

/* ====================================================================================
 * Header @0x0000
 * ==================================================================================== */
typedef struct crossmc_header
{
	uint32_t magic;              /* CROSSMC_MAGIC */
	uint32_t version;            /* CROSSMC_VERSION */
	uint32_t headerSize;         /* sizeof(Header) — runtime layout check */
	uint32_t mappingBytes;       /* total mapping size reserved */
	uint32_t flags;              /* reserved, 0 */
	uint32_t hostPid;            /* host process id */
	uint32_t mcPid;              /* Minecraft process id */
	uint32_t hostStateSize;      /* sizeof(HostState) */
	uint32_t mcStateSize;        /* sizeof(McState) */
	uint32_t overlaySlotSize;    /* sizeof(OverlayFrameSlot) */
	uint32_t inputRingSize;      /* sizeof(InputRing) + capacity * sizeof(InputEvent) */
	uint32_t reserved0;
	/* Heartbeats are Unix-epoch milliseconds (a clock both processes share), NOT
	 * GetTickCount64() (per-boot origin, not comparable across PIDs). */
	uint64_t hostHeartbeatMs;
	uint64_t mcHeartbeatMs;
	uint64_t sequence;           /* global monotonic publish counter */
	uint64_t timestampMs;        /* last header update, epoch ms */
} Header;
CROSSMC_STATIC_ASSERT(sizeof(Header) == 0x50, "Header size");

/* ====================================================================================
 * HostState @0x0100 — host -> Minecraft. seqlock: seq is odd while writing.
 * ==================================================================================== */
#define CROSSMC_HOST_IN_GAME   (1u << 0)
#define CROSSMC_HOST_MENU_OPEN (1u << 1)
#define CROSSMC_HOST_LOADING   (1u << 2)

typedef struct crossmc_host_state
{
	uint32_t seq;                /* seqlock counter */
	uint32_t flags;              /* CROSSMC_HOST_* */
	uint32_t worldId;            /* opaque host world/scene id */
	uint32_t collisionEpoch;     /* bumps on world change */
	uint64_t timestampMs;        /* epoch ms of this sample */
	double   posX, posY, posZ;   /* host player feet, MC space */
	float    yaw, pitch, roll;   /* authoritative look (MC degrees) */
	float    eyeHeight;          /* eye above feet, blocks */
	float    unitsPerBlock;      /* host units per Minecraft block (How to Fish ~ 1.0) */
	uint32_t teleportSeq;        /* bumps on an authoritative teleport */
	uint32_t cameraMode;         /* 0 first person, 1 behind, 2 front */
	uint32_t viewportW;
	uint32_t viewportH;
	uint32_t reserved0;
} HostState;
CROSSMC_STATIC_ASSERT(sizeof(HostState) <= 0x100, "HostState must fit its region");

/* ====================================================================================
 * McState @0x0200 — Minecraft -> host. seqlock: seq is odd while writing.
 * ==================================================================================== */
#define CROSSMC_MC_IN_WORLD    (1u << 0)
#define CROSSMC_MC_SCREEN_OPEN (1u << 1)
#define CROSSMC_MC_ON_GROUND   (1u << 2)
#define CROSSMC_MC_SNEAKING    (1u << 3)
#define CROSSMC_MC_SPRINTING   (1u << 4)
#define CROSSMC_MC_DEAD        (1u << 5)
#define CROSSMC_MC_SWIMMING    (1u << 6)
#define CROSSMC_MC_FLYING      (1u << 7)

typedef struct crossmc_mc_state
{
	uint32_t seq;
	uint32_t flags;              /* CROSSMC_MC_* */
	uint64_t timestampMs;        /* epoch ms of this sample */
	double   x, y, z;            /* interpolated feet position (MC space) */
	double   prevX, prevY, prevZ;/* feet position at the previous tick */
	double   curX,  curY,  curZ; /* feet position at the current tick */
	float    yaw, pitch;         /* MC rotation (degrees) */
	float    eyeHeight;          /* blocks above feet */
	float    fovDeg;             /* effective vertical FOV */
	float    tickMs;             /* ms per tick (50 unless the tick rate changed) */
	uint32_t cameraMode;         /* 0 first person, 1 behind, 2 front */
	float    cameraDistance;
	uint64_t frameCounter;       /* frames rendered since start */
	int64_t  tickQpc;            /* QueryPerformanceCounter at the tick */
	uint32_t reserved0;
} McState;
CROSSMC_STATIC_ASSERT(sizeof(McState) <= 0x100, "McState must fit its region");

/* ====================================================================================
 * OverlayControl @0x0300 — lock-free triple buffer control.
 *
 * state word encoding (single 32-bit atomic):
 *   bits 0..1  : index of the "middle" slot (neither the writer's private back slot
 *                nor the reader's private front slot)
 *   bit  2     : CROSSMC_OVERLAY_FRESH — the middle slot holds a frame not yet consumed
 *   bits 3..31 : reserved, 0
 *
 * Writer (Minecraft) owns "back"; reader (host) owns "front". Each keeps its own index
 * privately and touches the shared word only through one atomic exchange:
 *   publish: state = exchange(state, back | CROSSMC_OVERLAY_FRESH);
 *            back  = old & CROSSMC_OVERLAY_INDEX_MASK
 *   acquire: if (state & CROSSMC_OVERLAY_FRESH) { old = exchange(state, front);
 *              front = old & CROSSMC_OVERLAY_INDEX_MASK; }
 * ==================================================================================== */
#define CROSSMC_OVERLAY_FRESH      (1u << 2)
#define CROSSMC_OVERLAY_INDEX_MASK 0x3u

typedef struct crossmc_overlay_control
{
	uint32_t state;              /* see encoding above */
	uint32_t reserved0;
	uint64_t framesPublished;    /* monotonic count of published frames */
	uint64_t sequence;           /* frameId of the most recently published frame */
	uint64_t timestampMs;        /* epoch ms of the most recently published frame */
} OverlayControl;
CROSSMC_STATIC_ASSERT(sizeof(OverlayControl) == 0x20, "OverlayControl size");

/* ---- per-slot frame metadata ------------------------------------------------------- */
#define CROSSMC_FORMAT_BGRA8 1u      /* 4 bytes/pixel, B,G,R,A */

#define CROSSMC_OVERLAY_BOTTOM_UP (1u << 0) /* rows are bottom-up; D3D readback is top-down */

typedef struct crossmc_overlay_frame_slot
{
	uint32_t width;
	uint32_t height;
	uint32_t strideBytes;        /* bytes per row */
	uint32_t format;             /* CROSSMC_FORMAT_* */
	uint32_t flags;              /* CROSSMC_OVERLAY_* */
	uint32_t bufferIndex;        /* 0..2 — which slab this header describes */
	uint64_t frameId;            /* writer frame counter for this slot */
	uint64_t sequence;           /* publication sequence */
	uint64_t timestampMs;        /* epoch ms when filled */
	uint8_t  reserved[0x40 - 0x30];
} OverlayFrameSlot;
CROSSMC_STATIC_ASSERT(sizeof(OverlayFrameSlot) == 0x40, "OverlayFrameSlot size");

/* Pixel slab i (0..2) at:  CROSSMC_OFF_FRAMES + i * CROSSMC_FRAME_SLOT_BYTES
 * Its header at:           CROSSMC_OFF_OVERLAY_SLOTS + i * sizeof(OverlayFrameSlot) */
static inline uint64_t crossmc_overlay_slab(uint32_t i)
{
	return CROSSMC_OFF_FRAMES + (uint64_t)i * CROSSMC_FRAME_SLOT_BYTES;
}

static inline uint64_t crossmc_overlay_slot(uint32_t i)
{
	return CROSSMC_OFF_OVERLAY_SLOTS + (uint64_t)i * sizeof(OverlayFrameSlot);
}

/* ====================================================================================
 * InputRing @0x1000 — RESERVED (Phase 3). SPSC ring (host produces, MC consumes).
 * InputRing header at 0x1000; InputEvent entries at 0x1040.
 * ==================================================================================== */
#define CROSSMC_INPUT_RING_ENTRIES 2048u

typedef struct crossmc_input_event
{
	uint32_t type;               /* key / mouse button / scroll / cursor / text / release-all */
	uint32_t code;
	int32_t  a;
	int32_t  b;
	uint64_t timestampMs;
} InputEvent;
CROSSMC_STATIC_ASSERT(sizeof(InputEvent) == 0x18, "InputEvent size");

typedef struct crossmc_input_ring
{
	uint32_t head;               /* write index (host) */
	uint32_t tail;               /* read index (MC) */
	uint32_t capacity;           /* CROSSMC_INPUT_RING_ENTRIES */
	uint32_t reserved0;
} InputRing;
CROSSMC_STATIC_ASSERT(sizeof(InputRing) == 0x10, "InputRing size");

static inline uint64_t crossmc_input_event_at(uint32_t i)
{
	return CROSSMC_OFF_INPUT_EVENTS + (uint64_t)i * sizeof(InputEvent);
}

/* ====================================================================================
 * DepthFrame @0x0400 — RESERVED (Phase 4). Metadata only; no depth slabs allocated in v1.
 * ==================================================================================== */
#define CROSSMC_DEPTH_F32 1u
#define CROSSMC_DEPTH_U16 2u

typedef struct crossmc_depth_frame
{
	uint32_t width;
	uint32_t height;
	uint32_t strideBytes;
	uint32_t format;             /* CROSSMC_DEPTH_* */
	uint32_t bufferIndex;
	uint32_t reserved0;
	uint64_t frameId;
	uint64_t sequence;
	uint64_t timestampMs;
} DepthFrame;
CROSSMC_STATIC_ASSERT(sizeof(DepthFrame) == 0x30, "DepthFrame size");

CROSSMC_EXTERN_C_END

#endif /* CROSSMC_BRIDGE_PROTOCOL_H */
