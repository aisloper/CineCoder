package dev.dmitri.cinecorder.anim;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Server-side store for animation data referenced by cassettes.
 *
 * <p>When an animation is recorded, its JSON is copied to
 * {@code config/cinecorder/cassettes/<sha1>.bcam} and the cassette only keeps the hash. This
 * makes the cassette small enough for NBT and keeps it working even if the source file is moved
 * or deleted.</p>
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
		return id;
	}

	/** Resolves the JSON for a cassette reference. */
	public static String read(String id, String source) throws IOException {
		if (id != null && !id.isEmpty()) {
			Path stored = DIRECTORY.resolve(id + ".bcam");
			if (Files.exists(stored)) {
				return Files.readString(stored, StandardCharsets.UTF_8);
			}
		}

		if (source == null || source.isEmpty()) {
			throw new IOException("No source animation for cassette " + id);
		}

		String json = AnimationLibrary.read(source);
		try {
			// Persist a copy so the cassette survives the original file being removed.
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
		AnimationClip clip = AnimationParser.parse(json, source == null || source.isEmpty() ? key : source);
		CACHE.put(key, clip);
		return clip;
	}

	public static void clear() {
		CACHE.clear();
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
