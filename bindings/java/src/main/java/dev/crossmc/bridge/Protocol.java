package dev.crossmc.bridge;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mirror of {@code protocol/bridge_protocol.h}. Keep offsets, sizes and constants identical
 * to the C header and to the C# binding. Bump {@link #VERSION} in all three when the layout
 * changes.
 *
 * <p>Shared memory is <b>file-backed</b>: the Java side cannot open Win32 named sections
 * ({@code Local\...}), only map a file. Both processes map the same file (see
 * {@link #mappingPath()}).
 */
public final class Protocol {
	private Protocol() {
	}

	public static final int MAGIC = 0x42434D43;   // 'C','M','C','B'
	public static final int VERSION = 1;

	public static final String MAPPING_SUBDIR = "CrossMC";
	public static final String MAPPING_FILE = "bridge_v1.bin";

	// ---- configuration (see config/crossmc.properties) ---------------------------------
	public static final String CONFIG_DIR = "config";
	public static final String CONFIG_FILE = "crossmc.properties";
	public static final String KEY_MAPPING_PATH = "mapping.path";
	public static final String DEFAULT_MAPPING_PATH =
			"%LOCALAPPDATA%/" + MAPPING_SUBDIR + "/" + MAPPING_FILE;

	public static final int MAX_FRAME_W = 3840;
	public static final int MAX_FRAME_H = 2160;
	public static final int BYTES_PER_PIXEL = 4;  // BGRA8
	public static final long FRAME_SLOT_BYTES = (long) MAX_FRAME_W * MAX_FRAME_H * BYTES_PER_PIXEL;

	// region offsets (bytes)
	public static final long OFF_HEADER = 0x0000L;
	public static final long OFF_HOST_STATE = 0x0100L;
	public static final long OFF_MC_STATE = 0x0200L;
	public static final long OFF_OVERLAY_CTL = 0x0300L;
	public static final long OFF_OVERLAY_HDRS = 0x0340L;
	public static final long OFF_INPUT_RING = 0x1000L;
	public static final long OFF_FRAMES = 0x100000L;
	public static final long MAPPING_BYTES = OFF_FRAMES + FRAME_SLOT_BYTES * 3L;

	public static final int OVERLAY_SLOTS = 3;
	public static final int SLOT_HDR_SIZE = 0x40;

	// Header field offsets
	public static final long HDR_MAGIC = OFF_HEADER + 0L;
	public static final long HDR_VERSION = OFF_HEADER + 4L;
	public static final long HDR_MAPPING_BYTES = OFF_HEADER + 8L;
	public static final long HDR_FLAGS = OFF_HEADER + 12L;
	public static final long HDR_HOST_PID = OFF_HEADER + 16L;
	public static final long HDR_MC_PID = OFF_HEADER + 20L;
	public static final long HDR_HOST_HEARTBEAT = OFF_HEADER + 24L;
	public static final long HDR_MC_HEARTBEAT = OFF_HEADER + 32L;

	// OverlayCtl
	public static final long CTL_STATE = OFF_OVERLAY_CTL + 0L;             // uint32
	public static final long CTL_FRAMES_PUBLISHED = OFF_OVERLAY_CTL + 8L;  // uint64
	public static final int OVERLAY_FRESH = 1 << 2;
	public static final int OVERLAY_INDEX_MASK = 0x3;

	public static final int FORMAT_BGRA8 = 1;
	public static final int OVERLAY_BOTTOM_UP = 1 << 0;

	/** Byte offset of slot {@code i}'s 0x40-byte header. */
	public static long slotHdr(int i) {
		return OFF_OVERLAY_HDRS + (long) i * SLOT_HDR_SIZE;
	}

	/** Byte offset of slot {@code i}'s pixel slab. */
	public static long slotPixels(int i) {
		return OFF_FRAMES + (long) i * FRAME_SLOT_BYTES;
	}

	/**
	 * Resolves the shared-memory file from the configuration ({@code mapping.path}), falling back
	 * to {@link #DEFAULT_MAPPING_PATH}. Both processes must resolve the same absolute path.
	 */
	public static Path mappingPath() {
		String raw = configValue(KEY_MAPPING_PATH);

		if (raw == null || raw.trim().isEmpty()) {
			raw = DEFAULT_MAPPING_PATH;
		}

		return expand(raw.trim());
	}

	/**
	 * The configuration file that will be used, or {@code null} if none was found. Search order:
	 * {@code -Dcrossmc.config} / {@code CROSSMC_CONFIG}, then {@code ./config/crossmc.properties},
	 * then {@code %LOCALAPPDATA%/CrossMC/crossmc.properties}.
	 */
	public static Path configFile() {
		String explicit = System.getProperty("crossmc.config");

		if (explicit == null || explicit.isEmpty()) {
			explicit = System.getenv("CROSSMC_CONFIG");
		}

		if (explicit != null && !explicit.isEmpty()) {
			Path p = Paths.get(explicit);

			if (Files.isRegularFile(p)) {
				return p;
			}
		}

		Path local = Paths.get(CONFIG_DIR, CONFIG_FILE);

		if (Files.isRegularFile(local)) {
			return local;
		}

		String base = System.getenv("LOCALAPPDATA");

		if (base != null && !base.isEmpty()) {
			Path userLevel = Paths.get(base, MAPPING_SUBDIR, CONFIG_FILE);

			if (Files.isRegularFile(userLevel)) {
				return userLevel;
			}
		}

		return null;
	}

	private static String configValue(String key) {
		Path config = configFile();

		if (config == null) {
			return null;
		}

		Properties props = new Properties();

		try (InputStream in = Files.newInputStream(config)) {
			props.load(in);
		} catch (IOException e) {
			System.err.println("[CrossMC] failed to read config " + config + ": " + e);
			return null;
		}

		return props.getProperty(key);
	}

	/** Expands {@code %VAR%} placeholders and a leading {@code ~}, then normalises to absolute. */
	private static Path expand(String raw) {
		String value = raw;

		if (value.startsWith("~")) {
			value = System.getProperty("user.home") + value.substring(1);
		}

		Matcher matcher = Pattern.compile("%([^%]+)%").matcher(value);
		StringBuilder out = new StringBuilder();

		while (matcher.find()) {
			String name = matcher.group(1);
			String replacement = System.getenv(name);

			if (replacement == null || replacement.isEmpty()) {
				if ("LOCALAPPDATA".equalsIgnoreCase(name) || "APPDATA".equalsIgnoreCase(name)) {
					replacement = System.getProperty("java.io.tmpdir");
				} else {
					replacement = System.getProperty(name, matcher.group(0));
				}
			}

			matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
		}

		matcher.appendTail(out);
		return Paths.get(out.toString()).toAbsolutePath().normalize();
	}
}
