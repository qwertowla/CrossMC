/* bridge_protocol.h — CrossMC shared-memory protocol (draft v4)
 *
 * This header is the SINGLE SOURCE OF TRUTH for the byte layout. The C# binding
 * (bindings/csharp) and the Java binding (bindings/java) mirror it. If anything here
 * changes, change both mirrors and bump CROSSMC_VERSION.
 *
 * Data authority (so both sides never claim the same thing):
 *   Minecraft owns  : the MC player, MC blocks, MC entity logic, MC rules.
 *   Host owns       : host entity presentation state, host colliders, host-specific attrs.
 *   CrossMC owns    : the transport only — protocol, CrossEntityId mapping, State/Event
 *                     blocks, capability bits. It never owns gameplay state.
 *
 * It is C-compatible: plain C99/C11 constructs only, so it can be included from C, C++
 * (and thus C# via P/Invoke-style structs) without a translation layer.
 *
 * Design notes
 * ------------
 * * SHARED MEMORY IS FILE-BACKED. The Java (Minecraft) side cannot open Win32 named
 *   sections (Local\...); it can only map a file. So both processes map the same file
 *   (default %LOCALAPPDATA%\CrossMC\bridge_v4.bin, configurable through
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
 * Scope: one host game at a time. Payload maturity:
 *   implemented      : frame (overlay triple buffer), HostState/McState, ColliderTable,
 *                      EntityTable, DamageRing
 *   reserved / basic : InputRing, DepthFrame, BlockEditRing
 * Concrete host adapters (e.g. HowToFishMC) live in separate repositories and never change
 * this layout.
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
#define CROSSMC_VERSION 4u
/* Default file-backed mapping (relative to %LOCALAPPDATA%). Overridden by
 * config/crossmc.properties -> mapping.path; both processes must resolve the same file. */
#define CROSSMC_MAPPING_SUBPATH L"CrossMC\\bridge_v4.bin"

/* ---- capabilities ---------------------------------------------------------------- *
 * A lightweight feature bitmask each peer advertises in the header. A feature is active
 * only when BOTH peers advertise it. No negotiation protocol; just "what I can do". */
#define CROSSMC_CAP_FRAME      (1u << 0)
#define CROSSMC_CAP_STATE      (1u << 1)
#define CROSSMC_CAP_ENTITY     (1u << 2)
#define CROSSMC_CAP_COLLISION  (1u << 3)
#define CROSSMC_CAP_DAMAGE     (1u << 4)
#define CROSSMC_CAP_INPUT      (1u << 5)
#define CROSSMC_CAP_DEPTH      (1u << 6)
#define CROSSMC_CAP_BLOCK_EDIT (1u << 7)
#define CROSSMC_CAP_ALL       (CROSSMC_CAP_FRAME | CROSSMC_CAP_STATE | CROSSMC_CAP_ENTITY \
                             | CROSSMC_CAP_COLLISION | CROSSMC_CAP_DAMAGE | CROSSMC_CAP_INPUT \
                             | CROSSMC_CAP_DEPTH | CROSSMC_CAP_BLOCK_EDIT)

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
#define CROSSMC_OFF_COLLIDERS     0x20000  /* ColliderTable + Collider[]     */
#define CROSSMC_OFF_ENTITIES      0x40000  /* EntityTable + EntityMap[]      */
#define CROSSMC_OFF_DAMAGE        0x60000  /* DamageRing + DamageEvent[]     */
#define CROSSMC_OFF_BLOCK_EDITS   0x80000  /* reverse block sync (reserved)  */
#define CROSSMC_OFF_COLLIDER_ENTRIES (CROSSMC_OFF_COLLIDERS + 0x20)
#define CROSSMC_OFF_ENTITY_ENTRIES   (CROSSMC_OFF_ENTITIES + 0x20)
#define CROSSMC_OFF_DAMAGE_ENTRIES   (CROSSMC_OFF_DAMAGE + 0x10)
#define CROSSMC_OFF_BLOCK_EDIT_ENTRIES (CROSSMC_OFF_BLOCK_EDITS + 0x10)
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
	uint32_t hostCapabilities;   /* CROSSMC_CAP_* the host provides (written by the host) */
	uint32_t hostPid;            /* host process id */
	uint32_t mcPid;              /* Minecraft process id */
	uint32_t hostStateSize;      /* sizeof(HostState) */
	uint32_t mcStateSize;        /* sizeof(McState) */
	uint32_t overlaySlotSize;    /* sizeof(OverlayFrameSlot) */
	uint32_t inputRingSize;      /* sizeof(InputRing) + capacity * sizeof(InputEvent) */
	uint32_t mcCapabilities;     /* CROSSMC_CAP_* Minecraft provides (written by MC) */
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
 *
 * AUTHORITY: this is the host's OWN avatar/environment, NOT the authority for the Minecraft
 * player. The Minecraft player is authoritative and its final state travels in McState; host
 * input travels in InputRing. Only the environment fields (viewport, cameraMode, unitsPerBlock,
 * worldId) are meaningful to Minecraft. The position/rotation fields are informational (the host
 * representation) and MUST NOT be used to drive the Minecraft player.
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
	double   posX, posY, posZ;   /* host avatar feet, MC space (informational, NOT authority) */
	float    yaw, pitch, roll;   /* host camera orientation (informational, NOT authority) */
	float    eyeHeight;          /* eye above feet, blocks */
	float    unitsPerBlock;      /* host units per Minecraft block (e.g. ~1.0 for a 1:1 game) */
	uint32_t teleportSeq;        /* bumps on an authoritative teleport */
	uint32_t cameraMode;         /* 0 first person, 1 behind, 2 front */
	uint32_t viewportW;
	uint32_t viewportH;
	uint32_t reserved0;
} HostState;
CROSSMC_STATIC_ASSERT(sizeof(HostState) <= 0x100, "HostState must fit its region");

