package dev.cinecorder.net;

import dev.cinecorder.ModRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client -> server: record the named animation onto the cassette held by the player. */
public record TableSelectPayload(BlockPos pos, String name) implements CustomPacketPayload {
	public static final Type<TableSelectPayload> TYPE = new Type<>(ModRegistry.id("table_select"));

	public static final StreamCodec<FriendlyByteBuf, TableSelectPayload> CODEC = StreamCodec.of(
			(buf, payload) -> {
				BlockPos.STREAM_CODEC.encode(buf, payload.pos);
				buf.writeUtf(payload.name, 128);
			},
			buf -> new TableSelectPayload(BlockPos.STREAM_CODEC.decode(buf), buf.readUtf(128)));

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
