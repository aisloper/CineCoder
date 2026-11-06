package dev.cinecorder.net;

import java.util.ArrayList;
import java.util.List;

import dev.cinecorder.ModRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Sent to the client when it should open the animation table. */
public record OpenTablePayload(BlockPos pos, List<TableEntry> entries, String selected)
		implements CustomPacketPayload {
	public static final Type<OpenTablePayload> TYPE = new Type<>(ModRegistry.id("open_table"));

	public static final StreamCodec<FriendlyByteBuf, OpenTablePayload> CODEC = StreamCodec.of(
			(buf, payload) -> {
				BlockPos.STREAM_CODEC.encode(buf, payload.pos);
				buf.writeVarInt(payload.entries.size());
				for (TableEntry entry : payload.entries) {
					TableEntry.CODEC.encode(buf, entry);
				}
				buf.writeUtf(payload.selected, 128);
			},
			buf -> {
				BlockPos pos = BlockPos.STREAM_CODEC.decode(buf);
				int size = Math.min(buf.readVarInt(), 4096);
				List<TableEntry> entries = new ArrayList<>(size);
				for (int i = 0; i < size; i++) {
					entries.add(TableEntry.CODEC.decode(buf));
				}
				return new OpenTablePayload(pos, entries, buf.readUtf(128));
			});

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
