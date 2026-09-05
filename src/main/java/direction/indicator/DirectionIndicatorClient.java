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
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;

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

	/**
	 * The bundled jump cue, {@code assets/directionindicator/sounds/jump_cue.ogg}, declared in
	 * {@code sounds.json}. It deliberately isn't added to {@code BuiltInRegistries.SOUND_EVENT}:
	 * a sound played locally is resolved by {@code SoundManager.getSoundEvent(location)} straight
	 * from sounds.json, and the registry only matters for sounds the server asks for by id.
	 *
	 * <p>The file is mono on purpose. Minecraft only applies 3D positional attenuation to mono
	 * sounds, so a stereo cue would play at flat volume no matter where the other player is,
	 * which would lose the direction the cue exists to convey.
	 */
	private static final SoundEvent JUMP_SOUND = SoundEvent.createVariableRangeEvent(
			Identifier.fromNamespaceAndPath("directionindicator", "jump_cue"));

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
		/** Previous tick's hitbox height, so a pose change can be interpolated instead of snapping. */
		double lastHeight;
		boolean onGround;
		/** Smoothed speed along the player's facing, blocks per tick. Positive is forward. */
		double forwardSpeed;
		/** Ticks left to confirm a candidate jump; 0 when nothing is pending. */
		int confirmTicks;
		double takeoffY;
		int cooldown;
		/** Ticks left during which a launch reads as knockback rather than a jump. */
		int knockbackTicks;
		int prevHurtTime;
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
			t.prevHurtTime = player.hurtTime;
			t.lastHeight = player.getBbHeight();
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

		// --- Was a launch caused by a hit rather than a jump? ---
		// Knockback launches a grounded victim at 0.4 against a jump's 0.42, which interpolation makes
		// indistinguishable, so this keys off the hurt animation instead: LivingEntity.handleDamageEvent
		// sets hurtTime on every tracking client and it ticks down from there. That covers zero-damage
		// projectiles too, though launches that deal no damage at all - wind charges, fishing rods -
		// stay invisible to it and still ping.
		//
		// Two things stop this from silencing every jump in a fight. Only a hit landing on a GROUNDED
		// player can launch them, so nothing is armed while they are already airborne. And landing
		// clears it: every real jump after a hit has a landing in between, a knockback launch never
		// does, which is why the window length barely matters.
		//
		// A hit is "fresh" on the tick it lands, normally a rising edge in hurtTime. Servers running
		// 1.8-style combat land hits faster than it decays and pin it at hurtDuration, so treat that
		// as fresh too or the edge would never fire there. Both tests need hurtTime > 0: hurtDuration
		// is also 0 on a player who has never been hit.
		boolean freshHit = player.hurtTime > 0
				&& (player.hurtTime > t.prevHurtTime || player.hurtTime >= player.hurtDuration);
		t.prevHurtTime = player.hurtTime;

		if (onGround && !t.onGround) {
			// Landed. Whatever this hit was going to launch, it already has. A jump from here is
			// their own, which is what keeps the window from silencing every jump in a fight.
			t.knockbackTicks = 0;
		}
		if (config.knockbackWindow > 0 && onGround && freshHit) {
			t.knockbackTicks = config.knockbackWindow;
		} else if (t.knockbackTicks > 0) {
			t.knockbackTicks--;
		}

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
				if (t.cooldown == 0 && t.knockbackTicks == 0 && config.jumpSoundEnabled
						&& inRange(self, player) && (player != self || config.jumpSoundForSelf)) {
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
		t.lastHeight = player.getBbHeight();
		t.lastX = x;
		t.lastY = y;
		t.lastZ = z;
	}

	/**
	 * The player's hitbox height, interpolated across the tick.
	 *
	 * <p>{@code getBbHeight()} is swapped wholesale the moment the synced pose changes, so a player
	 * toggling sneak drops it from 1.8 to 1.5 in one step. Anything positioned off it snaps 0.3
	 * blocks, several times a second while someone is sneak-spamming in a fight, even though the
	 * model's own crouch is animated smoothly.
	 */
	public static double interpolatedHeight(AbstractClientPlayer player, float partialTick) {
		double now = player.getBbHeight();
		Tracked t = TRACKED.get(player.getId());
		return t == null ? now : Mth.lerp(partialTick, t.lastHeight, now);
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