/* ====================================================================================
 * McState @0x0200 — Minecraft -> host. seqlock: seq is odd while writing.
 *
 * AUTHORITY: the Minecraft player is the PRIMARY player. This struct is the authoritative player
 * state (position, rotation, flags); the host follows it. The host must never override the
 * Minecraft player from its own transform — it forwards input instead (InputRing).
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
 * InputRing @0x1000 — SPSC ring (host produces, Minecraft consumes). Protocol reserved;
 * basic state only (no full input system yet).
 *
 * The host is only an input SOURCE; Minecraft interprets events and computes the player
 * state. Every event carries `sequence` (monotonic) so Minecraft can order and de-duplicate
 * and never gets stuck: KEY_UP / MOUSE_UP always end a press, and RELEASE_ALL clears every
 * held key/button (send it on disconnect so Minecraft does not keep stale held inputs).
 * ==================================================================================== */
#define CROSSMC_INPUT_RING_ENTRIES 2048u

#define CROSSMC_INPUT_KEY_DOWN    1u  /* code = key;      a,b unused            */
#define CROSSMC_INPUT_KEY_UP      2u  /* code = key                             */
#define CROSSMC_INPUT_KEY_HOLD    3u  /* code = key;      a = held tick count   */
#define CROSSMC_INPUT_MOUSE_MOVE  4u  /* a,b = relative dx,dy (pixels)          */
#define CROSSMC_INPUT_MOUSE_DOWN  5u  /* code = button                          */
#define CROSSMC_INPUT_MOUSE_UP    6u  /* code = button                          */
#define CROSSMC_INPUT_MOUSE_WHEEL 7u  /* a   = delta                            */
#define CROSSMC_INPUT_CURSOR_POS  8u  /* a,b = absolute x,y (screen pixels)     */
#define CROSSMC_INPUT_RELEASE_ALL 9u  /* clear all held keys/buttons            */

typedef struct crossmc_input_event
{
	uint32_t type;               /* CROSSMC_INPUT_* */
	uint32_t code;               /* key / mouse button code */
	int32_t  a;                  /* value 1 (dx, wheel delta, cursor x, ...) */
	int32_t  b;                  /* value 2 (dy, cursor y, ...) */
	uint64_t timestampMs;        /* epoch ms */
	uint64_t sequence;           /* monotonic event id (order + de-dup) */
} InputEvent;
CROSSMC_STATIC_ASSERT(sizeof(InputEvent) == 0x20, "InputEvent size");

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

/* ====================================================================================
 * ColliderTable @0x20000 — host colliders -> Minecraft collision proxies.
 *
 * The host publishes its world colliders (in MINECRAFT space; the host adapter converts
 * from its own space). Minecraft voxelises them into client-side collision proxies so its
 * native collision / raycast / placement logic can see host space. The table is a
 * whole-table seqlock: the writer rewrites count + entries under an odd `seq`, the reader
 * retries until it gets a matching even `seq`.
 *
 * `id` is STABLE for the lifetime of the host collider so proxies can be created/updated/
 * destroyed. A full rewrite is not required: a consumer can diff by `id` and `revision`
 * (bump on every change), and the lifecycle flags below let an incremental producer emit
 * add/update/remove without resending unchanged colliders. `active` = CROSSMC_COLLIDER_ENABLED.
 * ==================================================================================== */
