package dev.dmitri.cinecorder.net;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import dev.dmitri.cinecorder.ModRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server -> client: gzip-compressed animation JSON. Registered as a large payload. */
public record AnimationDataPayload(String id, byte[] data) implements CustomPacketPayload {
	public static final int MAX_COMPRESSED = 48 * 1024 * 1024;
	public static final int MAX_UNCOMPRESSED = 128 * 1024 * 1024;

	public static final Type<AnimationDataPayload> TYPE = new Type<>(ModRegistry.id("animation_data"));

	public static final StreamCodec<FriendlyByteBuf, AnimationDataPayload> CODEC = StreamCodec.of(
			(buf, payload) -> {
				buf.writeUtf(payload.id, 128);
				buf.writeVarInt(payload.data.length);
				buf.writeBytes(payload.data);
			},
			buf -> {
				String id = buf.readUtf(128);
				int length = buf.readVarInt();
				if (length < 0 || length > MAX_COMPRESSED) {
					throw new IllegalArgumentException("Animation payload too large: " + length);
				}
				byte[] data = new byte[length];
				buf.readBytes(data);
				return new AnimationDataPayload(id, data);
			});

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	public static byte[] gzip(byte[] raw) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, raw.length / 8));
		try (GZIPOutputStream stream = new GZIPOutputStream(out)) {
			stream.write(raw);
		}
		return out.toByteArray();
	}

	public static byte[] gunzip(byte[] compressed) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, compressed.length * 4));
		try (GZIPInputStream stream = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
			byte[] buffer = new byte[64 * 1024];
			int read;
			long total = 0;
			while ((read = stream.read(buffer)) != -1) {
				total += read;
				if (total > MAX_UNCOMPRESSED) {
					throw new IOException("Animation is larger than " + MAX_UNCOMPRESSED + " bytes");
				}
				out.write(buffer, 0, read);
			}
		}
		return out.toByteArray();
	}
}
