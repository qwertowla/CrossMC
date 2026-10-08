package dev.crossmc.minecraft.mixin;

import dev.crossmc.minecraft.HostCollisionManager;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Injects host collision proxies into Minecraft's native block reads.
 *
 * <p>{@code World.getBlockState} is the single point used by native collision
 * ({@code CollisionView.getBlockCollisions}), raycast ({@code BlockView.raycast}) and block
 * placement. Returning a solid, invisible {@code barrier} state for host-occupied cells lets all
 * of those systems treat host space like an ordinary block, without touching Minecraft's rules.
 *
 * <p>Client only (guarded by {@code instanceof ClientWorld}); real blocks are never hidden.
 */
@Mixin(World.class)
public abstract class WorldBlockStateMixin {
	private static final ThreadLocal<Boolean> CROSSMC_IN_READ = ThreadLocal.withInitial(() -> Boolean.FALSE);

	@Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
	private void crossmc$hostCollisionProxy(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
		if (!HostCollisionManager.enabled() || !((Object) this instanceof ClientWorld)) {
			return;
		}

		if (!HostCollisionManager.get().isPhantom(pos)) {
			return;
		}

		if (CROSSMC_IN_READ.get()) {
			return;
		}

		// Never hide an existing block; only fill cells that are actually air.
		BlockState real;
		CROSSMC_IN_READ.set(Boolean.TRUE);

		try {
			real = ((World) (Object) this).getBlockState(pos);
		} finally {
			CROSSMC_IN_READ.set(Boolean.FALSE);
		}

		if (!real.isAir()) {
			return;
		}

		cir.setReturnValue(Blocks.BARRIER.getDefaultState());
	}
}
