package direction.indicator;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Mth;

/**
 * Settings, stored as JSON in the Fabric config directory and edited through the Mod Menu /
 * Cloth Config screen. Cloth writes straight into these public fields, so the renderer and the
 * tick handler pick changes up the instant Save is pressed.
 *
 * <p>Fields are deliberately NOT {@code static final}: javac inlines compile-time constants into
 * every call site, so a constant left behind as a "default" would silently keep the baked-in value.
 *
 * <p>Sub-block sizes are stored as integers in hundredths of a block, and the sound volume and
 * pitch in percent, because Cloth Config has int and long sliders but no double or float slider.
 */
public final class DirectionIndicatorConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("directionindicator.json");
	private static DirectionIndicatorConfig instance;

	// --- shared ---
	/** Players further than this many blocks away are ignored by both cues. */
	public int radius = 24;

	// --- jump sound ---
	public boolean jumpSoundEnabled = true;
	/** Off by default: the cue is for hearing other people jump, not yourself. */
	public boolean jumpSoundForSelf = false;
	/** Percent. */
	public int jumpSoundVolume = 60;
	/** Percent. */
	public int jumpSoundPitch = 160;

	// --- direction bar ---
	public boolean indicatorEnabled = true;
	/** Off by default. Turn on plus F5 to see your own bar, which is handy for checking the mod works. */
	public boolean indicatorForSelf = false;
	/** 0xRRGGBB. Cloth's colour field rejects an alpha byte, so these must stay 6 digits. */
	public int colorForward = 0x4CD964;
	public int colorBackward = 0xE03131;
	public int colorIdle = 0xFFD43B;
	/** Thousandths of a block per tick. Sneaking is ~65, walking ~216, sprinting ~280. */
	public int moveThreshold = 20;
	/** Hundredths of a block. */
	public int barWidth = 55;
	public int barHeight = 11;
	/** Hundredths of a block above the top of the player's hitbox. */
	public int headClearance = 30;
	/** 0-255. */
	public int fillOpacity = 235;
	public int backdropOpacity = 140;

	public static DirectionIndicatorConfig get() {
		if (instance == null) {
			instance = load();
		}
		return instance;
	}

	private static DirectionIndicatorConfig load() {
		if (Files.exists(PATH)) {
			try (Reader reader = Files.newBufferedReader(PATH)) {
				DirectionIndicatorConfig loaded = GSON.fromJson(reader, DirectionIndicatorConfig.class);
				if (loaded != null) {
					loaded.clamp();
					return loaded;
				}
			} catch (IOException | RuntimeException e) {
				// A corrupt or hand-edited file shouldn't stop the game from starting.
				System.err.println("[Direction Indicator] Could not read config, using defaults: " + e);
			}
		}
		DirectionIndicatorConfig fresh = new DirectionIndicatorConfig();
		fresh.save();
		return fresh;
	}

	public void save() {
		clamp();
		try {
			Files.createDirectories(PATH.getParent());
			try (Writer writer = Files.newBufferedWriter(PATH)) {
				GSON.toJson(this, writer);
			}
		} catch (IOException e) {
			System.err.println("[Direction Indicator] Could not write config: " + e);
		}
	}

	/** Slider bounds only validate typed input, so sanitise anything that came off disk. */
	public void clamp() {
		radius = Mth.clamp(radius, 1, 128);
		jumpSoundVolume = Mth.clamp(jumpSoundVolume, 0, 200);
		jumpSoundPitch = Mth.clamp(jumpSoundPitch, 50, 200);
		colorForward &= 0xFFFFFF;
		colorBackward &= 0xFFFFFF;
		colorIdle &= 0xFFFFFF;
		moveThreshold = Mth.clamp(moveThreshold, 0, 500);
		barWidth = Mth.clamp(barWidth, 5, 300);
		barHeight = Mth.clamp(barHeight, 2, 100);
		headClearance = Mth.clamp(headClearance, 0, 300);
		fillOpacity = Mth.clamp(fillOpacity, 0, 255);
		backdropOpacity = Mth.clamp(backdropOpacity, 0, 255);
	}
}
