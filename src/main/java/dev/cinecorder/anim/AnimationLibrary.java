package dev.cinecorder.anim;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.cinecorder.net.TableEntry;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Server-side access to the animation folder ({@code config/cinecorder/animations}).
 *
 * <p>Both {@code .bcam} files and {@code .blend} files are listed. Blend files are converted to
 * {@code .bcam} in the background through a headless Blender run (see {@link BlendConverter}).</p>
 */
public final class AnimationLibrary {
	private static final Path DIRECTORY = FabricLoader.getInstance().getConfigDir()
			.resolve("cinecorder").resolve("animations");
	private static final String DEMO_RESOURCE = "/assets/cinecorder/demo/demo_show.bcam";
	private static final String DEMO_NAME = "demo_show";

	private static final Map<String, Source> SOURCES = new LinkedHashMap<>();
	private static final Map<String, AnimationClip> CACHE = new HashMap<>();
	private static long lastScan;

	private AnimationLibrary() {
	}

	public record Source(String name, Path path, boolean blend) {
	}

	public static Path directory() {
		return DIRECTORY;
	}

	public static Path cacheDirectory() {
		return DIRECTORY.getParent().resolve("cache");
	}

	public static void reload() {
		lastScan = System.currentTimeMillis();
		CACHE.clear();
		SOURCES.clear();

		try {
			Files.createDirectories(DIRECTORY);
			Files.createDirectories(cacheDirectory());
		} catch (IOException e) {
			return;
		}

		Map<String, Path> blends = new LinkedHashMap<>();
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(DIRECTORY, "*.bcam")) {
			for (Path path : stream) {
				String base = baseName(path);
				// If a stale .bcam sits next to its .blend source, prefer the source so the
				// automatic converter can rebuild it in the current format.
				Path blend = DIRECTORY.resolve(base + ".blend");
				if (Files.exists(blend)) {
					// The .blend is the editable source; always let it win so changes and
					// format upgrades are picked up automatically.
					continue;
				}
				SOURCES.put(base, new Source(base, path, false));
			}
		} catch (IOException ignored) {
		}

		try (DirectoryStream<Path> stream = Files.newDirectoryStream(DIRECTORY, "*.blend")) {
			for (Path path : stream) {
				String base = baseName(path);
				if (!SOURCES.containsKey(base)) {
					blends.put(base, path);
				}
			}
		} catch (IOException ignored) {
		}

		List<String> names = new ArrayList<>(blends.keySet());
		names.sort(Comparator.comparing(String::toLowerCase));
		for (String name : names) {
			Path path = blends.get(name);
			SOURCES.put(name, new Source(name, path, true));
			BlendConverter.request(path);
		}

		// Keep bcam entries sorted as well.
		List<Source> ordered = new ArrayList<>(SOURCES.values());
		ordered.sort(Comparator.comparing(source -> source.name().toLowerCase()));
		SOURCES.clear();
		for (Source source : ordered) {
			SOURCES.put(source.name(), source);
		}
	}

	public static List<String> files() {
		if (SOURCES.isEmpty() && System.currentTimeMillis() - lastScan > 2000) {
			reload();
		}
		return List.copyOf(SOURCES.keySet());
	}

	/** Metadata used by the animation table GUI. */
	public static List<TableEntry> entries() {
		if (SOURCES.isEmpty()) {
			reload();
		}

		List<TableEntry> entries = new ArrayList<>();
		for (Source source : SOURCES.values()) {
			if (source.blend()) {
				Path cached = BlendConverter.cached(source.path());
				if (cached == null) {
					BlendConverter.request(source.path());
					entries.add(new TableEntry(source.name(), source.name(), -1, false, false));
				} else {
					entries.add(describe(cached, source.name()));
				}
			} else {
				entries.add(describe(source.path(), source.name()));
			}
		}
		return entries;
	}

	private static TableEntry describe(Path path, String name) {
		try {
			// Do not parse huge clips just to show their duration in the GUI.
			if (Files.size(path) > 8L * 1024 * 1024) {
				return new TableEntry(name, name, -1, false, true);
			}
			AnimationClip clip = parse(path, name);
			return new TableEntry(name, clip.name(), clip.durationTicks(), clip.loop(), true);
		} catch (RuntimeException | IOException e) {
			return new TableEntry(name, name, -1, false, false);
		}
	}

	/** Reads only the format version from a file (without loading the whole animation). */
	public static int readVersion(Path path) {
		try (InputStream stream = Files.newInputStream(path)) {
			byte[] head = stream.readNBytes(8192);
			return AnimationParser.readVersion(new String(head, StandardCharsets.UTF_8));
		} catch (IOException e) {
			return 1;
		}
	}

	public static Path blendFor(String name) {
		if (!isValidName(name)) {
			return null;
		}
		Path blend = DIRECTORY.resolve(name + ".blend");
		return Files.exists(blend) ? blend : null;
	}

	public static void requestBlends() {
		for (Source source : SOURCES.values()) {
			if (source.blend()) {
				BlendConverter.request(source.path());
			}
		}
	}

	public static String read(String name) throws IOException {
		Source source = SOURCES.get(name);
		if (source == null) {
			reload();
			source = SOURCES.get(name);
		}
		if (source == null) {
			throw new IOException("Unknown animation '" + name + "'");
		}

		if (!source.blend()) {
			return Files.readString(source.path(), StandardCharsets.UTF_8);
		}

		Path cached = BlendConverter.cached(source.path());
		if (cached == null) {
			BlendConverter.request(source.path());
			throw new AnimationException("Blend file is still converting, try again in a few seconds"
					+ BlendConverter.errorHint(source.path()));
		}
		return Files.readString(cached, StandardCharsets.UTF_8);
	}

	public static AnimationClip load(String name) throws IOException {
		AnimationClip cached = CACHE.get(name);
		if (cached != null) {
			return cached;
		}
		Source source = SOURCES.get(name);
		if (source == null) {
			read(name); // refresh + friendly error
		}
		source = SOURCES.get(name);
		if (source == null) {
			throw new IOException("Unknown animation '" + name + "'");
		}
		Path path = source.blend() ? BlendConverter.cached(source.path()) : source.path();
		if (path == null) {
			throw new AnimationException("Blend file is still converting");
		}
		AnimationClip clip = parse(path, name);
		CACHE.put(name, clip);
		return clip;
	}

	private static AnimationClip parse(Path path, String name) throws IOException {
		return AnimationParser.parse(Files.readString(path, StandardCharsets.UTF_8), name);
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

	private static String baseName(Path path) {
		String fileName = path.getFileName().toString();
		int dot = fileName.lastIndexOf('.');
		return dot > 0 ? fileName.substring(0, dot) : fileName;
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
