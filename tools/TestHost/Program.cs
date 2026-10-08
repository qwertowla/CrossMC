using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Threading;
using CrossMC.Bridge;

namespace CrossMC.TestHost
{
    /// <summary>
    /// Minimal CrossMC test host (no Unity, no game). Simulates the host side of the protocol so the
    /// shared memory, capabilities, sequence, entity lifecycle, state/event separation, input and
    /// disconnect can be exercised against Minecraft (or standalone with <c>--sim-mc</c>).
    ///
    /// <para>Usage: <c>dotnet run -- [--sim-mc] [--no-heartbeat]</c></para>
    ///   <c>--sim-mc</c>      also write McState and push damage events (stands in for Minecraft).
    ///   <c>--no-heartbeat</c> stop writing the host heartbeat, to exercise disconnect handling.
    /// </summary>
    internal static class Program
    {
        private static int Main(string[] args)
        {
            bool simMc = Array.IndexOf(args, "--sim-mc") >= 0;
            bool noHeartbeat = Array.IndexOf(args, "--no-heartbeat") >= 0;

            BridgeMemory memory;

            try
            {
                memory = BridgeMemory.Open();
            }
            catch (Exception e)
            {
                Console.Error.WriteLine("failed to open shared memory: " + e.Message);
                return 1;
            }

            using (memory)
            {
                int pid = Process.GetCurrentProcess().Id;

                if (!memory.HasValidHeader())
                {
                    memory.InitHeader(pid, 0);
                    memory.InitTripleBuffer();
                    memory.InitTables();
                    Console.WriteLine("created shared memory header (hostPid=" + pid + ")");
                }
                else
                {
                    memory.WriteHostPid(pid);
                    Console.WriteLine("joined shared memory (hostPid=" + pid + ")");
                }

                memory.WriteHostCapabilities(Protocol.CapAll);
                Console.WriteLine("mapping: " + Config.ResolveMappingPath());
                Console.WriteLine("config : " + Config.ConfigSource());
                Console.WriteLine("mode   : simMc=" + simMc + " noHeartbeat=" + noHeartbeat);

                var colliders = new List<Collider>();
                var entities = new List<EntityMap>();
                var hostEntities = new[]
                {
                    (cross: 1001, host: 11, kind: Protocol.EntityCreature),
                    (cross: 1002, host: 12, kind: Protocol.EntityCreature),
                    (cross: 1003, host: 13, kind: Protocol.EntityBoss),
                };

                long tick = 0;

                while (true)
                {
                    tick++;
                    long now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();

                    if (!noHeartbeat)
                    {
                        memory.WriteHostHeartbeat(now);
                    }

                    double a = tick * 0.05;

                    // ---- state: player ----
                    memory.WriteHostState(new HostState
                    {
                        Flags = 1,
                        TimestampMs = now,
                        PosX = Math.Cos(a) * 8,
                        PosY = 64,
                        PosZ = Math.Sin(a) * 8,
                        Yaw = (float)(a * 180 / Math.PI % 360),
                        Pitch = 0,
                        Roll = 0,
                        EyeHeight = 1.62f,
                        UnitsPerBlock = 1f,
                        ViewportW = 1920,
                        ViewportH = 1080,
                    });

                    // ---- state: colliders (id + revision + lifecycle) ----
                    colliders.Clear();
                    colliders.Add(new Collider
                    {
                        Id = 1,
                        Type = Protocol.ColliderBox,
                        Flags = Protocol.ColliderEnabled | Protocol.ColliderDynamic
                                | (tick == 1 ? Protocol.ColliderAdded : Protocol.ColliderUpdated),
                        Revision = (int)tick,
                        CenterX = (float)Math.Cos(a) * 6,
                        CenterY = 64,
                        CenterZ = (float)Math.Sin(a) * 6,
                        HalfX = 1,
                        HalfY = 1.5f,
                        HalfZ = 1,
                    });
                    memory.WriteColliderTable(colliders);

                    // ---- state: entities (stable CrossEntityId) ----
                    entities.Clear();

                    for (int i = 0; i < hostEntities.Length; i++)
                    {
                        var e = hostEntities[i];
                        double ea = a + i * 1.5;
                        entities.Add(new EntityMap
                        {
                            HostEntityId = e.host,
                            CrossEntityId = e.cross,
                            Kind = e.kind,
                            X = (float)(Math.Cos(ea) * 12),
                            Y = 64,
                            Z = (float)(Math.Sin(ea) * 12),
                            Yaw = (float)(ea * 180 / Math.PI),
                            Health = 100 - i * 10,
                            MaxHealth = 100,
                        });
                    }

                    memory.WriteEntityTable(entities);

                    // ---- event: input (host is the source) ----
                    memory.PushInput(new InputEvent
                    {
                        Type = Protocol.InputMouseMove,
                        A = (int)(Math.Cos(a) * 4),
                        B = (int)(Math.Sin(a) * 4),
                        TimestampMs = now,
                    });

                    // ---- optional: stand in for Minecraft ----
                    if (simMc)
                    {
                        memory.WriteMcHeartbeat(now);
                        memory.WriteMcState(new McState
                        {
                            Flags = 1,
                            TimestampMs = now,
                            X = 0,
                            Y = 64,
                            Z = 0,
                            Yaw = 0,
                            Pitch = 0,
                            EyeHeight = 1.62f,
                            FovDeg = 70,
                            TickMs = 50,
                            FrameCounter = tick,
                        });

                        if (tick % 20 == 0)
                        {
                            memory.PushDamage(new DamageEvent
                            {
                                CrossEntityId = 1001,
                                McEntityId = 4242,
                                SourceType = Protocol.DmgExplosion,
                                Amount = 20,
                                X = 0,
                                Y = 64,
                                Z = 0,
                                TimestampMs = now,
                            });
                        }
                    }

                    // ---- consume events / read MC state ----
                    var damage = memory.PollDamage();

                    if (damage != null)
                    {
                        Console.WriteLine("damage: cross=" + damage.CrossEntityId + " mc=" + damage.McEntityId
                                + " type=" + damage.SourceType + " amount=" + damage.Amount);
                    }

                    if (tick % 20 == 0)
                    {
                        var mc = TryReadMcState(memory);
                        Console.WriteLine("t=" + tick
                                + " hostAlive=" + memory.HostAlive(now)
                                + " mcAlive=" + memory.McAlive(now)
                                + " mcState=" + (mc == null ? "-" : mc.X.ToString("F1") + "," + mc.Y.ToString("F1") + "," + mc.Z.ToString("F1"))
                                + " entities=" + entities.Count + " colliders=" + colliders.Count);
                    }

                    Thread.Sleep(50);
                }
            }
        }

        private static McState TryReadMcState(BridgeMemory memory)
        {
            try
            {
                return memory.ReadMcState();
            }
            catch (Exception)
            {
                return null;
            }
        }
    }
}
