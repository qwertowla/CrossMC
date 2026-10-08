package dev.crossmc.minecraft.mixin;

import dev.crossmc.minecraft.CrossMcMinecraft;
import dev.crossmc.minecraft.HostCollisionManager;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes a CrossMC phantom (the invisible host-collision barrier injected through
 * {@code World.getBlockState}) behave like a normal <b>non-replaceable solid block</b> for block
 * placement: the block targets the cell just outside the hit face, never the phantom cell itself.
 *
 * <p>Why this is needed and cannot be fixed in {@code getBlockState} alone: the phantom exists only
 * in the client world. The client sends its {@link BlockHitResult} to the (integrated) server, which
 * sees plain air at the phantom cell and would therefore place the block <i>into</i> that cell.
 * Retargeting the hit to {@code hitPos.offset(side)} at the client interaction makes the client and
 * the server agree, and keeps all vanilla placement rules (replaceability, clicked face, sneaking,
 * direction handling) — only the phantom hit is translated.
 *
 * <p>Normal Minecraft blocks are untouched: only {@link HostCollisionManager#isPhantom(BlockPos)}
 * hits are retargeted.
 */
@Mixin(ClientPlayerInteractionManager.class)
public abstract class BlockPlacementPhantomMixin {
	@Inject(method = "interactBlock", at = @At("HEAD"), cancellable = true)
	private void crossmc$retargetPhantomPlacement(ClientPlayerEntity player, Hand hand,
			BlockHitResult hitResult, CallbackInfoReturnable<ActionResult> cir) {
		if (!HostCollisionManager.enabled()) {
			return;
		}

		HostCollisionManager manager = HostCollisionManager.get();
		BlockPos hitPos = hitResult.getBlockPos();

		if (!manager.isPhantom(hitPos)) {
			return;
		}

		// Target the cell just outside the hit face (skip outward through a thick phantom slab).
		BlockPos target = hitPos.offset(hitResult.getSide());

		for (int i = 0; i < 16 && manager.isPhantom(target); i++) {
			target = target.offset(hitResult.getSide());
		}

		BlockHitResult retargeted = hitResult.withBlockPos(target);
		CrossMcMinecraft.LOGGER.debug("CrossMC placement: phantom hit pos={} side={} -> target={}",
				hitPos, hitResult.getSide(), target);
		cir.setReturnValue(((ClientPlayerInteractionManager) (Object) this).interactBlock(player, hand, retargeted));
	}
}
