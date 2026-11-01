package dev.dmitri.cinecorder.anim;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import dev.dmitri.cinecorder.CineCorder;
import dev.dmitri.cinecorder.CineCorderConfig;

/**
 * Converts {@code .blend} files into {@code .bcam} by running Blender in background mode.
 *
 * <p>Blender must be installed on the machine that runs the server. The executable can be
 * configured in {@code config/cinecorder/config.json} (or with the {@code CINECORDER_BLENDER}
 * environment variable). Converted files are cached in {@code config/cinecorder/cache}.</p>
 */
public final class BlendConverter {
	private static final String SCRIPT_RESOURCE = "/assets/cinecorder/blender/cinecorder_export.py";
	private static final int CACHE_VERSION = 2;
	private static final Set<Path> PENDING = ConcurrentHashMap.newKeySet();
	private static final Map<Path, String> ERRORS = new ConcurrentHashMap<>();
	private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "CineCorder-Blender");
		thread.setDaemon(true);
		return thread;
	});

	private BlendConverter() {
	}

	public static void initialize() {
		try {
			Files.createDirectories(AnimationLibrary.cacheDirectory());
			extractScript();
		} catch (IOException e) {
			CineCorder.LOGGER.warn("Could not prepare the Blender exporter: {}", e.getMessage());
		}
	}

	public static boolean isAvailable() {
		return CineCorderConfig.get().convertBlendFiles;
	}

	/** Returns the cached {@code .bcam} for this blend file, or {@code null} if not converted yet. */
	public static Path cached(Path blend) {
		try {
			Path cache = AnimationLibrary.cacheDirectory();
			if (!Files.isDirectory(cache)) {
				return null;
			}
			Path expected = cache.resolve(cacheName(blend));
			return Files.exists(expected) ? expected : null;
		} catch (IOException e) {
			return null;
		}
	}

	public static void request(Path blend) {
		if (!isAvailable() || cached(blend) != null || !PENDING.add(blend)) {
			return;
		}

		EXECUTOR.submit(() -> {
			try {
				convert(blend);
			} catch (Exception e) {
				ERRORS.put(blend, String.valueOf(e.getMessage()));
				CineCorder.LOGGER.warn("Blender conversion failed for {}: {}", blend, e.getMessage());
			} finally {
				PENDING.remove(blend);
			}
		});
	}

	public static String errorHint(Path blend) {
		String error = ERRORS.get(blend);
		return error == null ? "" : " (" + error + ")";
	}

	private static void convert(Path blend) throws IOException, InterruptedException {
		Path cache = AnimationLibrary.cacheDirectory();
		Files.createDirectories(cache);

		String name = cacheName(blend);
		Path target = cache.resolve(name);
		if (Files.exists(target)) {
			return;
		}

		Path temp = cache.resolve(name + ".tmp.bcam");
		Files.deleteIfExists(temp);
		Path log = cache.resolve(baseName(blend) + ".log");
		Files.deleteIfExists(log);

		CineCorderConfig config = CineCorderConfig.get();
		List<String> command = new ArrayList<>();
		command.add(config.blenderPath);
		command.add("--background");
		command.add(blend.toAbsolutePath().toString());
		command.add("--factory-startup");
		command.add("--python-exit-code");
		command.add("1");
		command.add("--python");
		command.add(scriptPath().toAbsolutePath().toString());
		command.add("--");
		command.add("--output");
		command.add(temp.toAbsolutePath().toString());
		command.add("--all");
		command.add("--step");
		command.add("1");
		command.add("--name");
		command.add(baseName(blend));

		CineCorder.LOGGER.info("Converting {} with Blender: {}", blend.getFileName(), config.blenderPath);
		Process process = new ProcessBuilder(command)
				.redirectErrorStream(true)
				.redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
				.start();

		if (!process.waitFor(Math.max(5, config.blenderTimeoutSeconds), TimeUnit.SECONDS)) {
			process.destroyForcibly();
			ERRORS.put(blend, "Blender timed out after " + config.blenderTimeoutSeconds + "s");
			return;
		}

		if (process.exitValue() != 0 || !Files.exists(temp)) {
			String hint = Files.exists(log) ? " (see " + log + ")" : "";
			ERRORS.put(blend, "Blender exited with code " + process.exitValue() + hint);
			Files.deleteIfExists(temp);
			return;
		}

		Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
		ERRORS.remove(blend);
		Files.deleteIfExists(log);
		CineCorder.LOGGER.info("Converted {} -> {}", blend.getFileName(), target.getFileName());
	}

	private static String cacheName(Path blend) throws IOException {
		long modified = Files.getLastModifiedTime(blend).toMillis();
		return baseName(blend) + "-" + modified + "-v" + CACHE_VERSION + ".bcam";
	}

	private static String baseName(Path path) {
		String fileName = path.getFileName().toString();
		int dot = fileName.lastIndexOf('.');
		return dot > 0 ? fileName.substring(0, dot) : fileName;
	}

	private static Path scriptPath() {
		return dev.dmitri.cinecorder.CineCorderConfig.file().getParent().resolve("tools")
				.resolve("cinecorder_export.py");
	}

	private static void extractScript() throws IOException {
		Path script = scriptPath();
		Files.createDirectories(script.getParent());
		try (InputStream stream = BlendConverter.class.getResourceAsStream(SCRIPT_RESOURCE)) {
			if (stream == null) {
				return;
			}
			String content = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
			if (!Files.exists(script) || !content.equals(Files.readString(script, StandardCharsets.UTF_8))) {
				Files.writeString(script, content, StandardCharsets.UTF_8);
			}
		}
	}
}
