package dev.crossmc.bridge;

/**
 * Java mirror of {@code crossmc_entity_map} in {@code protocol/bridge_protocol.h}.
 *
 * <p>One row per host entity. {@link #crossEntityId} is the stable CrossMC key; {@link #hostEntityId}
 * is the host's own id, and Minecraft fills {@link #mcEntityId} for the bound proxy entity.
 */
public final class EntityMap {
	public int hostEntityId;
	public int mcEntityId;           // 0 = unbound
	public int kind;                 // Protocol.ENTITY_*
	public int flags;                // Protocol.ENTITY_*
	public float x, y, z;            // MC space feet
	public float yaw, pitch;
	public float health, maxHealth;
	public int crossEntityId;        // STABLE CrossMC key (allocated by the host)
	public long updatedMs;

	public EntityMap() {
	}
}
