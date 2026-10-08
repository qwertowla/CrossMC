package dev.crossmc.minecraft.mixin;

import net.minecraft.client.texture.NativeImage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes {@link NativeImage}'s raw off-heap buffer so the CrossMC host-frame channel can write a
 * decoded frame straight into a Minecraft dynamic texture (created once, updated each frame) without
 * per-pixel {@code setColor} calls or a texture re-creation.
 */
@Mixin(NativeImage.class)
public interface NativeImageAccessor {
	@Accessor("pointer")
	long crossmc$getPointer();

	@Accessor("sizeBytes")
	long crossmc$getSizeBytes();
}
