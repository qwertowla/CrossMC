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
 * Mirror of {@code protocol/bridge_protocol.h}. Keep offsets, sizes and constants identical to
 * the C header (which is the single source of truth) and to the C# binding. Bump {@link #VERSION}
 * in all three when the layout changes.
 *
 * <p>Shared memory is <b>file-backed</b>: the Java side cannot open Win32 named sections
 * ({@code Local\...}), only map a file. Both processes map the same file (see
 * {@link #mappingPath()}).
 */
public final class Protocol {
	private Protocol() {
	}

	public static final int MAGIC = 0x42434D43;   // 'C','M','C','B'
	public static final int VERSION = 4;

	public static final String MAPPING_SUBDIR = "CrossMC";
	public static final String MAPPING_FILE = "bridge_v4.bin";

	// ---- capabilities (CROSSMC_CAP_*) ----
	public static final int CAP_FRAME = 1 << 0;
	public static final int CAP_STATE = 1 << 1;
	public static final int CAP_ENTITY = 1 << 2;
	public static final int CAP_COLLISION = 1 << 3;
	public static final int CAP_DAMAGE = 1 << 4;
	public static final int CAP_INPUT = 1 << 5;
	public static final int CAP_DEPTH = 1 << 6;
	public static final int CAP_BLOCK_EDIT = 1 << 7;
	public static final int CAP_ALL = CAP_FRAME | CAP_STATE | CAP_ENTITY | CAP_COLLISION
			| CAP_DAMAGE | CAP_INPUT | CAP_DEPTH | CAP_BLOCK_EDIT;

	public static final long HEARTBEAT_TIMEOUT_MS = 2000L;

	// ---- configuration (see config/crossmc.properties) ---------------------------------
	public static final String CONFIG_DIR = "config";
	public static final String CONFIG_FILE = "crossmc.properties";
	public static final String CONFIG_RESOURCE = "/crossmc.properties";
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
	public static final long OFF_OVERLAY_SLOTS = 0x0340L;
	public static final long OFF_DEPTH_FRAME = 0x0400L;
	public static final long OFF_INPUT_RING = 0x1000L;
	public static final long OFF_INPUT_EVENTS = 0x1040L;
	public static final long OFF_COLLIDERS = 0x20000L;
	public static final long OFF_COLLIDER_ENTRIES = OFF_COLLIDERS + 0x20L;
	public static final long OFF_ENTITIES = 0x40000L;
	public static final long OFF_ENTITY_ENTRIES = OFF_ENTITIES + 0x20L;
	public static final long OFF_DAMAGE = 0x60000L;
	public static final long OFF_DAMAGE_ENTRIES = OFF_DAMAGE + 0x10L;
	public static final long OFF_BLOCK_EDITS = 0x80000L;
	public static final long OFF_BLOCK_EDIT_ENTRIES = OFF_BLOCK_EDITS + 0x10L;
	public static final long OFF_FRAMES = 0x100000L;
	public static final long MAPPING_BYTES = OFF_FRAMES + FRAME_SLOT_BYTES * 3L;

	// struct sizes (must equal the C static_asserts)
	public static final int HEADER_SIZE = 0x50;
	public static final int HOST_STATE_SIZE = 0x58;
	public static final int MC_STATE_SIZE = 0x90;
	public static final int OVERLAY_CONTROL_SIZE = 0x20;
	public static final int OVERLAY_SLOT_SIZE = 0x40;
	public static final int INPUT_EVENT_SIZE = 0x20;
	public static final int INPUT_RING_SIZE = 0x10;

	// Entry-relative field offsets (collider / entity / input)
	public static final int COLLIDER_REVISION = 12;
	public static final int ENTITY_CROSS_ID = 44;
	public static final int INPUT_TYPE = 0;
	public static final int INPUT_CODE = 4;
	public static final int INPUT_A = 8;
	public static final int INPUT_B = 12;
	public static final int INPUT_TIMESTAMP = 16;
	public static final int INPUT_SEQUENCE = 24;
	public static final int DEPTH_FRAME_SIZE = 0x30;
	public static final int COLLIDER_SIZE = 0x38;
	public static final int COLLIDER_TABLE_SIZE = 0x20;
	public static final int ENTITY_SIZE = 0x38;
	public static final int ENTITY_TABLE_SIZE = 0x20;
	public static final int DAMAGE_EVENT_SIZE = 0x40;
	public static final int DAMAGE_RING_SIZE = 0x10;
	public static final int BLOCK_EDIT_SIZE = 0x28;
	public static final int BLOCK_EDIT_RING_SIZE = 0x10;

	public static final int OVERLAY_SLOTS = 3;
	public static final int INPUT_RING_ENTRIES = 2048;
	public static final int COLLIDER_CAPACITY = 512;
	public static final int ENTITY_CAPACITY = 512;
	public static final int DAMAGE_CAPACITY = 1024;
	public static final int BLOCK_EDIT_CAPACITY = 1024;

	// ---- collider kinds / flags (CROSSMC_COLLIDER_*) ----
	public static final int COLLIDER_BOX = 1;
	public static final int COLLIDER_SPHERE = 2;
	public static final int COLLIDER_CAPSULE = 3;
	public static final int COLLIDER_ENABLED = 1 << 0;
	public static final int COLLIDER_DYNAMIC = 1 << 1;
	public static final int COLLIDER_ADDED = 1 << 2;
	public static final int COLLIDER_UPDATED = 1 << 3;
	public static final int COLLIDER_REMOVED = 1 << 4;

	// ---- entity kinds / flags (CROSSMC_ENTITY_*) ----
	public static final int ENTITY_CREATURE = 1;
	public static final int ENTITY_PLAYER = 2;
	public static final int ENTITY_ITEM = 3;
	public static final int ENTITY_BOSS = 4;
	public static final int ENTITY_DEAD = 1 << 0;
	public static final int ENTITY_BOSS_FLAG = 1 << 1;
	public static final int ENTITY_VISIBLE = 1 << 2;

	// ---- damage source kinds / flags (CROSSMC_DMG_*) ----
	public static final int DMG_GENERIC = 0;
	public static final int DMG_PLAYER = 1;
	public static final int DMG_MOB = 2;
	public static final int DMG_PROJECTILE = 3;
	public static final int DMG_EXPLOSION = 4;
	public static final int DMG_FALL = 5;
	public static final int DMG_FIRE = 6;
	public static final int DMG_MAGIC = 7;
	public static final int DMG_OTHER = 8;
	public static final int DMG_CRITICAL = 1 << 0;

	// ---- input event types (CROSSMC_INPUT_*) ----
	public static final int INPUT_KEY_DOWN = 1;
	public static final int INPUT_KEY_UP = 2;
	public static final int INPUT_KEY_HOLD = 3;
	public static final int INPUT_MOUSE_MOVE = 4;
	public static final int INPUT_MOUSE_DOWN = 5;
	public static final int INPUT_MOUSE_UP = 6;
	public static final int INPUT_MOUSE_WHEEL = 7;
	public static final int INPUT_CURSOR_POS = 8;
	public static final int INPUT_RELEASE_ALL = 9;

	// ---- keyboard semantics for INPUT_KEY_* events (InputEvent.code) ----
	public static final int KEY_FORWARD = 1;
	public static final int KEY_BACK = 2;
	public static final int KEY_LEFT = 3;
	public static final int KEY_RIGHT = 4;
	public static final int KEY_JUMP = 5;
	public static final int KEY_SNEAK = 6;
	public static final int KEY_SPRINT = 7;
	public static final int KEY_INVENTORY = 8;
	public static final int KEY_DROP = 9;
	public static final int KEY_SWAP_HANDS = 10;

	// Header field offsets
	public static final long HDR_MAGIC = OFF_HEADER + 0L;
	public static final long HDR_VERSION = OFF_HEADER + 4L;
	public static final long HDR_HEADER_SIZE = OFF_HEADER + 8L;
	public static final long HDR_MAPPING_BYTES = OFF_HEADER + 12L;
	public static final long HDR_HOST_CAPS = OFF_HEADER + 16L;
	public static final long HDR_HOST_PID = OFF_HEADER + 20L;
	public static final long HDR_MC_PID = OFF_HEADER + 24L;
	public static final long HDR_HOST_STATE_SIZE = OFF_HEADER + 28L;
	public static final long HDR_MC_STATE_SIZE = OFF_HEADER + 32L;
	public static final long HDR_OVERLAY_SLOT_SIZE = OFF_HEADER + 36L;
	public static final long HDR_INPUT_RING_SIZE = OFF_HEADER + 40L;
	public static final long HDR_MC_CAPS = OFF_HEADER + 44L;
	public static final long HDR_HOST_HEARTBEAT = OFF_HEADER + 48L;
	public static final long HDR_MC_HEARTBEAT = OFF_HEADER + 56L;
	public static final long HDR_SEQUENCE = OFF_HEADER + 64L;
	public static final long HDR_TIMESTAMP = OFF_HEADER + 72L;

	// OverlayControl
	public static final long CTL_STATE = OFF_OVERLAY_CTL + 0L;             // uint32
	public static final long CTL_FRAMES_PUBLISHED = OFF_OVERLAY_CTL + 8L;  // uint64
	public static final long CTL_SEQUENCE = OFF_OVERLAY_CTL + 16L;         // uint64
	public static final long CTL_TIMESTAMP = OFF_OVERLAY_CTL + 24L;        // uint64
	public static final int OVERLAY_FRESH = 1 << 2;
	public static final int OVERLAY_INDEX_MASK = 0x3;

	public static final int FORMAT_BGRA8 = 1;
	public static final int OVERLAY_BOTTOM_UP = 1 << 0;

	// HostState field offsets (relative to OFF_HOST_STATE)
	public static final int HOST_SEQ = 0;
	public static final int HOST_FLAGS = 4;
	public static final int HOST_WORLD_ID = 8;
	public static final int HOST_COLLISION_EPOCH = 12;
	public static final int HOST_TIMESTAMP = 16;      // uint64
	public static final int HOST_POS_X = 24;
	public static final int HOST_POS_Y = 32;
	public static final int HOST_POS_Z = 40;
	public static final int HOST_YAW = 48;
	public static final int HOST_PITCH = 52;
	public static final int HOST_ROLL = 56;
	public static final int HOST_EYE_HEIGHT = 60;
	public static final int HOST_UNITS_PER_BLOCK = 64;
	public static final int HOST_TELEPORT_SEQ = 68;
	public static final int HOST_CAMERA_MODE = 72;
	public static final int HOST_VIEWPORT_W = 76;
	public static final int HOST_VIEWPORT_H = 80;

	// McState field offsets (relative to OFF_MC_STATE)
	public static final int MC_SEQ = 0;
	public static final int MC_FLAGS = 4;
	public static final int MC_TIMESTAMP = 8;         // uint64
	public static final int MC_X = 16;
	public static final int MC_Y = 24;
	public static final int MC_Z = 32;
	public static final int MC_PREV_X = 40;
	public static final int MC_PREV_Y = 48;
	public static final int MC_PREV_Z = 56;
	public static final int MC_CUR_X = 64;
	public static final int MC_CUR_Y = 72;
	public static final int MC_CUR_Z = 80;
	public static final int MC_YAW = 88;
	public static final int MC_PITCH = 92;
	public static final int MC_EYE_HEIGHT = 96;
	public static final int MC_FOV_DEG = 100;
	public static final int MC_TICK_MS = 104;
	public static final int MC_CAMERA_MODE = 108;
	public static final int MC_CAMERA_DISTANCE = 112;
	public static final int MC_FRAME_COUNTER = 120;   // uint64
	public static final int MC_TICK_QPC = 128;        // int64

	/** Byte offset of slot {@code i}'s 0x40-byte header. */
	public static long slotHdr(int i) {
		return OFF_OVERLAY_SLOTS + (long) i * OVERLAY_SLOT_SIZE;
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
	 * The configuration file that will be used, or {@code null} if config comes from the bundled
	 * classpath resource / the built-in default. Search order: {@code -Dcrossmc.config} /
	 * {@code CROSSMC_CONFIG}, then {@code ./config/crossmc.properties}, then
	 * {@code %LOCALAPPDATA%/CrossMC/crossmc.properties}.
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

	/** A human-readable description of where config comes from (file, resource or default). */
	public static String configSource() {
		Path file = configFile();

		if (file != null) {
			return file.toString();
		}

		if (Protocol.class.getResource(CONFIG_RESOURCE) != null) {
			return "classpath:" + CONFIG_RESOURCE;
		}

		return "built-in default";
	}

	private static String configValue(String key) {
		Properties props = loadConfig();
		return props == null ? null : props.getProperty(key);
	}

	private static Properties loadConfig() {
		Path file = configFile();

		if (file != null) {
			try (InputStream in = Files.newInputStream(file)) {
				Properties props = new Properties();
				props.load(in);
				return props;
			} catch (IOException e) {
				System.err.println("[CrossMC] failed to read config " + file + ": " + e);
				return null;
			}
		}

		// Bundled default (the mod jar ships config/crossmc.properties as /crossmc.properties).
		try (InputStream in = Protocol.class.getResourceAsStream(CONFIG_RESOURCE)) {
			if (in == null) {
				return null;
			}

			Properties props = new Properties();
			props.load(in);
			return props;
		} catch (IOException e) {
			System.err.println("[CrossMC] failed to read bundled config: " + e);
			return null;
		}
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
