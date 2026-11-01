package dev.dmitri.cinecorder.anim;

import java.util.ArrayList;
import java.util.List;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Immutable, parsed representation of a {@code .bcam} animation file.
 *
 * <p>Coordinates are Minecraft coordinates (Y up, +Z south) measured in blocks,
 * relative to the projector that plays the clip.</p>
 */
public final class AnimationClip {
	public static final String FORMAT = "cinecorder/1";

	private final String name;
	private final double fps;
	private final double duration;
	private final boolean loop;
	private final float scale;
	private final List<Node> nodes;
	private final CameraTrack camera;

	public AnimationClip(String name, double fps, double duration, boolean loop, float scale,
			List<Node> nodes, CameraTrack camera) {
		this.name = name;
		this.fps = fps;
		this.duration = duration;
		this.loop = loop;
		this.scale = scale;
		this.nodes = List.copyOf(nodes);
		this.camera = camera;
	}

	public String name() {
		return name;
	}

	public double fps() {
		return fps;
	}

	public double duration() {
		return duration;
	}

	public int durationTicks() {
		return Math.max(1, (int) Math.round(duration * 20.0));
	}

	public boolean loop() {
		return loop;
	}

	public float scale() {
		return scale;
	}

	public List<Node> nodes() {
		return nodes;
	}

	public CameraTrack camera() {
		return camera;
	}

	/** Maps an absolute time to a time inside the clip, honouring looping. */
	public double localTime(double seconds) {
		if (duration <= 0.0) {
			return 0.0;
		}
		if (loop) {
			double t = seconds % duration;
			return t < 0.0 ? t + duration : t;
		}
		return Math.max(0.0, Math.min(seconds, duration));
	}

	/** Returns the world matrix of every node at the given time (parents come first). */
	public Matrix4f[] worldMatrices(double seconds) {
		double t = localTime(seconds);
		Matrix4f[] out = new Matrix4f[nodes.size()];
		for (int i = 0; i < nodes.size(); i++) {
			Node node = nodes.get(i);
			Matrix4f local = node.localMatrix(t);
			if (node.parent() >= 0) {
				out[i] = new Matrix4f(out[node.parent()]).mul(local);
			} else {
				out[i] = local;
			}
		}
		return out;
	}

	public static final class Node {
		private final String name;
		private final int parent;
		private final Vector3f pivot;
		private final List<Part> parts;
		private final List<Mesh> meshes;
		private final List<Key> keys;
		private final Matrix4f bind;

		public Node(String name, int parent, Vector3f pivot, List<Part> parts, List<Mesh> meshes,
				List<Key> keys, Matrix4f bind) {
			this.name = name;
			this.parent = parent;
			this.pivot = pivot;
			this.parts = List.copyOf(parts);
			this.meshes = List.copyOf(meshes);
			this.keys = List.copyOf(keys);
			this.bind = bind;
		}

		public String name() {
			return name;
		}

		public int parent() {
			return parent;
		}

		public List<Part> parts() {
			return parts;
		}

		public List<Mesh> meshes() {
			return meshes;
		}

		public Matrix4f bind() {
			return bind;
		}

		public List<Key> keys() {
			return keys;
		}

		public Matrix4f localMatrix(double time) {
			Transform transform = sample(time);
			return new Matrix4f()
					.translate(transform.position())
					.translate(pivot)
					.rotate(transform.rotation())
					.scale(transform.scale())
					.translate(-pivot.x, -pivot.y, -pivot.z);
		}

		public Transform sample(double time) {
			if (keys.isEmpty()) {
				return new Transform(new Vector3f(), new Quaternionf(), new Vector3f(1.0f));
			}
			if (time <= keys.getFirst().time()) {
				return keys.getFirst().transform();
			}
			if (time >= keys.getLast().time()) {
				return keys.getLast().transform();
			}

			int index = 0;
			while (index < keys.size() - 1 && keys.get(index + 1).time() <= time) {
				index++;
			}
			Key a = keys.get(index);
			Key b = keys.get(index + 1);
			double span = b.time() - a.time();
			float delta = span <= 0.0 ? 0.0f : (float) ((time - a.time()) / span);

			if (a.step()) {
				return a.transform();
			}

			Vector3f position = new Vector3f(a.transform().position()).lerp(b.transform().position(), delta);
			Vector3f scale = new Vector3f(a.transform().scale()).lerp(b.transform().scale(), delta);
			Quaternionf rotation = new Quaternionf(a.transform().rotation()).slerp(b.transform().rotation(), delta);
			return new Transform(position, rotation, scale);
		}
	}

