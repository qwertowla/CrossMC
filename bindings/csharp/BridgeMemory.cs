using System;
using System.Collections.Generic;
using System.IO;
using System.IO.MemoryMappedFiles;
using System.Runtime.InteropServices;
using System.Threading;

namespace CrossMC.Bridge
{
    /// <summary>
    /// C# runtime over the CrossMC shared memory. Mirrors <c>bindings/java</c>: file-backed mapping,
    /// lock-free triple buffer for frames, seqlocks for HostState/McState, whole-table seqlocks for
    /// the collider/entity tables, and a SPSC damage ring (Minecraft producer, host consumer).
    ///
    /// <para>All field access goes through a raw pointer into the mapping so the triple-buffer state
    /// word can be swapped with a real <see cref="Interlocked.Exchange(ref int, int)"/>.</para>
    /// </summary>
    public sealed unsafe class BridgeMemory : IDisposable
    {
        private readonly MemoryMappedFile _mmf;
        private readonly MemoryMappedViewAccessor _view;
        private readonly byte* _base;
        private int _front;

        private BridgeMemory(MemoryMappedFile mmf, MemoryMappedViewAccessor view)
        {
            _mmf = mmf;
            _view = view;
            byte* p = null;
            view.SafeMemoryMappedViewHandle.AcquirePointer(ref p);
            _base = p + view.PointerOffset;
        }

        public static BridgeMemory Open()
        {
            string path = Config.ResolveMappingPath();
            string dir = Path.GetDirectoryName(path);

            if (!string.IsNullOrEmpty(dir))
            {
                Directory.CreateDirectory(dir);
            }

            var file = new FileStream(path, FileMode.OpenOrCreate, FileAccess.ReadWrite, FileShare.ReadWrite);

            if (file.Length < Protocol.MappingBytes)
            {
                file.SetLength(Protocol.MappingBytes);
            }

            var mmf = MemoryMappedFile.CreateFromFile(file, null, Protocol.MappingBytes,
                    MemoryMappedFileAccess.ReadWrite, HandleInheritability.None, leaveOpen: false);
            var view = mmf.CreateViewAccessor(0, Protocol.MappingBytes, MemoryMappedFileAccess.ReadWrite);
            return new BridgeMemory(mmf, view);
        }

        private byte* P(long off) => _base + off;
        private int ReadI32(long off) => *(int*)P(off);
        private void WriteI32(long off, int v) => *(int*)P(off) = v;
        private long ReadI64(long off) => *(long*)P(off);
        private void WriteI64(long off, long v) => *(long*)P(off) = v;
        private float ReadF32(long off) => *(float*)P(off);
        private void WriteF32(long off, float v) => *(float*)P(off) = v;
        private double ReadF64(long off) => *(double*)P(off);
        private void WriteF64(long off, double v) => *(double*)P(off) = v;

        // ---------------------------------------------------------------- header

        public bool HasValidHeader()
        {
            return (uint)ReadI32(Protocol.HdrMagic) == Protocol.Magic
                && (uint)ReadI32(Protocol.HdrVersion) == Protocol.Version
                && ReadI32(Protocol.HdrHeaderSize) == Protocol.HeaderSize
                && ReadI32(Protocol.HdrHostStateSize) == Protocol.HostStateSize
                && ReadI32(Protocol.HdrMcStateSize) == Protocol.McStateSize
                && ReadI32(Protocol.HdrOverlaySlotSize) == Protocol.OverlaySlotSize;
        }

        public void InitHeader(int hostPid, int mcPid)
        {
            WriteI32(Protocol.HdrMagic, unchecked((int)Protocol.Magic));
            WriteI32(Protocol.HdrVersion, (int)Protocol.Version);
            WriteI32(Protocol.HdrHeaderSize, Protocol.HeaderSize);
            WriteI32(Protocol.HdrMappingBytes, (int)Protocol.MappingBytes);
            WriteI32(Protocol.HdrFlags, 0);
            WriteI32(Protocol.HdrHostPid, hostPid);
            WriteI32(Protocol.HdrMcPid, mcPid);
            WriteI32(Protocol.HdrHostStateSize, Protocol.HostStateSize);
            WriteI32(Protocol.HdrMcStateSize, Protocol.McStateSize);
            WriteI32(Protocol.HdrOverlaySlotSize, Protocol.OverlaySlotSize);
            WriteI64(Protocol.HdrHostHeartbeat, 0);
            WriteI64(Protocol.HdrMcHeartbeat, 0);
        }

        public void WriteHostPid(int pid) => WriteI32(Protocol.HdrHostPid, pid);
        public void WriteHostHeartbeat(long epochMs) => WriteI64(Protocol.HdrHostHeartbeat, epochMs);
        public long ReadMcHeartbeat() => ReadI64(Protocol.HdrMcHeartbeat);
        public long ReadHostHeartbeat() => ReadI64(Protocol.HdrHostHeartbeat);

        // ---------------------------------------------------------------- triple buffer

