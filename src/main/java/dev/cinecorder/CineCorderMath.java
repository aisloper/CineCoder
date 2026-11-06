package dev.cinecorder;

import net.minecraft.world.phys.Vec3;

/** Small shared math helpers. */
public final class CineCorderMath {
	private CineCorderMath() {
	}

	/**
	 * Rotates a vector around the Y axis using Minecraft's yaw convention
	 * (0 degrees points towards +Z, 90 degrees towards -X).
	 */
	public static Vec3 rotateYaw(float yawDegrees, Vec3 vector) {
		double angle = Math.toRadians(yawDegrees);
		double cos = Math.cos(angle);
		double sin = Math.sin(angle);
		return new Vec3(
				vector.x * cos - vector.z * sin,
				vector.y,
				vector.x * sin + vector.z * cos);
	}
}