	public record Transform(Vector3f position, Quaternionf rotation, Vector3f scale) {
	}

	public record Part(Vector3f from, Vector3f to, int argb) {
	}

	/** Arbitrary triangle geometry in node-local space, for meshes that are not boxes. */
	public static final class Mesh {
		private final float[] vertices;
		private final int[] indices;
		private final int argb;
		private final int[] boneIndices;
		private final float[] boneWeights;

		public Mesh(float[] vertices, int[] indices, int argb, int[] boneIndices,
				float[] boneWeights) {
			this.vertices = vertices;
			this.indices = indices;
			this.argb = argb;
			this.boneIndices = boneIndices;
			this.boneWeights = boneWeights;
		}

		public float[] vertices() {
			return this.vertices;
		}

		public int[] indices() {
			return this.indices;
		}

		public int argb() {
			return this.argb;
		}

		public int[] boneIndices() {
			return this.boneIndices;
		}

		public float[] boneWeights() {
			return this.boneWeights;
		}

		public boolean skinned() {
			return this.boneIndices != null && this.boneIndices.length == (this.vertices.length / 3) * 4;
		}
	}

	public record Key(double time, Transform transform, boolean step) {
	}

	public record CameraPose(float yaw, float pitch, Vector3f position) {
	}

	/** A camera path made of "position + look-at target" pairs. */
	public static final class CameraTrack {
		private final List<CameraKey> keys;
		private final float fov;

		public CameraTrack(List<CameraKey> keys, float fov) {
			this.keys = List.copyOf(keys);
			this.fov = fov;
		}

		public float fov() {
			return fov;
		}

		public boolean isEmpty() {
			return keys.isEmpty();
		}

		public CameraPose sample(double seconds) {
			if (keys.isEmpty()) {
				return new CameraPose(0.0f, 0.0f, new Vector3f(0.0f, 2.0f, 6.0f));
			}
			if (seconds <= keys.getFirst().time()) {
				return pose(keys.getFirst());
			}
			if (seconds >= keys.getLast().time()) {
				return pose(keys.getLast());
			}

			int index = 0;
			while (index < keys.size() - 1 && keys.get(index + 1).time() <= seconds) {
				index++;
			}
			CameraKey a = keys.get(index);
			CameraKey b = keys.get(index + 1);
			double span = b.time() - a.time();
			float delta = span <= 0.0 ? 0.0f : (float) ((seconds - a.time()) / span);
			Vector3f position = new Vector3f(a.position()).lerp(b.position(), delta);
			Vector3f look = new Vector3f(a.look()).lerp(b.look(), delta);
			return pose(position, look);
		}

		private static CameraPose pose(CameraKey key) {
			return pose(key.position(), key.look());
		}

		private static CameraPose pose(Vector3f position, Vector3f look) {
			Vector3f direction = new Vector3f(look).sub(position);
			if (direction.lengthSquared() < 1.0e-8f) {
				direction.set(0.0f, 0.0f, 1.0f);
			} else {
				direction.normalize();
			}
			float yaw = (float) Math.toDegrees(Math.atan2(-direction.x, direction.z));
			float pitch = (float) Math.toDegrees(-Math.asin(Math.max(-1.0f, Math.min(1.0f, direction.y))));
			return new CameraPose(yaw, pitch, position);
		}
	}

	public record CameraKey(double time, Vector3f position, Vector3f look) {
	}

	public static List<Key> keysFrom(List<Key> keys) {
		List<Key> sorted = new ArrayList<>(keys);
		sorted.sort((a, b) -> Double.compare(a.time(), b.time()));
		return sorted;
	}
}
