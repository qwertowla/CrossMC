package dev.crossmc.minecraft.mixin;

import dev.crossmc.minecraft.HostInputConsumer;
import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Feeds the host mouse delta into Minecraft's own mouse deltas, so vanilla applies its real
 * sensitivity / smoothing and calls {@code changeLookDirection} itself. This keeps Minecraft the
 * authority for the camera — CrossMC never sets yaw/pitch directly.
 */
@Mixin(Mouse.class)
public abstract class MouseMixin {
	@Shadow
	private double cursorDeltaX;

	@Shadow
	private double cursorDeltaY;

	@Inject(method = "updateMouse", at = @At("HEAD"))
	private void crossmc$applyHostMouse(double delta, CallbackInfo ci) {
		HostInputConsumer consumer = HostInputConsumer.get();
		this.cursorDeltaX += consumer.drainMouseDx();
		this.cursorDeltaY += consumer.drainMouseDy();
	}
}
