package dev.dmitri.cinecorder.item;

import java.util.Optional;

import dev.dmitri.cinecorder.anim.AnimationClip;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * Reads and writes the animation reference stored on a cassette.
 *
 * <p>Cassettes no longer embed the animation JSON: NBT strings are limited to 65535 bytes, and
 * real animations are much larger. The item stores a content hash plus the source name; the
 * server resolves the JSON from {@code config/cinecorder/cassettes} (falling back to the
 * animations folder) and streams it to clients on demand.</p>
 */
public final class CassetteData {
	private static final String ROOT = "CineCorder";
	private static final String ID = "Id";
	private static final String SOURCE = "Source";
	private static final String TITLE = "Title";
	private static final String TICKS = "Ticks";
	private static final String LOOP = "Loop";
	private static final String LEGACY_DATA = "Data";
	private static final int MAX_LEGACY_INLINE = 60000;

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
		String id = tag.getStringOr(ID, "");
		String source = tag.getStringOr(SOURCE, "");
		String inline = tag.getStringOr(LEGACY_DATA, "");
		if (inline.length() > MAX_LEGACY_INLINE) {
			inline = "";
		}
		if (id.isEmpty() && inline.isEmpty()) {
			return Optional.empty();
		}

		return Optional.of(new Content(
				id,
				source,
				tag.getStringOr(TITLE, "Untitled"),
				Math.max(1, tag.getIntOr(TICKS, 20)),
				tag.getBooleanOr(LOOP, false),
				inline));
	}

	public static void write(ItemStack stack, String id, String source, AnimationClip clip) {
		CompoundTag root = new CompoundTag();
		root.putString(ID, id);
		root.putString(SOURCE, source);
		root.putString(TITLE, clip.name());
		root.putInt(TICKS, clip.durationTicks());
		root.putBoolean(LOOP, clip.loop());

		CompoundTag outer = new CompoundTag();
		outer.put(ROOT, root);
		CustomData.set(DataComponents.CUSTOM_DATA, stack, outer);
	}

	public static void clear(ItemStack stack) {
		CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.remove(ROOT));
	}

	/** id/source identify server-side animation data; inlineJson is only for legacy cassettes. */
	public record Content(String id, String source, String title, int ticks, boolean loop, String inlineJson) {
		public boolean hasInline() {
			return this.inlineJson != null && !this.inlineJson.isEmpty();
		}

		public String cacheKey() {
			return this.id.isEmpty() ? "inline:" + this.title + ":" + this.ticks : this.id;
		}
	}
}
