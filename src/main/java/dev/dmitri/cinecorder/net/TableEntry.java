package dev.dmitri.cinecorder.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/** One row of the animation table GUI. */
public record TableEntry(String name, String title, int ticks, boolean loop, boolean ready) {
	public static final StreamCodec<FriendlyByteBuf, TableEntry> CODEC = StreamCodec.of(
			(buf, entry) -> {
				buf.writeUtf(entry.name, 128);
				buf.writeUtf(entry.title, 160);
				buf.writeVarInt(entry.ticks);
				buf.writeBoolean(entry.loop);
				buf.writeBoolean(entry.ready);
			},
			buf -> new TableEntry(
					buf.readUtf(128),
					buf.readUtf(160),
					buf.readVarInt(),
					buf.readBoolean(),
					buf.readBoolean()));

	public double seconds() {
		return ticks < 0 ? 0.0 : ticks / 20.0;
	}
}
