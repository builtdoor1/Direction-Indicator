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
 *   <li>a sound the moment a player starts a jump;</li>
 *   <li>a flat, camera-facing bar above their head coloured by whether they are moving
 *       forward, backward, or not meaningfully moving.</li>
 * </ol>
 *
 * Both features use the same {@link #RADIUS}. All per-player bookkeeping happens once per
 * client tick in here, so {@link IndicatorRenderer} stays a pure drawing pass.
 */
public final class DirectionIndicatorClient implements ClientModInitializer {

	// ------------------------------------------------------------------
	// Configuration. Everything worth tweaking lives in this block.
	// ------------------------------------------------------------------

	/** Players further than this many blocks away are ignored by both cues. */
	public static final double RADIUS = 24.0;

	/** Whether your own jumps and your own indicator count too. */
	public static final boolean INCLUDE_SELF = true;

	/** Placeholder jump cue. Any plain {@code SoundEvent} constant can go here. */
	private static final SoundEvent JUMP_SOUND = SoundEvents.EXPERIENCE_ORB_PICKUP;
	private static final float JUMP_VOLUME = 0.6F;
	private static final float JUMP_PITCH = 1.6F;

	/** Indicator colours, 0xRRGGBB. */
	public static final int COLOR_FORWARD = 0x4CD964;
	public static final int COLOR_BACKWARD = 0xE03131;
	public static final int COLOR_IDLE = 0xFFD43B;

	/**
	 * Speed along the player's facing, in blocks per tick, below which they read as "not
	 * meaningfully moving". Sneaking is about 0.065, walking 0.216, sprinting 0.28, so this
	 * only swallows drift. Pure strafing has almost no forward component and reads idle too.
	 */
	private static final double MOVE_THRESHOLD = 0.02;

	/** Exponential smoothing on that speed, so a single dropped position packet can't flicker the colour. */
	private static final double SMOOTHING = 0.45;

	/** Horizontal steps larger than this (squared, blocks per tick) are teleports, not movement. */
	private static final double TELEPORT_SQR = 16.0;

	/**
	 * A jump is "left the ground, then gained height". Remote players arrive interpolated
	 * over a few ticks, so the height gain is given this many ticks to show up before the
	 * candidate is discarded as a step off a ledge.
	 */
	private static final double JUMP_MIN_RISE = 0.1;
	private static final int JUMP_CONFIRM_TICKS = 4;

	/** Ticks after a jump before another can fire. A jump arc is ~12 ticks, so this only eats flicker. */
	private static final int JUMP_COOLDOWN_TICKS = 6;

	// ------------------------------------------------------------------

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
		ClientTickEvents.END_CLIENT_TICK.register(DirectionIndicatorClient::onEndTick);
		// Drawn with the debug-render pass, the phase Fabric intends for overlay content.
		WorldRenderEvents.BEFORE_DEBUG_RENDER.register(IndicatorRenderer::render);
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
				if (t.cooldown == 0 && isRelevant(self, player)) {
					level.playLocalSound(x, y, z, JUMP_SOUND, SoundSource.PLAYERS, JUMP_VOLUME, JUMP_PITCH, false);
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

	/** Whether this player is close enough, and visible enough, to get either cue. */
	public static boolean isRelevant(LocalPlayer self, AbstractClientPlayer player) {
		if (player == self && !INCLUDE_SELF) {
			return false;
		}
		if (player.isSpectator() || !player.isAlive()) {
			return false;
		}
		return self.distanceToSqr(player) <= RADIUS * RADIUS;
	}

	/** Indicator colour (0xRRGGBB) for a player. Untracked players read as idle. */
	public static int indicatorColor(AbstractClientPlayer player) {
		Tracked t = TRACKED.get(player.getId());
		if (t == null || Math.abs(t.forwardSpeed) < MOVE_THRESHOLD) {
			return COLOR_IDLE;
		}
		return t.forwardSpeed > 0.0 ? COLOR_FORWARD : COLOR_BACKWARD;
	}
}
