package dev.dmitri.cinecorder.net;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import dev.dmitri.cinecorder.CineCorder;
import dev.dmitri.cinecorder.anim.AnimationStore;
import dev.dmitri.cinecorder.block.AnimationTableBlockEntity;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/** Payload registration and the server-side table/animation handlers. */
public final class CineCorderNetworking {
	private static final Map<UUID, Long> LAST_ANIMATION_REQUEST = new ConcurrentHashMap<>();
	private static final long REQUEST_COOLDOWN_MS = 3000L;

	private CineCorderNetworking() {
	}

	/** Called on both sides during mod initialisation. */
	public static void registerPayloads() {
		PayloadTypeRegistry.clientboundPlay().register(OpenTablePayload.TYPE, OpenTablePayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(TableSelectPayload.TYPE, TableSelectPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(AnimationRequestPayload.TYPE,
				AnimationRequestPayload.CODEC);
		// Large payload: Fabric splits it into multiple packets automatically.
		PayloadTypeRegistry.clientboundPlay().registerLarge(AnimationDataPayload.TYPE,
				AnimationDataPayload.CODEC, 64 * 1024 * 1024);
	}

	public static void registerServerReceivers() {
		ServerPlayNetworking.registerGlobalReceiver(TableSelectPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			if (!(player.level().getBlockEntity(payload.pos()) instanceof AnimationTableBlockEntity table)) {
				return;
			}
			if (player.distanceToSqr(Vec3.atCenterOf(payload.pos())) > 64.0) {
				return;
			}
			table.record(player, payload.name());
		});

		ServerPlayNetworking.registerGlobalReceiver(AnimationRequestPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			long now = System.currentTimeMillis();
			Long last = LAST_ANIMATION_REQUEST.put(player.getUUID(), now);
			if (last != null && now - last < REQUEST_COOLDOWN_MS) {
				return;
			}

			try {
				String json = AnimationStore.read(payload.id(), payload.source());
				byte[] compressed = AnimationDataPayload.gzip(json.getBytes(StandardCharsets.UTF_8));
				if (compressed.length > AnimationDataPayload.MAX_COMPRESSED) {
					CineCorder.LOGGER.warn("Animation '{}' is too large to send ({} bytes compressed)",
							payload.id(), compressed.length);
					return;
				}
				ServerPlayNetworking.send(player,
						new AnimationDataPayload(payload.id(), compressed));
			} catch (Exception e) {
				CineCorder.LOGGER.warn("Could not send animation '{}' to {}: {}", payload.id(),
						player.getName().getString(), e.toString());
				ServerPlayNetworking.send(player, new AnimationDataPayload(payload.id(), new byte[0]));
			}
		});
	}

	public static void openTable(ServerPlayer player, AnimationTableBlockEntity table) {
		ServerPlayNetworking.send(player, new OpenTablePayload(
				table.getBlockPos(),
				dev.dmitri.cinecorder.anim.AnimationLibrary.entries(),
				table.selectedFile() == null ? "" : table.selectedFile()));
	}
}
