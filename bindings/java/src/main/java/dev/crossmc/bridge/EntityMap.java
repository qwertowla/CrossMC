package dev.crossmc.bridge;

/**
 * Java mirror of {@code crossmc_entity_map} in {@code protocol/bridge_protocol.h}.
 *
 * <p>One row per host entity. {@link #hostEntityId} (FishNet {@code NetworkObject.ObjectId}) is
 * the stable key; Minecraft fills {@link #mcEntityId} for the bound proxy entity.
 */
public final class EntityMap {
	public int hostEntityId;
	public int mcEntityId;           // 0 = unbound
	public int kind;                 // Protocol.ENTITY_*
	public int flags;                // Protocol.ENTITY_*
	public float x, y, z;            // MC space feet
	public float yaw, pitch;
	public float health, maxHealth;
	public long updatedMs;

	public EntityMap() {
	}
}
