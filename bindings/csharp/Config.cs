using System;
using System.Collections.Generic;
using System.IO;

namespace CrossMC.Bridge
{
    /// <summary>
    /// Resolves configuration the same way as the Java binding: <c>-Dcrossmc.config</c>-equivalent is
    /// the <c>CROSSMC_CONFIG</c> environment variable, then <c>./config/crossmc.properties</c>, then
    /// <c>%LOCALAPPDATA%/CrossMC/crossmc.properties</c>, falling back to the built-in default.
    /// </summary>
    public static class Config
    {
        public static string ResolveMappingPath()
        {
            var props = Load();
            string raw = props.TryGetValue(Protocol.ConfigKeyMappingPath, out var v) ? v : null;

            if (string.IsNullOrWhiteSpace(raw))
            {
                raw = Protocol.DefaultMappingPath;
            }

            return Expand(raw.Trim());
        }

        public static string ConfigSource()
        {
            string file = FindConfigFile();
            return file ?? "built-in default";
        }

        public static Dictionary<string, string> Load()
        {
            var dict = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
            string file = FindConfigFile();

            if (file == null)
            {
                return dict;
            }

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

                dict[t.Substring(0, eq).Trim()] = t.Substring(eq + 1).Trim();
            }

            return dict;
        }

        private static string FindConfigFile()
        {
            string explicitPath = Environment.GetEnvironmentVariable("CROSSMC_CONFIG");

            if (!string.IsNullOrEmpty(explicitPath) && File.Exists(explicitPath))
            {
                return explicitPath;
            }

            string local = Path.Combine(Protocol.ConfigDir, Protocol.ConfigFile);

            if (File.Exists(local))
            {
                return Path.GetFullPath(local);
            }

            string baseDir = Environment.GetEnvironmentVariable("LOCALAPPDATA");

            if (!string.IsNullOrEmpty(baseDir))
            {
                string user = Path.Combine(baseDir, Protocol.MappingSubdir, Protocol.ConfigFile);

                if (File.Exists(user))
                {
                    return user;
                }
            }

            return null;
        }

        public static string Expand(string raw)
        {
            string value = raw;

            if (value.StartsWith("~"))
            {
                value = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile) + value.Substring(1);
            }

            int i;

            while ((i = value.IndexOf('%')) >= 0)
            {
                int j = value.IndexOf('%', i + 1);

                if (j < 0)
                {
                    break;
                }

                string name = value.Substring(i + 1, j - i - 1);
                string rep = Environment.GetEnvironmentVariable(name);

                if (string.IsNullOrEmpty(rep))
                {
                    rep = (name.Equals("LOCALAPPDATA", StringComparison.OrdinalIgnoreCase)
                            || name.Equals("APPDATA", StringComparison.OrdinalIgnoreCase))
                        ? Path.GetTempPath()
                        : name;
                }

                value = value.Substring(0, i) + rep + value.Substring(j + 1);
            }

            return Path.GetFullPath(value);
        }
    }
}
