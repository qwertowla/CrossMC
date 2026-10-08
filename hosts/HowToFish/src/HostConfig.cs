using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using UnityEngine;

namespace CrossMC.HowToFish
{
    /// <summary>
    /// How to Fish-specific configuration. Damage multipliers live here (never in the protocol).
    /// </summary>
    public sealed class HostConfig
    {
        private readonly Dictionary<string, string> _values = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);

        public float OriginX;
        public float OriginY;
        public float OriginZ;
        public float Scale = 1f;
        public bool FlipX = true;

        public float ColliderRadius = 16f;
        public float ColliderInterval = 0.25f;
        public int ColliderMax = 256;

        public float EntityRadius = 64f;
        public float EntityInterval = 0.25f;

        public float OverlayX = 0.05f;
        public float OverlayY = 0.05f;
        public float OverlayWidth = 0.35f;
        public float OverlayHeight = 0.35f;

        public static HostConfig Load()
        {
            var cfg = new HostConfig();
            string file = FindFile();

            if (file != null)
            {
                foreach (string line in File.ReadAllLines(file))
                {
                    string t = line.Trim();

                    if (t.Length == 0 || t.StartsWith("#") || t.StartsWith(";"))
                    {
                        continue;
                    }

                    int eq = t.IndexOf('=');

                    if (eq <= 0)
                    {
                        continue;
                    }

                    cfg._values[t.Substring(0, eq).Trim()] = t.Substring(eq + 1).Trim();
                }
            }

            cfg.OriginX = cfg.GetFloat("transform.originX", 0f);
            cfg.OriginY = cfg.GetFloat("transform.originY", 0f);
            cfg.OriginZ = cfg.GetFloat("transform.originZ", 0f);
            cfg.Scale = cfg.GetFloat("transform.scale", 1f);
            cfg.FlipX = cfg.GetBool("transform.flipX", true);

            cfg.ColliderRadius = cfg.GetFloat("collider.radius", 16f);
            cfg.ColliderInterval = cfg.GetFloat("collider.interval", 0.25f);
            cfg.ColliderMax = (int)cfg.GetFloat("collider.max", 256f);

            cfg.EntityRadius = cfg.GetFloat("entity.radius", 64f);
            cfg.EntityInterval = cfg.GetFloat("entity.interval", 0.25f);

            cfg.OverlayX = cfg.GetFloat("overlay.x", 0.05f);
            cfg.OverlayY = cfg.GetFloat("overlay.y", 0.05f);
            cfg.OverlayWidth = cfg.GetFloat("overlay.width", 0.35f);
            cfg.OverlayHeight = cfg.GetFloat("overlay.height", 0.35f);
            return cfg;
        }

        /// <summary>Damage multiplier for a CrossMC damage source kind.</summary>
        public float DamageMultiplier(int sourceType)
        {
            string key = sourceType switch
            {
                1 => "damage.player",
                2 => "damage.mob",
                3 => "damage.projectile",
                4 => "damage.explosion",
                5 => "damage.fall",
                6 => "damage.fire",
                7 => "damage.magic",
                8 => "damage.other",
                _ => "damage.default",
            };
            return GetFloat(key, GetFloat("damage.default", 1f));
        }

        private float GetFloat(string key, float fallback)
        {
            return _values.TryGetValue(key, out string v)
                && float.TryParse(v, NumberStyles.Float, CultureInfo.InvariantCulture, out float f)
                ? f : fallback;
        }

        private bool GetBool(string key, bool fallback)
        {
            if (!_values.TryGetValue(key, out string v))
            {
                return fallback;
            }

            return v.Equals("true", StringComparison.OrdinalIgnoreCase) || v == "1";
        }

        private static string FindFile()
        {
            string baseDir = Environment.GetEnvironmentVariable("LOCALAPPDATA");

            if (!string.IsNullOrEmpty(baseDir))
            {
                string user = Path.Combine(baseDir, "CrossMC", "howtofish.properties");

                if (File.Exists(user))
                {
                    return user;
                }
            }

            string beside = Path.Combine(Path.GetDirectoryName(typeof(HostConfig).Assembly.Location), "howtofish.properties");

            if (File.Exists(beside))
            {
                return beside;
            }

            string repo = Path.Combine(Path.GetDirectoryName(typeof(HostConfig).Assembly.Location), "host.properties");

            if (File.Exists(repo))
            {
                return repo;
            }

            return null;
        }
    }

    /// <summary>Host (Unity) world space -> Minecraft space, per the configured origin/scale/flip.</summary>
    public readonly struct CoordinateMapper
    {
        private readonly HostConfig _cfg;

        public CoordinateMapper(HostConfig cfg)
        {
            _cfg = cfg;
        }

        public Vector3 ToMc(Vector3 host)
        {
            float x = (host.x - _cfg.OriginX) * _cfg.Scale;
            float y = (host.y - _cfg.OriginY) * _cfg.Scale;
            float z = (host.z - _cfg.OriginZ) * _cfg.Scale;

            if (_cfg.FlipX)
            {
                x = -x;
            }

            return new Vector3(x, y, z);
        }
    }
}