        public void InitTripleBuffer()
        {
            _front = 0;
            WriteI32(Protocol.CtlState, 1);
            WriteI64(Protocol.CtlFramesPublished, 0);
            WriteI64(Protocol.OffOverlayCtl + 16, 0);
            WriteI64(Protocol.OffOverlayCtl + 24, 0);
        }

        /// <summary>Host side: newest ready slot, or -1 if no fresh frame.</summary>
        public int Acquire()
        {
            int state = Volatile.Read(ref *(int*)P(Protocol.CtlState));

            if ((state & Protocol.OverlayFresh) == 0)
            {
                return -1;
            }

            int old = Interlocked.Exchange(ref *(int*)P(Protocol.CtlState), _front);
            _front = old & Protocol.OverlayIndexMask;
            return old & Protocol.OverlayIndexMask;
        }

        public int FrontSlot() => _front;

        public int SlotWidth(int slot) => ReadI32(Protocol.SlotHdr(slot) + 0);
        public int SlotHeight(int slot) => ReadI32(Protocol.SlotHdr(slot) + 4);
        public int SlotStride(int slot) => ReadI32(Protocol.SlotHdr(slot) + 8);
        public int SlotFormat(int slot) => ReadI32(Protocol.SlotHdr(slot) + 12);
        public int SlotFlags(int slot) => ReadI32(Protocol.SlotHdr(slot) + 16);
        public int SlotBufferIndex(int slot) => ReadI32(Protocol.SlotHdr(slot) + 20);

        /// <summary>Copies a slot's pixels into <paramref name="dst"/> (stride * height bytes).</summary>
        public void ReadPixels(int slot, byte[] dst)
        {
            Marshal.Copy((IntPtr)P(Protocol.SlotPixels(slot)), dst, 0, dst.Length);
        }

        // ---------------------------------------------------------------- seqlock state

        public void WriteHostState(HostState s)
        {
            long b = Protocol.OffHostState;
            int seq = Volatile.Read(ref *(int*)P(b + 0));
            Volatile.Write(ref *(int*)P(b + 0), seq + 1);
            Thread.MemoryBarrier();
            WriteI32(b + 4, s.Flags);
            WriteI32(b + 8, s.WorldId);
            WriteI32(b + 12, s.CollisionEpoch);
            WriteI64(b + 16, s.TimestampMs);
            WriteF64(b + 24, s.PosX);
            WriteF64(b + 32, s.PosY);
            WriteF64(b + 40, s.PosZ);
            WriteF32(b + 48, s.Yaw);
            WriteF32(b + 52, s.Pitch);
            WriteF32(b + 56, s.Roll);
            WriteF32(b + 60, s.EyeHeight);
            WriteF32(b + 64, s.UnitsPerBlock);
            WriteI32(b + 68, s.TeleportSeq);
            WriteI32(b + 72, s.CameraMode);
            WriteI32(b + 76, s.ViewportW);
            WriteI32(b + 80, s.ViewportH);
            Thread.MemoryBarrier();
            Volatile.Write(ref *(int*)P(b + 0), seq + 2);
        }

        public McState ReadMcState()
        {
            long b = Protocol.OffMcState;

            for (int attempt = 0; attempt < 256; attempt++)
            {
                int s1 = Volatile.Read(ref *(int*)P(b + 0));

                if ((s1 & 1) != 0)
                {
                    continue;
                }

                var s = new McState();
                s.Flags = ReadI32(b + 4);
                s.TimestampMs = ReadI64(b + 8);
                s.X = ReadF64(b + 16);
                s.Y = ReadF64(b + 24);
                s.Z = ReadF64(b + 32);
                s.PrevX = ReadF64(b + 40);
                s.PrevY = ReadF64(b + 48);
                s.PrevZ = ReadF64(b + 56);
                s.CurX = ReadF64(b + 64);
                s.CurY = ReadF64(b + 72);
                s.CurZ = ReadF64(b + 80);
                s.Yaw = ReadF32(b + 88);
                s.Pitch = ReadF32(b + 92);
                s.EyeHeight = ReadF32(b + 96);
                s.FovDeg = ReadF32(b + 100);
                s.TickMs = ReadF32(b + 104);
                s.CameraMode = ReadI32(b + 108);
                s.CameraDistance = ReadF32(b + 112);
                s.FrameCounter = ReadI64(b + 120);
                s.TickQpc = ReadI64(b + 128);
                Thread.MemoryBarrier();

                if (s1 == Volatile.Read(ref *(int*)P(b + 0)))
                {
                    return s;
                }
            }

            throw new InvalidOperationException("McState seqlock read failed");
        }

        // ---------------------------------------------------------------- collider table (host -> MC)

