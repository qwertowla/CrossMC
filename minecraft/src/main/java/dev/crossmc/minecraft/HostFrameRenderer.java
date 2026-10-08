package dev.crossmc.minecraft;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

/**
 * Draws the newest host frame as a textured, unlit quad in <b>Minecraft world space</b>.
 *
 * <p>Not a HUD/overlay: it is an ordinary world object (occluded by terrain, walked around, viewed
 * from any angle). The texture is the {@link HostFrameConsumer} dynamic texture. Lighting is forced
 * full-bright so the screen is unaffected by Minecraft's lighting. No collision, no shadow.
 */
public final class HostFrameRenderer {
	private HostFrameRenderer() {
	}

	public static void register() {
		WorldRenderEvents.AFTER_ENTITIES.register(HostFrameRenderer::render);
	}

	private static void render(WorldRenderContext context) {
		if (!ClientRenderConfig.worldEnabled) {
			return;
		}

		if (!HostFrameConsumer.hasFrame()) {
			return;
		}

		Identifier texture = HostFrameConsumer.textureId();

		if (texture == null) {
			return;
		}

		VertexConsumerProvider consumers = context.consumers();
		MatrixStack matrices = context.matrixStack();
		Camera camera = context.camera();

		if (consumers == null || matrices == null || camera == null) {
			return;
		}

		double cx = ClientRenderConfig.x;
		double cy = ClientRenderConfig.y;
		double cz = ClientRenderConfig.z;
		float halfW = ClientRenderConfig.width * 0.5f;
		float halfH = ClientRenderConfig.height * 0.5f;

		Vec3d normal;
		Vec3d right;

		ClientPlayerEntity player = MinecraftClient.getInstance().player;

		if (ClientRenderConfig.facePlayer && player != null) {
			double dx = player.getX() - cx;
			double dz = player.getZ() - cz;
			double len = Math.sqrt(dx * dx + dz * dz);

			if (len < 1.0e-4) {
				dx = 0;
				dz = 1;
				len = 1;
			}

			normal = new Vec3d(dx / len, 0, dz / len);
			right = new Vec3d(normal.z, 0, -normal.x);
		} else {
			normal = new Vec3d(0, 0, 1);
			right = new Vec3d(1, 0, 0);
		}

		Vec3d bottomLeft = new Vec3d(cx - right.x * halfW, cy - halfH, cz - right.z * halfW);
		Vec3d bottomRight = new Vec3d(cx + right.x * halfW, cy - halfH, cz + right.z * halfW);
		Vec3d topRight = new Vec3d(cx + right.x * halfW, cy + halfH, cz + right.z * halfW);
		Vec3d topLeft = new Vec3d(cx - right.x * halfW, cy + halfH, cz - right.z * halfW);

		Vec3d camPos = camera.getPos();
		matrices.push();
		matrices.translate(-camPos.x, -camPos.y, -camPos.z);
		Matrix4f matrix = matrices.peek().getPositionMatrix();
		VertexConsumer vertices = consumers.getBuffer(RenderLayer.getEntityCutoutNoCull(texture));

		vertex(vertices, matrix, bottomLeft, 0f, 1f, normal);
		vertex(vertices, matrix, bottomRight, 1f, 1f, normal);
		vertex(vertices, matrix, topRight, 1f, 0f, normal);
		vertex(vertices, matrix, topLeft, 0f, 0f, normal);

		matrices.pop();
	}

	private static void vertex(VertexConsumer vertices, Matrix4f matrix, Vec3d p, float u, float v, Vec3d n) {
		vertices.vertex(matrix, (float) p.x, (float) p.y, (float) p.z)
				.color(255, 255, 255, 255)
				.texture(u, v)
				.overlay(OverlayTexture.DEFAULT_UV)
				.light(LightmapTextureManager.MAX_LIGHT_COORDINATE)
				.normal((float) n.x, (float) n.y, (float) n.z);
	}
}
