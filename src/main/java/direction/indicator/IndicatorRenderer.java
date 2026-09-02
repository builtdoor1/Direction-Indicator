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
 * {@link DirectionIndicatorClient#isRelevant} accepts. The colour is decided in
 * {@link DirectionIndicatorClient}; this class only decides where the quad goes.
 */
public final class IndicatorRenderer {

	/** Bar size in blocks. A player is 0.6 wide, so this sits just inside their silhouette. */
	private static final double WIDTH = 0.55;
	private static final double HEIGHT = 0.11;

	/** How far the dark backdrop extends past the coloured fill on every side. */
	private static final double BORDER = 0.022;

	/**
	 * Height above the top of the player's hitbox. Vanilla puts the nametag at
	 * {@code bbHeight + 0.5}, so this tucks the bar into the gap below it.
	 */
	private static final double HEAD_CLEARANCE = 0.30;

	private static final int FILL_ALPHA = 235;
	private static final int BACKDROP_ALPHA = 140;

	private IndicatorRenderer() {
	}

	/** Registered as a {@code WorldRenderEvents.BEFORE_DEBUG_RENDER} callback. */
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

		Camera camera = mc.gameRenderer.getMainCamera();
		Vec3 camPos = camera.position();
		Vec3 up = vec(camera.upVector());
		Vec3 right = vec(camera.leftVector()).scale(-1.0);

		float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		Matrix4f matrix = poseStack.last().pose();

		// debugQuads() is POSITION_COLOR quads, translucent, with no depth write and no
		// back-face culling. That means the backdrop and the fill can share a plane and
		// simply draw in submission order, and the winding of each quad doesn't matter.
		VertexConsumer buffer = consumers.getBuffer(RenderTypes.debugQuads());

		for (AbstractClientPlayer player : level.players()) {
			if (!DirectionIndicatorClient.isRelevant(self, player)) {
				continue;
			}

			// World rendering places the camera at the origin, so work camera-relative.
			double x = Mth.lerp(partialTick, player.xo, player.getX());
			double y = Mth.lerp(partialTick, player.yo, player.getY());
			double z = Mth.lerp(partialTick, player.zo, player.getZ());
			Vec3 center = new Vec3(x, y + player.getBbHeight() + HEAD_CLEARANCE, z).subtract(camPos);

			quad(buffer, matrix, center, right, up,
					WIDTH / 2.0 + BORDER, HEIGHT / 2.0 + BORDER, 0x000000, BACKDROP_ALPHA);
			quad(buffer, matrix, center, right, up,
					WIDTH / 2.0, HEIGHT / 2.0, DirectionIndicatorClient.indicatorColor(player), FILL_ALPHA);
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
