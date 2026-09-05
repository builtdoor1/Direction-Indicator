package direction.indicator;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3fc;

/**
 * Draws one flat, camera-facing bar above the head of every player
 * {@link DirectionIndicatorClient#shouldDrawIndicator} accepts. The colour is decided in
 * {@link DirectionIndicatorClient}; this class only decides where the quad goes.
 *
 * <p>Registered on {@code WorldRenderEvents.END_MAIN}, which fires inside the main render pass
 * immediately before the world renderer's own {@code endBatch()}.
 */
public final class IndicatorRenderer {

	/** How far the dark backdrop extends past the coloured fill on every side, in blocks. */
	private static final double BORDER = 0.022;

	/**
	 * How far the coloured fill is pulled toward the camera, in blocks, to force its draw order.
	 *
	 * <p>{@code debugQuads()} is declared {@code sortOnUpload()}, so {@code endBatch} re-sorts the
	 * whole batch back-to-front by quad centroid every frame. The backdrop and the fill are coplanar
	 * and their centroids are derived the same way, making the sort key an exact tie - which float
	 * rounding then breaks arbitrarily. Because the coordinates are camera-relative the rounding
	 * changes every frame that anything moves, so on a fraction of frames the black backdrop sorted
	 * last and composited over the fill, darkening the bar. That read as flicker, and only while
	 * moving. Two millimetres of parallax is invisible but is orders of magnitude above the rounding
	 * noise, so the ordering is decided by a real margin. Depth write is off and the fill only moves
	 * nearer, so the depth test is unaffected.
	 */
	private static final double FILL_SORT_BIAS = 0.002;

	private IndicatorRenderer() {
	}

	/** Registered as a {@code WorldRenderEvents.END_MAIN} callback. */
	public static void render(WorldRenderContext context) {
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		LocalPlayer self = mc.player;
		if (level == null || self == null || mc.options.hideGui) {
			return;
		}

		MultiBufferSource consumers = context.consumers();
		PoseStack poseStack = context.matrices();
		if (consumers == null || poseStack == null) {
			return;
		}

		DirectionIndicatorConfig config = DirectionIndicatorConfig.get();
		Camera camera = mc.gameRenderer.getMainCamera();
		Vec3 camPos = camera.position();
		Vec3 up = vec(camera.upVector());
		Vec3 right = vec(camera.leftVector()).scale(-1.0);

		double halfWidth = config.barWidth / 200.0;
		double halfHeight = config.barHeight / 200.0;
		double clearance = config.headClearance / 100.0;

		// true matches what vanilla resolves to for a Player: the frozen-tick flag never applies to them.
		float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
		Matrix4f matrix = poseStack.last().pose();

		// debugQuads() is POSITION_COLOR quads, translucent, with no depth write and no back-face
		// culling. That means the backdrop and the fill can share a plane and simply draw in
		// submission order, and the winding of each quad doesn't matter.
		VertexConsumer buffer = consumers.getBuffer(RenderTypes.debugQuads());

		for (AbstractClientPlayer player : level.players()) {
			if (!DirectionIndicatorClient.shouldDrawIndicator(self, player)) {
				continue;
			}
			// Your own bar sits directly above the first-person camera, where it would be a
			// degenerate sliver on the near plane. Only draw it when the camera is detached (F5).
			if (player == self && !camera.isDetached()) {
				continue;
			}

			// World rendering places the camera at the origin, so work camera-relative.
			double x = Mth.lerp(partialTick, player.xo, player.getX());
			double y = Mth.lerp(partialTick, player.yo, player.getY());
			double z = Mth.lerp(partialTick, player.zo, player.getZ());
			double height = DirectionIndicatorClient.interpolatedHeight(player, partialTick);
			Vec3 center = new Vec3(x, y + height + clearance, z).subtract(camPos);

			// The camera sits at the origin here, so "toward the camera" is simply toward the origin.
			Vec3 fillCenter = center.lengthSqr() > 1.0E-6
					? center.subtract(center.normalize().scale(FILL_SORT_BIAS))
					: center;

			quad(buffer, matrix, center, right, up,
					halfWidth + BORDER, halfHeight + BORDER, 0x000000, config.backdropOpacity);
			quad(buffer, matrix, fillCenter, right, up,
					halfWidth, halfHeight, DirectionIndicatorClient.indicatorColor(player), config.fillOpacity);
		}
	}

	private static void quad(VertexConsumer buffer, Matrix4f matrix, Vec3 center, Vec3 right, Vec3 up,
			double halfWidth, double halfHeight, int rgb, int alpha) {
		Vec3 h = right.scale(halfWidth);
		Vec3 v = up.scale(halfHeight);
		int r = (rgb >> 16) & 0xFF;
		int g = (rgb >> 8) & 0xFF;
		int b = rgb & 0xFF;

		vertex(buffer, matrix, center.subtract(h).subtract(v), r, g, b, alpha);
		vertex(buffer, matrix, center.add(h).subtract(v), r, g, b, alpha);
		vertex(buffer, matrix, center.add(h).add(v), r, g, b, alpha);
		vertex(buffer, matrix, center.subtract(h).add(v), r, g, b, alpha);
	}

	private static void vertex(VertexConsumer buffer, Matrix4f matrix, Vec3 pos, int r, int g, int b, int a) {
		buffer.addVertex(matrix, (float) pos.x, (float) pos.y, (float) pos.z).setColor(r, g, b, a);
	}

	private static Vec3 vec(Vector3fc v) {
		return new Vec3(v.x(), v.y(), v.z());
	}
}
