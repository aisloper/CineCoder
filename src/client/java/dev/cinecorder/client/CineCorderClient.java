package dev.cinecorder.client;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

import dev.cinecorder.CineCorder;
import dev.cinecorder.ModRegistry;
import dev.cinecorder.anim.AnimationCache;
import dev.cinecorder.anim.AnimationParser;
import dev.cinecorder.net.AnimationDataPayload;
import dev.cinecorder.net.AnimationRequestPayload;
import dev.cinecorder.net.OpenTablePayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.client.renderer.entity.NoopRenderer;

public class CineCorderClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		EntityRenderers.register(ModRegistry.CAMERA_RIG, context -> new NoopRenderer<>(context));
		LevelRenderEvents.COLLECT_SUBMITS.register(SceneRenderer::collectSubmits);

		ClientPlayNetworking.registerGlobalReceiver(OpenTablePayload.TYPE, (payload, context) -> {
			var client = context.client();
			client.execute(() -> client.gui.setScreen(new AnimationTableScreen(payload)));
		});

		ClientPlayNetworking.registerGlobalReceiver(AnimationDataPayload.TYPE, (payload, context) -> {
			if (payload.data().length == 0) {
				context.client().execute(() -> AnimationCache.fail(payload.id()));
				return;
			}
			CompletableFuture.supplyAsync(() -> {
				try {
					byte[] raw = AnimationDataPayload.gunzip(payload.data());
					return AnimationParser.parse(new String(raw, StandardCharsets.UTF_8), payload.id());
				} catch (Exception e) {
					CineCorder.LOGGER.warn("Could not load animation '{}': {}", payload.id(), e.toString());
					return null;
				}
			}).thenAccept(clip -> context.client().execute(() -> {
				if (clip != null) {
					AnimationCache.put(payload.id(), clip);
				} else {
					AnimationCache.fail(payload.id());
				}
			}));
		});

		AnimationCache.requester = content -> ClientPlayNetworking.send(
				new AnimationRequestPayload(content.id(), content.source()));

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> AnimationCache.clear());
	}
}
