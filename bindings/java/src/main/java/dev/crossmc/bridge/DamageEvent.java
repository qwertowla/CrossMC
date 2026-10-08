package dev.crossmc.bridge;

/**
 * Java mirror of {@code crossmc_damage_event} in {@code protocol/bridge_protocol.h}.
 *
 * <p>Produced by Minecraft when a native damage event lands on a proxy entity, consumed by the
 * host adapter which decides the real effect (and applies its own multipliers).
 */
public final class DamageEvent {
	public int crossEntityId;        // victim CrossEntityId (primary key)
	public int mcEntityId;           // victim MC entity id (debug)
	public int sourceType;           // Protocol.DMG_*
	public int flags;                // Protocol.DMG_*
	public float amount;             // raw damage as applied by Minecraft
	public int attackerCrossId;      // 0 = none
	public float x, y, z;            // victim position (MC space)
	public float knockbackX, knockbackZ;
	public long sequence;
	public long timestampMs;

	public DamageEvent() {
	}
}
