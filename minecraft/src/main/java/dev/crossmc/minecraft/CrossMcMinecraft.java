package dev.crossmc.minecraft;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common/server entry point.
 *
 * <p>Drives the host entity proxies and forwards Minecraft's native damage events to the host.
 * The client-side frame/collision/state work lives in {@link CrossMcMinecraftClient}.
 */
public class CrossMcMinecraft implements ModInitializer {
	public static final String MOD_ID = "crossmc";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		ServerTickEvents.END_SERVER_TICK.register(HostEntityManager::tick);
		ServerLivingEntityEvents.AFTER_DAMAGE.register(DamageBridge::onDamage);
		LOGGER.info("CrossMC server-side proxy/damage bridge ready");
	}
}
