package dev.dmitri.cinecorder.item;

import java.util.Optional;

import dev.dmitri.cinecorder.anim.AnimationClip;
import dev.dmitri.cinecorder.anim.AnimationException;
import dev.dmitri.cinecorder.anim.AnimationParser;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * Reads and writes the animation payload stored on a cassette.
 *
 * <p>The payload lives in the vanilla {@code minecraft:custom_data} component so it is
 * saved with the world and synchronised to clients automatically.</p>
 */
public final class CassetteData {
	private static final String ROOT = "CineCorder";
	private static final String TITLE = "Title";
	private static final String DATA = "Data";
	private static final String TICKS = "Ticks";
	private static final String LOOP = "Loop";

	private CassetteData() {
	}

	public static Optional<Content> read(ItemStack stack) {
		if (stack.isEmpty()) {
			return Optional.empty();
		}

		CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
		if (customData == null) {
			return Optional.empty();
		}

		CompoundTag tag = customData.copyTag().getCompoundOrEmpty(ROOT);
		String data = tag.getStringOr(DATA, "");
		if (data.isEmpty()) {
			return Optional.empty();
		}

		return Optional.of(new Content(
				tag.getStringOr(TITLE, "Untitled"),
				data,
				Math.max(1, tag.getIntOr(TICKS, 20)),
				tag.getBooleanOr(LOOP, false)));
	}

	public static void write(ItemStack stack, AnimationClip clip, String rawJson) {
		CompoundTag root = new CompoundTag();
		root.putString(TITLE, clip.name());
		root.putString(DATA, rawJson);
		root.putInt(TICKS, clip.durationTicks());
		root.putBoolean(LOOP, clip.loop());

		CompoundTag outer = new CompoundTag();
		outer.put(ROOT, root);
		CustomData.set(DataComponents.CUSTOM_DATA, stack, outer);
	}

	public static void clear(ItemStack stack) {
		CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.remove(ROOT));
	}

	public record Content(String title, String json, int ticks, boolean loop) {
		public AnimationClip parse() throws AnimationException {
			return AnimationParser.parse(json, title);
		}
	}
}
