package dev.dmitri.cinecorder.anim;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Parses and validates {@code .bcam} animation files. */
public final class AnimationParser {
	private AnimationParser() {
	}

	public static AnimationClip parse(String json, String fallbackName) throws AnimationException {
		JsonObject root;
		try {
			JsonElement element = JsonParser.parseString(json);
			if (!element.isJsonObject()) {
				throw new AnimationException("Animation root must be a JSON object");
			}
			root = element.getAsJsonObject();
		} catch (JsonParseException e) {
			throw new AnimationException("Invalid JSON: " + e.getMessage());
		}

		String format = optString(root, "format", AnimationClip.FORMAT);
		if (!AnimationClip.FORMAT.equals(format)) {
			throw new AnimationException("Unsupported format '" + format + "', expected " + AnimationClip.FORMAT);
		}

		String name = optString(root, "name", fallbackName);
		double fps = optDouble(root, "fps", 24.0);
		float scale = (float) optDouble(root, "scale", 1.0);
		boolean loop = optBoolean(root, "loop", false);

		JsonArray nodeArray = optArray(root, "nodes");
		if (nodeArray == null || nodeArray.isEmpty()) {
			throw new AnimationException("Animation contains no nodes");
		}

		List<AnimationClip.Node> nodes = new ArrayList<>();
		double maxTime = 0.0;

		for (int i = 0; i < nodeArray.size(); i++) {
			JsonObject nodeObject = asObject(nodeArray.get(i), "nodes[" + i + "]");
			String nodeName = optString(nodeObject, "name", "node" + i);
			int parent = (int) optDouble(nodeObject, "parent", -1.0);
			if (parent >= i) {
				throw new AnimationException("Node '" + nodeName + "' references parent " + parent
						+ " but parents must be declared before their children");
			}

			Vector3f pivot = optVector(nodeObject, "pivot", new Vector3f()).mul(scale);

			JsonArray partArray = optArray(nodeObject, "parts");
			List<AnimationClip.Part> parts = new ArrayList<>();
			if (partArray != null) {
				for (int p = 0; p < partArray.size(); p++) {
					JsonObject partObject = asObject(partArray.get(p), "parts[" + p + "]");
					Vector3f from = requiredVector(partObject, "from").mul(scale);
					Vector3f to = requiredVector(partObject, "to").mul(scale);
					int color = optColor(partObject, "color", 0xFFCCCCCC);
					parts.add(new AnimationClip.Part(
							new Vector3f(Math.min(from.x, to.x), Math.min(from.y, to.y), Math.min(from.z, to.z)),
							new Vector3f(Math.max(from.x, to.x), Math.max(from.y, to.y), Math.max(from.z, to.z)),
							color));
				}
			}

			if (parts.isEmpty()) {
				throw new AnimationException("Node '" + nodeName + "' has no parts (box meshes)");
			}

			List<AnimationClip.Key> keys = new ArrayList<>();
			JsonArray keyArray = optArray(nodeObject, "keys");
			if (keyArray != null) {
				for (int k = 0; k < keyArray.size(); k++) {
					JsonObject keyObject = asObject(keyArray.get(k), "keys[" + k + "]");
					double time = optDouble(keyObject, "t", 0.0);
					maxTime = Math.max(maxTime, time);
					Vector3f position = optVector(keyObject, "pos", new Vector3f()).mul(scale);
					Vector3f nodeScale = optVector(keyObject, "scale", new Vector3f(1.0f, 1.0f, 1.0f));
					Quaternionf rotation = parseRotation(keyObject);
					boolean step = optBoolean(keyObject, "step", false);
					keys.add(new AnimationClip.Key(
							time, new AnimationClip.Transform(position, rotation, nodeScale), step));
				}
			}

			nodes.add(new AnimationClip.Node(nodeName, parent, pivot, parts,
					AnimationClip.keysFrom(keys)));
		}

		AnimationClip.CameraTrack camera = null;
		JsonObject cameraObject = optObject(root, "camera");
		if (cameraObject != null) {
			float fov = (float) optDouble(cameraObject, "fov", 70.0);
			List<AnimationClip.CameraKey> cameraKeys = new ArrayList<>();
			JsonArray keyArray = optArray(cameraObject, "keys");
			if (keyArray != null) {
				for (int k = 0; k < keyArray.size(); k++) {
					JsonObject keyObject = asObject(keyArray.get(k), "camera.keys[" + k + "]");
					double time = optDouble(keyObject, "t", 0.0);
					maxTime = Math.max(maxTime, time);
					Vector3f position = requiredVector(keyObject, "pos").mul(scale);
					Vector3f look = optVector(keyObject, "look", new Vector3f(0.0f, 1.0f, 0.0f)).mul(scale);
					cameraKeys.add(new AnimationClip.CameraKey(time, position, look));
				}
			}
			camera = new AnimationClip.CameraTrack(cameraKeys, fov);
		}

		double duration = optDouble(root, "duration", maxTime);
		if (duration <= 0.0) {
			duration = Math.max(1.0 / Math.max(1.0, fps), maxTime);
		}

		return new AnimationClip(name, fps, duration, loop, scale, nodes, camera);
	}