        public void WriteColliderTable(IList<Collider> items)
        {
            long b = Protocol.OffColliders;
            int seq = Volatile.Read(ref *(int*)P(b + 0));
            Volatile.Write(ref *(int*)P(b + 0), seq + 1);
            Thread.MemoryBarrier();

            int n = Math.Min(items.Count, Protocol.ColliderCapacity);
            WriteI32(b + 4, n);
            WriteI32(b + 8, Protocol.ColliderCapacity);
            WriteI64(b + 16, ReadI64(b + 16) + 1);
            WriteI64(b + 24, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());

            for (int i = 0; i < n; i++)
            {
                Collider c = items[i];
                long e = Protocol.OffColliderEntries + (long)i * Protocol.ColliderSize;
                WriteI32(e + 0, c.Id);
                WriteI32(e + 4, c.Type);
                WriteI32(e + 8, c.Flags);
                WriteF32(e + 16, c.CenterX);
                WriteF32(e + 20, c.CenterY);
                WriteF32(e + 24, c.CenterZ);
                WriteF32(e + 28, c.HalfX);
                WriteF32(e + 32, c.HalfY);
                WriteF32(e + 36, c.HalfZ);
                WriteF32(e + 40, c.RotYaw);
                WriteI64(e + 48, c.UpdatedMs == 0 ? DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() : c.UpdatedMs);
            }

            Thread.MemoryBarrier();
            Volatile.Write(ref *(int*)P(b + 0), seq + 2);
        }

        // ---------------------------------------------------------------- entity table (host -> MC)

        public void WriteEntityTable(IList<EntityMap> items)
        {
            long b = Protocol.OffEntities;
            int seq = Volatile.Read(ref *(int*)P(b + 0));
            Volatile.Write(ref *(int*)P(b + 0), seq + 1);
            Thread.MemoryBarrier();

            int n = Math.Min(items.Count, Protocol.EntityCapacity);
            WriteI32(b + 4, n);
            WriteI32(b + 8, Protocol.EntityCapacity);
            WriteI64(b + 16, ReadI64(b + 16) + 1);
            WriteI64(b + 24, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());

            for (int i = 0; i < n; i++)
            {
                EntityMap m = items[i];
                long e = Protocol.OffEntityEntries + (long)i * Protocol.EntitySize;
                WriteI32(e + 0, m.HostEntityId);
                WriteI32(e + 4, m.McEntityId);
                WriteI32(e + 8, m.Kind);
                WriteI32(e + 12, m.Flags);
                WriteF32(e + 16, m.X);
                WriteF32(e + 20, m.Y);
                WriteF32(e + 24, m.Z);
                WriteF32(e + 28, m.Yaw);
                WriteF32(e + 32, m.Pitch);
                WriteF32(e + 36, m.Health);
                WriteF32(e + 40, m.MaxHealth);
                WriteI64(e + 48, m.UpdatedMs == 0 ? DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() : m.UpdatedMs);
            }

            Thread.MemoryBarrier();
            Volatile.Write(ref *(int*)P(b + 0), seq + 2);
        }

        // ---------------------------------------------------------------- damage ring (MC -> host)

        public DamageEvent PollDamage()
        {
            long b = Protocol.OffDamage;
            int head = ReadI32(b + 0);
            int tail = ReadI32(b + 4);

            if (tail >= head)
            {
                return null;
            }

            int idx = (int)(((long)tail % Protocol.DamageCapacity + Protocol.DamageCapacity) % Protocol.DamageCapacity);
            long e = Protocol.OffDamageEntries + (long)idx * Protocol.DamageEventSize;
            var d = new DamageEvent
            {
                HostEntityId = ReadI32(e + 0),
                McEntityId = ReadI32(e + 4),
                SourceType = ReadI32(e + 8),
                Flags = ReadI32(e + 12),
                Amount = ReadF32(e + 16),
                AttackerHostId = ReadI32(e + 20),
                X = ReadF32(e + 24),
                Y = ReadF32(e + 28),
                Z = ReadF32(e + 32),
                KnockbackX = ReadF32(e + 36),
                KnockbackZ = ReadF32(e + 40),
                Sequence = ReadI64(e + 48),
                TimestampMs = ReadI64(e + 56),
            };
            WriteI32(b + 4, tail + 1);
            return d;
        }

        public void InitTables()
        {
            WriteI32(Protocol.OffColliders + 8, Protocol.ColliderCapacity);
            WriteI32(Protocol.OffEntities + 8, Protocol.EntityCapacity);
            WriteI32(Protocol.OffDamage + 8, Protocol.DamageCapacity);
            WriteI32(Protocol.OffColliders, 0);
            WriteI32(Protocol.OffColliders + 4, 0);
            WriteI32(Protocol.OffEntities, 0);
            WriteI32(Protocol.OffEntities + 4, 0);
            WriteI32(Protocol.OffDamage, 0);
            WriteI32(Protocol.OffDamage + 4, 0);
        }

        public void Dispose()
        {
            try
            {
                _view.SafeMemoryMappedViewHandle.ReleasePointer();
                _view.Dispose();
            }
            finally
            {
                _mmf.Dispose();
            }
        }
    }
}
