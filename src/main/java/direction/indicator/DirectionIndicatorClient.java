package direction.indicator;

import java.util.HashMap;
import java.util.Map;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * Client-side movement cues for nearby players:
 *
 * <ol>
 *   <li>a sound the moment another player starts a jump;</li>
 *   <li>a flat, camera-facing bar above their head coloured by whether they are moving
 *       forward, backward, or not meaningfully moving.</li>
 * </ol>
 *
 * Both features share {@link DirectionIndicatorConfig#radius}. All per-player bookkeeping happens
 * once per client tick in here, so {@link IndicatorRenderer} stays a pure drawing pass.
 */
public final class DirectionIndicatorClient implements ClientModInitializer {

	/** Placeholder jump cue. Any plain {@code SoundEvent} constant can go here. */
	private static final SoundEvent JUMP_SOUND = SoundEvents.EXPERIENCE_ORB_PICKUP;

	/** Exponential smoothing on forward speed, so a dropped position packet can't flicker the colour. */
	private static final double SMOOTHING = 0.45;

	/** Horizontal steps larger than this (squared, blocks per tick) are teleports, not movement. */
	private static final double TELEPORT_SQR = 16.0;

	/**
	 * A jump is "left the ground, then gained height". Remote players arrive interpolated over a few
	 * ticks, so the height gain is given a short window to show up before the candidate is discarded
	 * as a step off a ledge.
	 */
	private static final double JUMP_MIN_RISE = 0.1;
	private static final int JUMP_CONFIRM_TICKS = 4;

	/** Ticks after a jump before another can fire. A jump arc is ~12 ticks, so this only eats flicker. */
	private static final int JUMP_COOLDOWN_TICKS = 6;

	/** Per-player state, keyed by entity id. */
	private static final class Tracked {
		double lastX;
		double lastY;
		double lastZ;
		boolean onGround;
		/** Smoothed speed along the player's facing, blocks per tick. Positive is forward. */
		double forwardSpeed;
		/** Ticks left to confirm a candidate jump; 0 when nothing is pending. */
		int confirmTicks;
		double takeoffY;
		int cooldown;
		int seenTick;
	}

	private static final Map<Integer, Tracked> TRACKED = new HashMap<>();
	private static int tickCounter;

	@Override
	public void onInitializeClient() {
		// Create the config file up front so it exists before Mod Menu can open it.
		DirectionIndicatorConfig.get();

		ClientTickEvents.END_CLIENT_TICK.register(DirectionIndicatorClient::onEndTick);

		// END_MAIN, not BEFORE_DEBUG_RENDER. In fabric-rendering-v1 16.2.x BEFORE_DEBUG_RENDER is the
		// one "drawing" event injected into the extraction region of renderLevel, before the frame
		// graph is even built - so it hands out the previous frame's PoseStack and opens a batch
		// outside any executing pass. END_MAIN fires inside the main pass, immediately before the
		// world renderer's own endBatch(), which is what Fabric's own docs recommend for content that
		// must not be overdrawn or cleared.
		WorldRenderEvents.END_MAIN.register(IndicatorRenderer::render);
	}

	private static void onEndTick(Minecraft mc) {
		ClientLevel level = mc.level;
		LocalPlayer self = mc.player;
		if (level == null || self == null) {
			TRACKED.clear();
			return;
		}

		tickCounter++;
		for (AbstractClientPlayer player : level.players()) {
			track(level, self, player);
		}

		// Anything not touched this tick has left the world; drop it so a returning player
		// gets a fresh baseline rather than a stale one.
		TRACKED.values().removeIf(t -> t.seenTick != tickCounter);
	}

	private static void track(ClientLevel level, LocalPlayer self, AbstractClientPlayer player) {
		DirectionIndicatorConfig config = DirectionIndicatorConfig.get();
		boolean onGround = player.onGround();
		double x = player.getX();
		double y = player.getY();
		double z = player.getZ();

		Tracked t = TRACKED.get(player.getId());
		if (t == null) {
			// First sight (join, respawn, walked back into range): seed the baseline so the
			// first comparison can't manufacture a jump or a burst of movement.
			t = new Tracked();
			t.onGround = onGround;
			t.lastX = x;
			t.lastY = y;
			t.lastZ = z;
			t.seenTick = tickCounter;
			TRACKED.put(player.getId(), t);
			return;
		}
		t.seenTick = tickCounter;

		// --- Which way are they moving, relative to where they are looking? ---
		// Remote players never report usable getDeltaMovement(), so measure the position delta.
		double dx = x - t.lastX;
		double dz = z - t.lastZ;
		if (dx * dx + dz * dz > TELEPORT_SQR) {
			dx = 0.0;
			dz = 0.0;
		}
		double yaw = Math.toRadians(player.getYRot());
		double forwardNow = dx * -Math.sin(yaw) + dz * Math.cos(yaw);
		t.forwardSpeed += (forwardNow - t.forwardSpeed) * SMOOTHING;

		// --- Did they just start a jump? ---
		if (t.cooldown > 0) {
			t.cooldown--;
		}

		if (onGround) {
			t.confirmTicks = 0;
		} else if (t.onGround) {
			// Left the ground this tick. Could be a jump, could be walking off an edge.
			t.confirmTicks = JUMP_CONFIRM_TICKS;
			t.takeoffY = t.lastY;
		}

		if (t.confirmTicks > 0) {
			if (y - t.takeoffY >= JUMP_MIN_RISE) {
				// Gained height while airborne, so it was a jump.
				if (t.cooldown == 0 && config.jumpSoundEnabled && inRange(self, player)
						&& (player != self || config.jumpSoundForSelf)) {
					level.playLocalSound(x, y, z, JUMP_SOUND, SoundSource.PLAYERS,
							config.jumpSoundVolume / 100.0F, config.jumpSoundPitch / 100.0F, false);
				}
				t.cooldown = JUMP_COOLDOWN_TICKS;
				t.confirmTicks = 0;
			} else {
				t.confirmTicks--;
			}
		}

		t.onGround = onGround;
		t.lastX = x;
		t.lastY = y;
		t.lastZ = z;
	}

	/** Close enough, and alive enough, to be worth a cue at all. */
	private static boolean inRange(LocalPlayer self, AbstractClientPlayer player) {
		if (player.isSpectator() || !player.isAlive()) {
			return false;
		}
		int radius = DirectionIndicatorConfig.get().radius;
		return self.distanceToSqr(player) <= (double) radius * radius;
	}

	/** Whether this player should get a direction bar drawn above their head. */
	public static boolean shouldDrawIndicator(LocalPlayer self, AbstractClientPlayer player) {
		DirectionIndicatorConfig config = DirectionIndicatorConfig.get();
		if (!config.indicatorEnabled) {
			return false;
		}
		if (player == self && !config.indicatorForSelf) {
			return false;
		}
		return inRange(self, player);
	}

	/** Indicator colour (0xRRGGBB) for a player. Untracked players read as idle. */
	public static int indicatorColor(AbstractClientPlayer player) {
		DirectionIndicatorConfig config = DirectionIndicatorConfig.get();
		Tracked t = TRACKED.get(player.getId());
		if (t == null || Math.abs(t.forwardSpeed) < config.moveThreshold / 1000.0) {
			return config.colorIdle;
		}
		return t.forwardSpeed > 0.0 ? config.colorForward : config.colorBackward;
	}
}