	private static Quaternionf parseRotation(JsonObject object) {
		JsonElement element = object.get("rot");
		if (element == null || element.isJsonNull()) {
			return new Quaternionf();
		}

		if (element.isJsonArray()) {
			JsonArray array = element.getAsJsonArray();
			if (array.size() < 3) {
				throw new AnimationException("'rot' must contain three euler angles in degrees");
			}
			return new Quaternionf().rotationXYZ(
					(float) Math.toRadians(array.get(0).getAsDouble()),
					(float) Math.toRadians(array.get(1).getAsDouble()),
					(float) Math.toRadians(array.get(2).getAsDouble()));
		}

		throw new AnimationException("'rot' must be an array of three euler angles in degrees");
	}

	private static JsonObject asObject(JsonElement element, String path) {
		if (element == null || !element.isJsonObject()) {
			throw new AnimationException("'" + path + "' must be an object");
		}
		return element.getAsJsonObject();
	}

	private static Vector3f requiredVector(JsonObject object, String key) {
		JsonElement element = object.get(key);
		if (element == null || !element.isJsonArray()) {
			throw new AnimationException("Missing vector '" + key + "' (expected [x, y, z])");
		}
		return toVector(element.getAsJsonArray(), key);
	}

	private static Vector3f optVector(JsonObject object, String key, Vector3f fallback) {
		JsonElement element = object.get(key);
		if (element == null || element.isJsonNull()) {
			return fallback;
		}
		if (!element.isJsonArray()) {
			throw new AnimationException("'" + key + "' must be an array of numbers");
		}
		return toVector(element.getAsJsonArray(), key);
	}

	private static Vector3f toVector(JsonArray array, String key) {
		if (array.size() < 3) {
			throw new AnimationException("'" + key + "' must contain three numbers");
		}
		return new Vector3f(
				(float) array.get(0).getAsDouble(),
				(float) array.get(1).getAsDouble(),
				(float) array.get(2).getAsDouble());
	}

	private static int optColor(JsonObject object, String key, int fallback) {
		JsonElement element = object.get(key);
		if (element == null || element.isJsonNull()) {
			return fallback;
		}
		if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
			String value = element.getAsString().replace("#", "");
			try {
				int rgb = (int) Long.parseLong(value, 16);
				return 0xFF000000 | (rgb & 0xFFFFFF);
			} catch (NumberFormatException e) {
				throw new AnimationException("Invalid color '" + element.getAsString() + "'");
			}
		}
		if (element.isJsonArray()) {
			JsonArray array = element.getAsJsonArray();
			float r = array.get(0).getAsFloat();
			float g = array.get(1).getAsFloat();
			float b = array.get(2).getAsFloat();
			float a = array.size() > 3 ? array.get(3).getAsFloat() : 1.0f;
			return packColor(r, g, b, a);
		}
		throw new AnimationException("'" + key + "' must be [r, g, b] or a hex string");
	}

	private static int packColor(float r, float g, float b, float a) {
		int ri = Math.max(0, Math.min(255, (int) (r * 255.0f)));
		int gi = Math.max(0, Math.min(255, (int) (g * 255.0f)));
		int bi = Math.max(0, Math.min(255, (int) (b * 255.0f)));
		int ai = Math.max(0, Math.min(255, (int) (a * 255.0f)));
		return (ai << 24) | (ri << 16) | (gi << 8) | bi;
	}

	private static JsonArray optArray(JsonObject object, String key) {
		JsonElement element = object.get(key);
		if (element == null || element.isJsonNull()) {
			return null;
		}
		if (!element.isJsonArray()) {
			throw new AnimationException("'" + key + "' must be an array");
		}
		return element.getAsJsonArray();
	}

	private static JsonObject optObject(JsonObject object, String key) {
		JsonElement element = object.get(key);
		if (element == null || element.isJsonNull()) {
			return null;
		}
		if (!element.isJsonObject()) {
			throw new AnimationException("'" + key + "' must be an object");
		}
		return element.getAsJsonObject();
	}

	private static String optString(JsonObject object, String key, String fallback) {
		JsonElement element = object.get(key);
		return element == null || element.isJsonNull() ? fallback : element.getAsString();
	}

	private static double optDouble(JsonObject object, String key, double fallback) {
		JsonElement element = object.get(key);
		return element == null || element.isJsonNull() ? fallback : element.getAsDouble();
	}

	private static boolean optBoolean(JsonObject object, String key, boolean fallback) {
		JsonElement element = object.get(key);
		return element == null || element.isJsonNull() ? fallback : element.getAsBoolean();
	}
}
