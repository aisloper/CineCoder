package dev.cinecorder;

import dev.cinecorder.anim.AnimationLibrary;
import dev.cinecorder.anim.AnimationStore;
import dev.cinecorder.anim.BlendConverter;
import dev.cinecorder.command.CineCorderCommand;
import dev.cinecorder.net.CineCorderNetworking;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CineCorder implements ModInitializer {
	public static final String MOD_ID = ModRegistry.MOD_ID;
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	private static int rebuildTicks;

	@Override
	public void onInitialize() {
		CineCorderConfig.get();
		ModRegistry.initialize();
		CineCorderNetworking.registerPayloads();
		CineCorderNetworking.registerServerReceivers();
		BlendConverter.initialize();

		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			int installed = AnimationLibrary.installDemos();
			AnimationLibrary.reload();
			if (installed > 0) {
				LOGGER.info("Installed {} demo animation(s) in {}", installed, AnimationLibrary.directory());
			}
		});

		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			CineCorderNetworking.clearCaches();
			dev.cinecorder.anim.AnimationStore.clear();
		});

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (++rebuildTicks % 1200 == 0) {
				int rebuilt = AnimationStore.rebuild(false);
				if (rebuilt > 0) {
					LOGGER.info("Rebuilt {} cassette animation(s) to the current format", rebuilt);
				}
			}
		});

		CommandRegistrationCallback.EVENT.register(CineCorderCommand::register);

		LOGGER.info("CineCorder ready: drop .bcam or .blend files into {}", AnimationLibrary.directory());
	}
}
