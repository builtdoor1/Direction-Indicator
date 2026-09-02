package direction.indicator;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.network.chat.Component;

/**
 * The Mod Menu entry point and the Cloth Config screen.
 *
 * <p>Both Mod Menu and Cloth Config are optional: they are declared in "suggests", not "depends",
 * and nothing loads this class unless Mod Menu is installed and queries the entrypoint.
 *
 * <p>Cloth's save consumers write straight into the {@link DirectionIndicatorConfig} singleton, so
 * changes take effect the moment Save is pressed; the saving runnable only persists to disk.
 * Note that Cloth's colour field is 6-digit RGB and rejects an alpha byte, which is why opacity is
 * a separate slider, and that Cloth has no double slider, so sub-block sizes are int sliders in
 * hundredths of a block.
 */
public final class ModMenuIntegration implements ModMenuApi {

	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return parent -> {
			DirectionIndicatorConfig config = DirectionIndicatorConfig.get();

			ConfigBuilder builder = ConfigBuilder.create()
					.setParentScreen(parent)
					.setTitle(Component.literal("Direction Indicator"))
					.setSavingRunnable(config::save);

			ConfigEntryBuilder entry = builder.entryBuilder();

			ConfigCategory general = builder.getOrCreateCategory(Component.literal("General"));
			general.addEntry(entry.startIntSlider(Component.literal("Radius"), config.radius, 1, 64)
					.setDefaultValue(24)
					.setTooltip(Component.literal("Blocks. Used by both the jump sound and the bar."))
					.setSaveConsumer(v -> config.radius = v)
					.build());

			ConfigCategory sound = builder.getOrCreateCategory(Component.literal("Jump Sound"));
			sound.addEntry(entry.startBooleanToggle(Component.literal("Enabled"), config.jumpSoundEnabled)
					.setDefaultValue(true)
					.setSaveConsumer(v -> config.jumpSoundEnabled = v)
					.build());
			sound.addEntry(entry.startBooleanToggle(Component.literal("Play for your own jumps"), config.jumpSoundForSelf)
					.setDefaultValue(false)
					.setTooltip(Component.literal("Off by default, so you only hear other players jump."))
					.setSaveConsumer(v -> config.jumpSoundForSelf = v)
					.build());
			sound.addEntry(entry.startIntSlider(Component.literal("Volume"), config.jumpSoundVolume, 0, 200)
					.setDefaultValue(60)
					.setTextGetter(v -> Component.literal(v + "%"))
					.setSaveConsumer(v -> config.jumpSoundVolume = v)
					.build());
			sound.addEntry(entry.startIntSlider(Component.literal("Pitch"), config.jumpSoundPitch, 50, 200)
					.setDefaultValue(160)
					.setTextGetter(v -> Component.literal(v + "%"))
					.setSaveConsumer(v -> config.jumpSoundPitch = v)
					.build());

			ConfigCategory bar = builder.getOrCreateCategory(Component.literal("Direction Bar"));
			bar.addEntry(entry.startBooleanToggle(Component.literal("Enabled"), config.indicatorEnabled)
					.setDefaultValue(true)
					.setSaveConsumer(v -> config.indicatorEnabled = v)
					.build());
			bar.addEntry(entry.startBooleanToggle(Component.literal("Show above yourself"), config.indicatorForSelf)
					.setDefaultValue(false)
					.setTooltip(Component.literal("Only visible in third person (F5). Handy for checking the mod works."))
					.setSaveConsumer(v -> config.indicatorForSelf = v)
					.build());
			bar.addEntry(entry.startColorField(Component.literal("Moving forward"), config.colorForward)
					.setDefaultValue(0x4CD964)
					.setSaveConsumer(v -> config.colorForward = v & 0xFFFFFF)
					.build());
			bar.addEntry(entry.startColorField(Component.literal("Moving backward"), config.colorBackward)
					.setDefaultValue(0xE03131)
					.setSaveConsumer(v -> config.colorBackward = v & 0xFFFFFF)
					.build());
			bar.addEntry(entry.startColorField(Component.literal("Not moving"), config.colorIdle)
					.setDefaultValue(0xFFD43B)
					.setSaveConsumer(v -> config.colorIdle = v & 0xFFFFFF)
					.build());
			bar.addEntry(entry.startIntSlider(Component.literal("Movement threshold"), config.moveThreshold, 0, 300)
					.setDefaultValue(20)
					.setTextGetter(v -> Component.literal(v + " / 1000 blocks per tick"))
					.setTooltip(Component.literal("Below this the bar reads as idle. Sneaking is ~65, walking ~216, sprinting ~280."))
					.setSaveConsumer(v -> config.moveThreshold = v)
					.build());
			bar.addEntry(entry.startIntSlider(Component.literal("Width"), config.barWidth, 5, 200)
					.setDefaultValue(55)
					.setTextGetter(v -> Component.literal(v + " / 100 blocks"))
					.setSaveConsumer(v -> config.barWidth = v)
					.build());
			bar.addEntry(entry.startIntSlider(Component.literal("Height"), config.barHeight, 2, 100)
					.setDefaultValue(11)
					.setTextGetter(v -> Component.literal(v + " / 100 blocks"))
					.setSaveConsumer(v -> config.barHeight = v)
					.build());
			bar.addEntry(entry.startIntSlider(Component.literal("Height above head"), config.headClearance, 0, 200)
					.setDefaultValue(30)
					.setTextGetter(v -> Component.literal(v + " / 100 blocks"))
					.setSaveConsumer(v -> config.headClearance = v)
					.build());
			bar.addEntry(entry.startIntSlider(Component.literal("Fill opacity"), config.fillOpacity, 0, 255)
					.setDefaultValue(235)
					.setSaveConsumer(v -> config.fillOpacity = v)
					.build());
			bar.addEntry(entry.startIntSlider(Component.literal("Backdrop opacity"), config.backdropOpacity, 0, 255)
					.setDefaultValue(140)
					.setSaveConsumer(v -> config.backdropOpacity = v)
					.build());

			return builder.build();
		};
	}
}
