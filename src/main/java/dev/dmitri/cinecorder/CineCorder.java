package dev.dmitri.cinecorder;

import dev.dmitri.cinecorder.anim.AnimationLibrary;
import dev.dmitri.cinecorder.anim.BlendConverter;
import dev.dmitri.cinecorder.command.CineCorderCommand;
import dev.dmitri.cinecorder.net.CineCorderNetworking;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CineCorder implements ModInitializer {
	public static final String MOD_ID = ModRegistry.MOD_ID;
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

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

		CommandRegistrationCallback.EVENT.register(CineCorderCommand::register);

		LOGGER.info("CineCorder ready: drop .bcam or .blend files into {}", AnimationLibrary.directory());
	}
}
