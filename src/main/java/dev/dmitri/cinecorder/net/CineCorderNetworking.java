package dev.dmitri.cinecorder.net;

import dev.dmitri.cinecorder.block.AnimationTableBlockEntity;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/** Payload registration and the server-side table handler. */
public final class CineCorderNetworking {
	private CineCorderNetworking() {
	}

	/** Called on both sides during mod initialisation. */
	public static void registerPayloads() {
		PayloadTypeRegistry.clientboundPlay().register(OpenTablePayload.TYPE, OpenTablePayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(TableSelectPayload.TYPE, TableSelectPayload.CODEC);
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
	}

	public static void openTable(ServerPlayer player, AnimationTableBlockEntity table) {
		ServerPlayNetworking.send(player, new OpenTablePayload(
				table.getBlockPos(),
				dev.dmitri.cinecorder.anim.AnimationLibrary.entries(),
				table.selectedFile() == null ? "" : table.selectedFile()));
	}
}
