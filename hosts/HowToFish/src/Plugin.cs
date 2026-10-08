using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Reflection;
using BepInEx;
using UnityEngine;
using BridgeMemory = CrossMC.Bridge.BridgeMemory;
using BridgeProtocol = CrossMC.Bridge.Protocol;
using HostState = CrossMC.Bridge.HostState;
using EntityMap = CrossMC.Bridge.EntityMap;
using DamageEvent = CrossMC.Bridge.DamageEvent;
using BridgeCollider = CrossMC.Bridge.Collider;

namespace CrossMC.HowToFish
{
    /// <summary>
    /// CrossMC host adapter for <i>How to Fish</i>.
    ///
    /// <p>Reads Minecraft's frame (overlay), publishes the host player/camera and the host world
    /// colliders/creatures into CrossMC, and applies Minecraft's damage events to the real host
    /// entities using this adapter's configured multipliers.</p>
    ///
    /// <p>All game access runs on the Unity main thread (Update/OnGUI). Only the shared-memory
    /// reads/writes are thread-agnostic.</p>
    /// </summary>
    [BepInPlugin("com.crossmc.howtofish", "CrossMC - How to Fish", "0.1.0")]
    [BepInProcess("How to Fish.exe")]
    public class Plugin : BaseUnityPlugin
    {
        private BridgeMemory _memory;
        private HostConfig _config;
        private CoordinateMapper _mapper;
        private FrameOverlay _overlay;

        private float _colliderTimer;
        private float _entityTimer;

        private readonly UnityEngine.Collider[] _overlapBuffer = new UnityEngine.Collider[512];
        private readonly List<BridgeCollider> _colliders = new List<BridgeCollider>();
        private readonly List<EntityMap> _entities = new List<EntityMap>();
        private readonly Dictionary<int, Creature> _hostCreatures = new Dictionary<int, Creature>();

        private void Awake()
        {
            _config = HostConfig.Load();
            _mapper = new CoordinateMapper(_config);

            try
            {
                _memory = BridgeMemory.Open();
                int pid = Process.GetCurrentProcess().Id;

                if (!_memory.HasValidHeader())
                {
                    _memory.InitHeader(pid, 0);
                    _memory.InitTripleBuffer();
                    _memory.InitTables();
                    Logger.LogInfo("CrossMC created shared memory header (hostPid=" + pid + ")");
                }
                else
                {
                    _memory.WriteHostPid(pid);
                    Logger.LogInfo("CrossMC joined shared memory (hostPid=" + pid + ")");
                }

                var go = new GameObject("CrossMC.Overlay");
                DontDestroyOnLoad(go);
                _overlay = go.AddComponent<FrameOverlay>();
                _overlay.Init(_memory, _config);

                Logger.LogInfo("CrossMC host ready. config=" + global::CrossMC.Bridge.Config.ConfigSource());
            }
            catch (Exception e)
            {
                Logger.LogError("CrossMC failed to open shared memory: " + e);
                _memory = null;
            }
        }

