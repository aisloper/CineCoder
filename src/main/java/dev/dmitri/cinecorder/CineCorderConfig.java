package dev.dmitri.cinecorder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.fabricmc.loader.api.FabricLoader;

/** {@code config/cinecorder/config.json} */
public final class CineCorderConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static CineCorderConfig instance;

	public String blenderPath = "blender";
	public int blenderTimeoutSeconds = 120;
	public boolean convertBlendFiles = true;

	public static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("cinecorder").resolve("config.json");
	}

	public static synchronized CineCorderConfig get() {
		if (instance == null) {
			instance = load();
		}
		return instance;
	}

	private static CineCorderConfig load() {
		Path path = file();
		if (Files.exists(path)) {
			try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
				CineCorderConfig config = GSON.fromJson(reader, CineCorderConfig.class);
				if (config != null) {
					return config;
				}
			} catch (IOException | RuntimeException ignored) {
			}
		}

		CineCorderConfig config = new CineCorderConfig();
		config.save();
		return config;
	}

	public void save() {
		Path path = file();
		try {
			Files.createDirectories(path.getParent());
			try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
				GSON.toJson(this, writer);
			}
		} catch (IOException ignored) {
		}
	}
}
