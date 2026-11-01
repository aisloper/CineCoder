package dev.dmitri.cinecorder.net;

import dev.dmitri.cinecorder.ModRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client -> server: send me the animation with this id (or source name). */
public record AnimationRequestPayload(String id, String source) implements CustomPacketPayload {
	public static final Type<AnimationRequestPayload> TYPE = new Type<>(ModRegistry.id("animation_request"));

	public static final StreamCodec<FriendlyByteBuf, AnimationRequestPayload> CODEC = StreamCodec.of(
			(buf, payload) -> {
				buf.writeUtf(payload.id, 128);
				buf.writeUtf(payload.source, 128);
			},
			buf -> new AnimationRequestPayload(buf.readUtf(128), buf.readUtf(128)));

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