        private void Update()
        {
            if (_memory == null)
            {
                return;
            }

            _memory.WriteHostHeartbeat(DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
            PublishPlayer();

            float dt = Time.deltaTime;
            _colliderTimer -= dt;

            if (_colliderTimer <= 0f)
            {
                _colliderTimer = _config.ColliderInterval;
                ExportColliders();
            }

            _entityTimer -= dt;

            if (_entityTimer <= 0f)
            {
                _entityTimer = _config.EntityInterval;
                ExportEntities();
            }

            ApplyDamage();
        }

        private void PublishPlayer()
        {
            Player player = Player.LocalPlayer;

            if (!player || !player.Transform)
            {
                return;
            }

            Vector3 pos = _mapper.ToMc(player.Transform.position);
            Vector3 rot = player.CamObject ? player.CamObject.eulerAngles : Vector3.zero;

            var state = new HostState
            {
                Flags = 1, // in game
                TimestampMs = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
                PosX = pos.x,
                PosY = pos.y,
                PosZ = pos.z,
                Yaw = rot.y,
                Pitch = rot.x,
                Roll = rot.z,
                EyeHeight = 1.62f,
                UnitsPerBlock = 1f / Mathf.Max(0.0001f, _config.Scale),
                ViewportW = Screen.width,
                ViewportH = Screen.height,
            };
            _memory.WriteHostState(state);
        }

        private void ExportColliders()
        {
            Player player = Player.LocalPlayer;

            if (!player || !player.Transform)
            {
                return;
            }

            Vector3 center = player.Transform.position;
            _colliders.Clear();

            int count = Physics.OverlapSphereNonAlloc(center, _config.ColliderRadius, _overlapBuffer);
            int id = 1;

            for (int i = 0; i < count && _colliders.Count < _config.ColliderMax; i++)
            {
                UnityEngine.Collider col = _overlapBuffer[i];

                if (col == null || col.isTrigger)
                {
                    continue;
                }

                Bounds b = col.bounds;
                Vector3 c = _mapper.ToMc(b.center);
                float s = _config.Scale;
                _colliders.Add(new BridgeCollider
                {
                    Id = id++,
                    Type = BridgeProtocol.ColliderBox,
                    Flags = BridgeProtocol.ColliderEnabled,
                    CenterX = c.x,
                    CenterY = c.y,
                    CenterZ = c.z,
                    HalfX = b.extents.x * s,
                    HalfY = b.extents.y * s,
                    HalfZ = b.extents.z * s,
                });
            }

            _memory.WriteColliderTable(_colliders);
        }

        private void ExportEntities()
        {
            Player player = Player.LocalPlayer;

            if (!player || !player.Transform)
            {
                return;
            }

            Vector3 center = player.Transform.position;
            _entities.Clear();
            _hostCreatures.Clear();

            Creature boss = BossManager.Boss;
            Creature[] creatures = UnityEngine.Object.FindObjectsByType<Creature>(FindObjectsInactive.Exclude);

            foreach (Creature creature in creatures)
            {
                if (creature == null || creature.IsDead || creature.NetworkObject == null)
                {
                    continue;
                }

                if (Vector3.Distance(creature.transform.position, center) > _config.EntityRadius)
                {
                    continue;
                }

                int hostId = creature.NetworkObject.ObjectId;

                if (hostId == 0)
                {
                    continue;
                }

                Vector3 p = _mapper.ToMc(creature.transform.position);
                bool isBoss = boss == creature;
                _entities.Add(new EntityMap
                {
                    HostEntityId = hostId,
                    Kind = isBoss ? BridgeProtocol.EntityBoss : BridgeProtocol.EntityCreature,
                    Flags = isBoss ? BridgeProtocol.EntityBossFlag : 0,
                    X = p.x,
                    Y = p.y,
                    Z = p.z,
                    Yaw = creature.transform.eulerAngles.y,
                    Health = creature.Hp,
                    MaxHealth = creature.MaxHp,
                });
                _hostCreatures[hostId] = creature;

                if (_entities.Count >= BridgeProtocol.EntityCapacity)
                {
                    break;
                }
            }

            _memory.WriteEntityTable(_entities);
        }

        private void ApplyDamage()
        {
            for (int guard = 0; guard < 64; guard++)
            {
                DamageEvent d = _memory.PollDamage();

                if (d == null)
                {
                    break;
                }

                float multiplier = _config.DamageMultiplier(d.SourceType);
                int scaled = Mathf.Max(0, Mathf.RoundToInt(d.Amount * multiplier));
                Logger.LogInfo("CrossMC damage: hostEntity=" + d.HostEntityId + " mc=" + d.McEntityId
                        + " type=" + d.SourceType + " raw=" + d.Amount + " x" + multiplier + " => " + scaled);
                ApplyToHostEntity(d.HostEntityId, scaled, d);
            }
        }

        private void ApplyToHostEntity(int hostId, int amount, DamageEvent d)
        {
            if (amount <= 0)
            {
                return;
            }

            if (_hostCreatures.TryGetValue(hostId, out Creature creature) && creature != null)
            {
                ApplyCreatureDamage(creature, amount, d);
                return;
            }

            // No bound creature (e.g. the mapping targets the player): apply to the local player.
            Player player = Player.LocalPlayer;

            if (player && player.Vitals != null)
            {
                Vector3 hit = new Vector3(d.X, d.Y, d.Z);
                player.Vitals.TakeDamage(amount, Vector3.zero, hit, false);
                Logger.LogInfo("CrossMC applied " + amount + " damage to the local player");
            }
        }

        private void ApplyCreatureDamage(Creature creature, int amount, DamageEvent d)
        {
            // The real creature damage entry point (LocalHit) has a long gameplay-specific
            // signature that has NOT been verified against the current build. Best effort: find it
            // and invoke with mapped arguments; any failure is logged, never hidden.
            try
            {
                MethodInfo hit = null;

                foreach (MethodInfo m in creature.GetType().GetMethods(BindingFlags.Public | BindingFlags.Instance))
                {
                    if (m.Name == "LocalHit")
                    {
                        hit = m;
                        break;
                    }
                }

                if (hit == null)
                {
                    Logger.LogWarning("CrossMC: no creature damage method found; dropped " + amount + " damage");
                    return;
                }

                hit.Invoke(creature, BuildHitArgs(hit, creature, amount, d));
                Logger.LogInfo("CrossMC invoked creature damage (" + amount + ") on host entity");
            }
            catch (Exception e)
            {
                Logger.LogWarning("CrossMC creature damage failed: " + e.Message);
            }
        }

        private static object[] BuildHitArgs(MethodInfo method, Creature creature, int amount, DamageEvent d)
        {
            ParameterInfo[] ps = method.GetParameters();
            object[] args = new object[ps.Length];
            Player player = Player.LocalPlayer;

            for (int i = 0; i < ps.Length; i++)
            {
                Type t = ps[i].ParameterType;

                if (t == typeof(Transform))
                {
                    args[i] = player && player.Transform ? player.Transform : creature.transform;
                }
                else if (t == typeof(Vector3))
                {
                    args[i] = new Vector3(d.X, d.Y, d.Z);
                }
                else if (t == typeof(Player))
                {
                    args[i] = player;
                }
                else if (t == typeof(int))
                {
                    args[i] = amount;
                }
                else if (t == typeof(float))
                {
                    args[i] = (float)amount;
                }
                else if (t == typeof(bool))
                {
                    args[i] = false;
                }
                else
                {
                    args[i] = t.IsValueType ? Activator.CreateInstance(t) : null;
                }
            }

            return args;
        }

        private void OnDestroy()
        {
            try
            {
                _memory?.Dispose();
            }
            catch (Exception)
            {
                // ignore
            }

            _memory = null;
        }
    }
}
