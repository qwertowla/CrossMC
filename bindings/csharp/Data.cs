namespace CrossMC.Bridge
{
    /// <summary>Mirror of <c>crossmc_host_state</c>. Host -> Minecraft.</summary>
    public sealed class HostState
    {
        public int Flags;
        public int WorldId;
        public int CollisionEpoch;
        public long TimestampMs;
        public double PosX, PosY, PosZ;
        public float Yaw, Pitch, Roll;
        public float EyeHeight;
        public float UnitsPerBlock = 1f;
        public int TeleportSeq;
        public int CameraMode;
        public int ViewportW, ViewportH;
    }

    /// <summary>Mirror of <c>crossmc_mc_state</c>. Minecraft -> host.</summary>
    public sealed class McState
    {
        public int Flags;
        public long TimestampMs;
        public double X, Y, Z;
        public double PrevX, PrevY, PrevZ;
        public double CurX, CurY, CurZ;
        public float Yaw, Pitch;
        public float EyeHeight;
        public float FovDeg;
        public float TickMs;
        public int CameraMode;
        public float CameraDistance;
        public long FrameCounter;
        public long TickQpc;
    }

    /// <summary>Mirror of <c>crossmc_collider</c>.</summary>
    public sealed class Collider
    {
        public int Id;
        public int Type;      // Protocol.Collider*
        public int Flags;
        public float CenterX, CenterY, CenterZ;
        public float HalfX, HalfY, HalfZ;
        public float RotYaw;
        public long UpdatedMs;
    }

    /// <summary>Mirror of <c>crossmc_entity_map</c>.</summary>
    public sealed class EntityMap
    {
        public int HostEntityId;
        public int McEntityId;
        public int Kind;      // Protocol.Entity*
        public int Flags;
        public float X, Y, Z;
        public float Yaw, Pitch;
        public float Health, MaxHealth;
        public long UpdatedMs;
    }

    /// <summary>Mirror of <c>crossmc_damage_event</c>.</summary>
    public sealed class DamageEvent
    {
        public int HostEntityId;
        public int McEntityId;
        public int SourceType;
        public int Flags;
        public float Amount;
        public int AttackerHostId;
        public float X, Y, Z;
        public float KnockbackX, KnockbackZ;
        public long Sequence;
        public long TimestampMs;
    }
}
