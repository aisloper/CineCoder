package dev.cinecorder.anim;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import dev.cinecorder.item.CassetteData;

/**
 * Client-side cache of animation clips referenced by cassettes.
 *
 * <p>When a clip is missing, a request is sent to the server (throttled); the large JSON is
 * streamed back by {@code AnimationDataPayload} and parsed off-thread.</p>
 */
public final class AnimationCache {
	private static final Map<String, AnimationClip> CLIPS = new ConcurrentHashMap<>();
	private static final Map<String, Long> REQUESTED = new ConcurrentHashMap<>();
	private static final java.util.Set<String> FAILED = ConcurrentHashMap.newKeySet();
	private static final long REQUEST_INTERVAL_MS = 3000L;

	/** Set by the client initializer to actually send the request. */
	public static volatile Consumer<CassetteData.Content> requester = content -> {
	};

	private AnimationCache() {
	}

	public static AnimationClip get(CassetteData.Content content) {
		String key = content.cacheKey();
		AnimationClip clip = CLIPS.get(key);
		if (clip != null) {
			return clip;
		}

		if (FAILED.contains(key)) {
			return null;
		}

		if (content.hasInline()) {
			try {
				clip = AnimationParser.parse(content.inlineJson(), content.title());
				CLIPS.put(key, clip);
				return clip;
			} catch (RuntimeException e) {
				return null;
			}
		}

		long now = System.currentTimeMillis();
		Long last = REQUESTED.get(key);
		if (last == null || now - last > REQUEST_INTERVAL_MS) {
			REQUESTED.put(key, now);
			requester.accept(content);
		}
		return null;
	}

	public static void put(String id, AnimationClip clip) {
		if (clip != null && id != null && !id.isEmpty()) {
			CLIPS.put(id, clip);
			REQUESTED.remove(id);
			FAILED.remove(id);
		}
	}

	public static void fail(String id) {
		if (id != null && !id.isEmpty()) {
			REQUESTED.remove(id);
			FAILED.add(id);
		}
	}

	public static void clear() {
		CLIPS.clear();
		REQUESTED.clear();
		FAILED.clear();
	}
}
