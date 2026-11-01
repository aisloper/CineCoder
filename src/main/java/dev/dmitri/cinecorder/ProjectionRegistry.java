package dev.dmitri.cinecorder;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import dev.dmitri.cinecorder.block.ProjectorBlockEntity;

/**
 * Client-side list of loaded projector block entities. Kept in the common source set (it does not
 * reference any client-only classes) so block entities can register themselves on both sides.
 */
public final class ProjectionRegistry {
	private static final List<ProjectorBlockEntity> PROJECTORS = new CopyOnWriteArrayList<>();

	private ProjectionRegistry() {
	}

	public static void add(ProjectorBlockEntity projector) {
		if (!PROJECTORS.contains(projector)) {
			PROJECTORS.add(projector);
		}
	}

	public static void remove(ProjectorBlockEntity projector) {
		PROJECTORS.remove(projector);
	}

	public static List<ProjectorBlockEntity> projectors() {
		return PROJECTORS;
	}
}