#define CROSSMC_COLLIDER_BOX     1u
#define CROSSMC_COLLIDER_SPHERE  2u
#define CROSSMC_COLLIDER_CAPSULE 3u

#define CROSSMC_COLLIDER_ENABLED (1u << 0)  /* active */
#define CROSSMC_COLLIDER_DYNAMIC (1u << 1)  /* moves; needs update stream */
#define CROSSMC_COLLIDER_ADDED   (1u << 2)  /* lifecycle: new this revision */
#define CROSSMC_COLLIDER_UPDATED (1u << 3)  /* lifecycle: changed geometry */
#define CROSSMC_COLLIDER_REMOVED (1u << 4)  /* lifecycle: gone (id retired) */

#define CROSSMC_COLLIDER_CAPACITY 512u

typedef struct crossmc_collider
{
	uint32_t id;                 /* stable host collider id */
	uint32_t type;               /* CROSSMC_COLLIDER_* */
	uint32_t flags;              /* CROSSMC_COLLIDER_* */
	uint32_t revision;           /* bumps on any geometric change (dirty check) */
	float    centerX, centerY, centerZ; /* MC space */
	float    halfX, halfY, halfZ;       /* box half extents; sphere r=halfX; capsule r=halfX, halfH=halfY */
	float    rotYaw;             /* rotation about +Y, degrees (box/capsule) */
	uint32_t reserved1;
	uint64_t updatedMs;          /* epoch ms */
} Collider;
CROSSMC_STATIC_ASSERT(sizeof(Collider) == 0x38, "Collider size");

typedef struct crossmc_collider_table
{
	uint32_t seq;                /* seqlock counter */
	uint32_t count;
	uint32_t capacity;           /* CROSSMC_COLLIDER_CAPACITY */
	uint32_t flags;              /* reserved */
	uint64_t revision;           /* bumps on every rewrite */
	uint64_t timestampMs;
} ColliderTable;
CROSSMC_STATIC_ASSERT(sizeof(ColliderTable) == 0x20, "ColliderTable size");

/* ====================================================================================
 * EntityTable @0x40000 — host entities <-> Minecraft proxy entities.
 *
 * The host publishes its creatures/items/bosses (MC-space position + health). Minecraft
 * spawns one hidden proxy entity per row. Damage done to the proxy is reported back
 * through DamageRing.
 *
 * Identity (CrossMC-defined, so nothing depends on a game's native ids):
 *     Minecraft entity id  <->  crossEntityId  <->  hostEntityId
 *   - `crossEntityId` is the STABLE CrossMC key. The host MUST allocate it once per host
 *     entity and keep it for the entity's whole life (never reuse while alive).
 *   - `hostEntityId` is the host's own id (e.g. FishNet NetworkObject.ObjectId) — for
 *     debugging/mapping only, never the primary key.
 *   - `mcEntityId` is filled in by Minecraft for the bound proxy (0 = unbound).
 *
 * Lifecycle + mapping queries are derived from the whole-table snapshot: a row that is
 * present with a new revision = spawn/update; a crossEntityId that is absent = destroy.
 * The table is rewritten under a seqlock, so consumers see a consistent snapshot and can
 * diff it against the previous one.
 * ==================================================================================== */
#define CROSSMC_ENTITY_CREATURE 1u
#define CROSSMC_ENTITY_PLAYER   2u
#define CROSSMC_ENTITY_ITEM     3u
#define CROSSMC_ENTITY_BOSS     4u

#define CROSSMC_ENTITY_DEAD    (1u << 0)
#define CROSSMC_ENTITY_BOSS_FLAG (1u << 1)
#define CROSSMC_ENTITY_VISIBLE (1u << 2)

#define CROSSMC_ENTITY_CAPACITY 512u

typedef struct crossmc_entity_map
{
	uint32_t hostEntityId;       /* host-native id (debug/mapping only) */
	uint32_t mcEntityId;         /* bound Minecraft entity id, 0 = unbound */
	uint32_t kind;               /* CROSSMC_ENTITY_* */
	uint32_t flags;              /* CROSSMC_ENTITY_* */
	float    x, y, z;            /* MC space feet position */
	float    yaw, pitch;
	float    health, maxHealth;
	uint32_t crossEntityId;      /* STABLE CrossMC key (allocated by the host) */
	uint64_t updatedMs;
} EntityMap;
CROSSMC_STATIC_ASSERT(sizeof(EntityMap) == 0x38, "EntityMap size");

