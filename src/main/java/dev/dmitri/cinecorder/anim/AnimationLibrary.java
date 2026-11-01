package dev.dmitri.cinecorder.anim;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Server-side access to the animation folder ({@code config/cinecorder/animations}).
 *
 * <p>Animation files are plain JSON with the {@code .bcam} extension. They are never sent to
 * clients as files: the cassette item carries the animation data itself.</p>
 */
public final class AnimationLibrary {
	private static final Path DIRECTORY = FabricLoader.getInstance().getConfigDir()
			.resolve("cinecorder").resolve("animations");
	private static final String DEMO_RESOURCE = "/assets/cinecorder/demo/demo_show.bcam";
	private static final String DEMO_NAME = "demo_show";

	private static final Map<String, AnimationClip> CACHE = new HashMap<>();
	private static List<String> files = List.of();
	private static long lastScan;

	private AnimationLibrary() {
	}

	public static Path directory() {
		return DIRECTORY;
	}

	public static void reload() {
		lastScan = System.currentTimeMillis();
		CACHE.clear();
		List<String> found = new ArrayList<>();
		try {
			Files.createDirectories(DIRECTORY);
			try (DirectoryStream<Path> stream = Files.newDirectoryStream(DIRECTORY, "*.bcam")) {
				for (Path path : stream) {
					String fileName = path.getFileName().toString();
					found.add(fileName.substring(0, fileName.length() - ".bcam".length()));
				}
			}
		} catch (IOException e) {
			files = List.of();
			return;
		}
		found.sort(Comparator.comparing(String::toLowerCase));
		files = List.copyOf(found);
	}

	public static List<String> files() {
		if (files.isEmpty() && System.currentTimeMillis() - lastScan > 2000) {
			reload();
		}
		return files;
	}

	public static String read(String name) throws IOException {
		Path path = resolve(name);
		return Files.readString(path, StandardCharsets.UTF_8);
	}

	public static AnimationClip load(String name) throws AnimationException, IOException {
		AnimationClip cached = CACHE.get(name);
		if (cached != null) {
			return cached;
		}
		AnimationClip clip = AnimationParser.parse(read(name), name);
		CACHE.put(name, clip);
		return clip;
	}

	public static void invalidate(String name) {
		CACHE.remove(name);
	}

	/** Copies bundled demo animations into the folder, never overwriting user files. */
	public static int installDemos() {
		try {
			Files.createDirectories(DIRECTORY);
			Path target = DIRECTORY.resolve(DEMO_NAME + ".bcam");
			if (Files.exists(target)) {
				return 0;
			}
			try (InputStream stream = AnimationLibrary.class.getResourceAsStream(DEMO_RESOURCE)) {
				if (stream == null) {
					return 0;
				}
				Files.writeString(target, new String(stream.readAllBytes(), StandardCharsets.UTF_8),
						StandardCharsets.UTF_8);
				return 1;
			}
		} catch (IOException e) {
			return 0;
		}
	}

	private static Path resolve(String name) throws IOException {
		if (!isValidName(name)) {
			throw new IOException("Invalid animation name '" + name + "'");
		}
		return DIRECTORY.resolve(name + ".bcam");
	}

	public static boolean isValidName(String name) {
		if (name == null || name.isBlank() || name.length() > 96) {
			return false;
		}
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
					|| c == '_' || c == '-' || c == ' ' || c == '.';
			if (!ok) {
				return false;
			}
		}
		return !name.contains("..");
	}
}
