package dev.cinecorder.anim;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import dev.cinecorder.CineCorder;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Server-side store for animation data referenced by cassettes.
 *
 * <p>A cassette only keeps a content hash and the source name. The JSON lives in
 * {@code config/cinecorder/cassettes}. Old cassette files are automatically rebuilt from their
 * {@code .blend} source when the animation format changes; the cassette itself does not have to
 * be re-recorded.</p>
 */
public final class AnimationStore {
	private static final Path DIRECTORY = FabricLoader.getInstance().getConfigDir()
			.resolve("cinecorder").resolve("cassettes");
	private static final Map<String, AnimationClip> CACHE = new ConcurrentHashMap<>();

	private AnimationStore() {
	}

	public static Path directory() {
		return DIRECTORY;
	}

	/** Copies the animation into the store and returns its content hash. */
	public static String store(String json, String source) throws IOException {
		Files.createDirectories(DIRECTORY);
		String id = hash(json);
		Path target = DIRECTORY.resolve(id + ".bcam");
		if (!Files.exists(target)) {
			Path temp = DIRECTORY.resolve(id + ".tmp");
			Files.writeString(temp, json, StandardCharsets.UTF_8);
			Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
		}
		writeSource(id, source);
		return id;
	}

	/** Resolves the JSON for a cassette reference, rebuilding stale data when possible. */
	public static String read(String id, String source) throws IOException {
		if (id != null && !id.isEmpty()) {
			Path stored = DIRECTORY.resolve(id + ".bcam");
			if (Files.exists(stored)) {
				if (source != null && !source.isEmpty()) {
					writeSource(id, source);
				}
				if (AnimationLibrary.readVersion(stored) >= AnimationClip.CURRENT_FORMAT_VERSION) {
					return Files.readString(stored, StandardCharsets.UTF_8);
				}

				String rebuilt = rebuildFromSource(id, source, stored);
				if (rebuilt != null) {
					return rebuilt;
				}

				// There is a .blend source: wait for the background conversion instead of
				// serving geometry from the previous format.
				if (source != null && !source.isEmpty() && AnimationLibrary.blendFor(source) != null) {
					throw new AnimationException(
							"Animation is being rebuilt to the current format; retry in a few seconds");
				}
				return Files.readString(stored, StandardCharsets.UTF_8);
			}
		}

		if (source == null || source.isEmpty()) {
			throw new IOException("No source animation for cassette " + id);
		}

		String json = AnimationLibrary.read(source);
		try {
			store(json, source);
		} catch (IOException ignored) {
		}
		return json;
	}

	public static AnimationClip load(String id, String source) throws IOException {
		String key = (id == null || id.isEmpty()) ? "source:" + source : id;
		AnimationClip cached = CACHE.get(key);
		if (cached != null) {
			return cached;
		}

		String json = read(id, source);
		AnimationClip clip = AnimationParser.parse(json,
				source == null || source.isEmpty() ? key : source);
		CACHE.put(key, clip);
		return clip;
	}

	/**
	 * Rebuilds stored cassette animations that were exported with an older format.
	 *
	 * @return number of files rewritten
	 */
	public static int rebuild(boolean force) {
		if (!Files.isDirectory(DIRECTORY)) {
			return 0;
		}

		int count = 0;
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(DIRECTORY, "*.bcam")) {
			for (Path stored : stream) {
				String id = baseName(stored);
				if (!force && AnimationLibrary.readVersion(stored) >= AnimationClip.CURRENT_FORMAT_VERSION) {
					continue;
				}
				String source = readSource(id);
				if (source == null || source.isEmpty()) {
					continue;
				}
				if (rebuildFromSource(id, source, stored) != null) {
					count++;
				}
			}
		} catch (IOException e) {
			CineCorder.LOGGER.warn("Could not rebuild cassette store: {}", e.toString());
		}
		return count;
	}

	public static void clear() {
		CACHE.clear();
	}

	private static String rebuildFromSource(String id, String source, Path stored) throws IOException {
		if (source == null || source.isEmpty()) {
			return null;
		}
		Path blend = AnimationLibrary.blendFor(source);
		if (blend == null) {
			return null;
		}

		BlendConverter.request(blend);
		Path converted = BlendConverter.cached(blend);
		if (converted == null) {
			return null;
		}

		String json = Files.readString(converted, StandardCharsets.UTF_8);
		if (AnimationParser.readVersion(json) < AnimationClip.CURRENT_FORMAT_VERSION) {
			return null;
		}

		Files.writeString(stored, json, StandardCharsets.UTF_8);
		CACHE.remove(id);
		CineCorder.LOGGER.info("Rebuilt cassette {} from '{}' to format v{}", id, source,
				AnimationClip.CURRENT_FORMAT_VERSION);
		return json;
	}

	private static void writeSource(String id, String source) {
		if (id == null || id.isEmpty() || source == null || source.isEmpty()) {
			return;
		}
		try {
			Files.createDirectories(DIRECTORY);
			Files.writeString(DIRECTORY.resolve(id + ".source"), source, StandardCharsets.UTF_8);
		} catch (IOException ignored) {
		}
	}

	private static String readSource(String id) {
		try {
			Path path = DIRECTORY.resolve(id + ".source");
			return Files.exists(path) ? Files.readString(path, StandardCharsets.UTF_8).trim() : null;
		} catch (IOException e) {
			return null;
		}
	}

	private static String baseName(Path path) {
		String fileName = path.getFileName().toString();
		int dot = fileName.lastIndexOf('.');
		return dot > 0 ? fileName.substring(0, dot) : fileName;
	}

	private static String hash(String json) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-1");
			return HexFormat.of().formatHex(digest.digest(json.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-1 is unavailable", e);
		}
	}
}