typedef struct crossmc_entity_table
{
	uint32_t seq;                /* seqlock counter */
	uint32_t count;
	uint32_t capacity;           /* CROSSMC_ENTITY_CAPACITY */
	uint32_t flags;
	uint64_t revision;
	uint64_t timestampMs;
} EntityTable;
CROSSMC_STATIC_ASSERT(sizeof(EntityTable) == 0x20, "EntityTable size");

/* ====================================================================================
 * DamageRing @0x60000 — Minecraft damage events -> host.
 *
 * Minecraft writes one DamageEvent for every native damage applied to a proxy entity
 * (melee, projectile, explosion/TNT, fall, fire, ...). The host reads them and applies its
 * own damage rules/multipliers to the real host entity. Single producer (Minecraft) /
 * single consumer (host); head/tail are monotonic counters, entry index = i % capacity.
 * ==================================================================================== */
#define CROSSMC_DMG_GENERIC    0u
#define CROSSMC_DMG_PLAYER     1u
#define CROSSMC_DMG_MOB        2u
#define CROSSMC_DMG_PROJECTILE 3u
#define CROSSMC_DMG_EXPLOSION  4u
#define CROSSMC_DMG_FALL       5u
#define CROSSMC_DMG_FIRE       6u
#define CROSSMC_DMG_MAGIC      7u
#define CROSSMC_DMG_OTHER      8u

#define CROSSMC_DMG_CRITICAL (1u << 0)

#define CROSSMC_DAMAGE_CAPACITY 1024u

typedef struct crossmc_damage_event
{
	uint32_t crossEntityId;      /* victim CrossEntityId (primary key) */
	uint32_t mcEntityId;         /* victim MC entity id (debug) */
	uint32_t sourceType;         /* CROSSMC_DMG_* */
	uint32_t flags;              /* CROSSMC_DMG_* */
	float    amount;             /* raw damage as applied by Minecraft */
	uint32_t attackerCrossId;    /* 0 = none */
	float    x, y, z;            /* victim position (MC space) */
	float    knockbackX, knockbackZ;
	uint32_t reserved0;
	uint64_t sequence;           /* monotonic event id (order + de-dup) */
	uint64_t timestampMs;
} DamageEvent;
CROSSMC_STATIC_ASSERT(sizeof(DamageEvent) == 0x40, "DamageEvent size");

typedef struct crossmc_damage_ring
{
	uint32_t head;               /* producer (Minecraft) monotonic write counter */
	uint32_t tail;               /* consumer (host) monotonic read counter */
	uint32_t capacity;           /* CROSSMC_DAMAGE_CAPACITY */
	uint32_t reserved0;
} DamageRing;
CROSSMC_STATIC_ASSERT(sizeof(DamageRing) == 0x10, "DamageRing size");

/* ====================================================================================
 * BlockEditRing @0x80000 — Minecraft blocks -> host (RESERVED, reverse direction).
 *
 * Placeholder so the reverse constraint path (MC block space constraining host entities)
 * can be added without another layout change. Not written in v1.
 * ==================================================================================== */
#define CROSSMC_BLOCK_SET   1u
#define CROSSMC_BLOCK_CLEAR 2u

#define CROSSMC_BLOCK_EDIT_CAPACITY 1024u

typedef struct crossmc_block_edit
{
	int32_t  x, y, z;            /* MC block position */
	uint32_t action;             /* CROSSMC_BLOCK_* */
	uint32_t blockId;            /* opaque MC block state id */
	uint32_t reserved0;
	uint64_t sequence;
	uint64_t timestampMs;
} BlockEdit;
CROSSMC_STATIC_ASSERT(sizeof(BlockEdit) == 0x28, "BlockEdit size");

typedef struct crossmc_block_edit_ring
{
	uint32_t head;
	uint32_t tail;
	uint32_t capacity;
	uint32_t reserved0;
} BlockEditRing;
CROSSMC_STATIC_ASSERT(sizeof(BlockEditRing) == 0x10, "BlockEditRing size");

CROSSMC_EXTERN_C_END

#endif /* CROSSMC_BRIDGE_PROTOCOL_H */
