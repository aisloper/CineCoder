package dev.dmitri.cinecorder.client;

import dev.dmitri.cinecorder.ModRegistry;
import dev.dmitri.cinecorder.net.OpenTablePayload;
import net.fabricmc.api.ClientModInitializer;
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
	}
}
