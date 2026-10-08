using System;

namespace CrossMC.Bridge
{
    /// <summary>
    /// Mirror of <c>protocol/bridge_protocol.h</c> (v3). Keep offsets, sizes and constants
    /// identical to the C header and to the Java binding.
    /// </summary>
    public static class Protocol
    {
        public const uint Magic = 0x42434D43u; // 'C','M','C','B'
        public const uint Version = 3;

        public const string MappingSubdir = "CrossMC";
        public const string MappingFile = "bridge_v3.bin";
        public const string ConfigDir = "config";
        public const string ConfigFile = "crossmc.properties";
        public const string ConfigKeyMappingPath = "mapping.path";
        public const string DefaultMappingPath = "%LOCALAPPDATA%/CrossMC/bridge_v3.bin";

        public const int MaxFrameW = 3840;
        public const int MaxFrameH = 2160;
        public const int BytesPerPixel = 4; // BGRA8
        public const long FrameSlotBytes = (long)MaxFrameW * MaxFrameH * BytesPerPixel;

        // region offsets
        public const long OffHeader = 0x0000;
        public const long OffHostState = 0x0100;
        public const long OffMcState = 0x0200;
        public const long OffOverlayCtl = 0x0300;
        public const long OffOverlaySlots = 0x0340;
        public const long OffDepthFrame = 0x0400;
        public const long OffInputRing = 0x1000;
        public const long OffColliders = 0x20000;
        public const long OffColliderEntries = OffColliders + 0x20;
        public const long OffEntities = 0x40000;
        public const long OffEntityEntries = OffEntities + 0x20;
        public const long OffDamage = 0x60000;
        public const long OffDamageEntries = OffDamage + 0x10;
        public const long OffBlockEdits = 0x80000;
        public const long OffFrames = 0x100000;
        public const long MappingBytes = OffFrames + FrameSlotBytes * 3;

        // struct sizes
        public const int HeaderSize = 0x50;
        public const int HostStateSize = 0x58;
        public const int McStateSize = 0x90;
        public const int OverlayControlSize = 0x20;
        public const int OverlaySlotSize = 0x40;
        public const int ColliderSize = 0x38;
        public const int ColliderTableSize = 0x20;
        public const int EntitySize = 0x38;
        public const int EntityTableSize = 0x20;
        public const int DamageEventSize = 0x40;
        public const int DamageRingSize = 0x10;

        public const int OverlaySlots = 3;
        public const int ColliderCapacity = 512;
        public const int EntityCapacity = 512;
        public const int DamageCapacity = 1024;

        // header fields
        public const long HdrMagic = OffHeader + 0;
        public const long HdrVersion = OffHeader + 4;
        public const long HdrHeaderSize = OffHeader + 8;
        public const long HdrMappingBytes = OffHeader + 12;
        public const long HdrFlags = OffHeader + 16;
        public const long HdrHostPid = OffHeader + 20;
        public const long HdrMcPid = OffHeader + 24;
        public const long HdrHostStateSize = OffHeader + 28;
        public const long HdrMcStateSize = OffHeader + 32;
        public const long HdrOverlaySlotSize = OffHeader + 36;
        public const long HdrHostHeartbeat = OffHeader + 48;
        public const long HdrMcHeartbeat = OffHeader + 56;

        // overlay control
        public const long CtlState = OffOverlayCtl + 0;
        public const long CtlFramesPublished = OffOverlayCtl + 8;
        public const int OverlayFresh = 1 << 2;
        public const int OverlayIndexMask = 0x3;
        public const int FormatBgra8 = 1;
        public const int OverlayBottomUp = 1 << 0;

        // collider kinds
        public const int ColliderBox = 1;
        public const int ColliderSphere = 2;
        public const int ColliderCapsule = 3;
        public const int ColliderEnabled = 1 << 0;

        // entity kinds
        public const int EntityCreature = 1;
        public const int EntityPlayer = 2;
        public const int EntityItem = 3;
        public const int EntityBoss = 4;
        public const int EntityDead = 1 << 0;
        public const int EntityBossFlag = 1 << 1;

        // damage source kinds
        public const int DmgGeneric = 0;
        public const int DmgPlayer = 1;
        public const int DmgMob = 2;
        public const int DmgProjectile = 3;
        public const int DmgExplosion = 4;
        public const int DmgFall = 5;
        public const int DmgFire = 6;
        public const int DmgMagic = 7;
        public const int DmgOther = 8;

        public static long SlotHdr(int i) => OffOverlaySlots + (long)i * OverlaySlotSize;
        public static long SlotPixels(int i) => OffFrames + (long)i * FrameSlotBytes;
    }
}
