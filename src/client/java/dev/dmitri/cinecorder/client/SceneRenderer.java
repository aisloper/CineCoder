package dev.dmitri.cinecorder.client;

import dev.dmitri.cinecorder.CineCorderMath;
import dev.dmitri.cinecorder.ProjectionRegistry;
import dev.dmitri.cinecorder.block.ProjectorBlock;
import dev.dmitri.cinecorder.block.ProjectorBlockEntity;
import dev.dmitri.cinecorder.anim.AnimationClip;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Draws every playing projection in the world. Rendering happens in the level "submit" phase, so
 * the scene is not tied to the visibility of the projector's chunk (important when the cinematic
 * camera looks away from the projector itself).
 */
public final class SceneRenderer {
	private static final Identifier WHITE = Identifier.fromNamespaceAndPath("cinecorder",
			"textures/scene/white.png");
	private static RenderType renderType;

	private SceneRenderer() {
	}

	public static void collectSubmits(LevelRenderContext context) {
		Minecraft minecraft = Minecraft.getInstance();
		ClientLevel level = minecraft.level;
		if (level == null) {
			return;
		}

		Vec3 camera = minecraft.gameRenderer.mainCamera().position();
		float partialTick = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);

		for (ProjectorBlockEntity projector : ProjectionRegistry.projectors()) {
			if (projector.isRemoved() || projector.getLevel() != level || !projector.isPlaying()) {
				continue;
			}
			AnimationClip clip = projector.clip();
			if (clip == null || clip.nodes().isEmpty()) {
				continue;
			}

			// The camera entity is interpolated a couple of ticks behind the server timeline,
			// so render the scene with a matching offset to keep camera and actors in sync.
			double time = (level.getGameTime() - projector.startGameTime() + partialTick) / 20.0 - 0.1;
			renderProjection(context, level, projector, clip, time, camera);
		}
	}

	private static void renderProjection(LevelRenderContext context, ClientLevel level,
			ProjectorBlockEntity projector, AnimationClip clip, double time, Vec3 camera) {
		BlockPos pos = projector.getBlockPos();
		Vec3 origin = Vec3.atCenterOf(pos);
		float yaw = yawOf(projector.getBlockState());

		PoseStack poseStack = new PoseStack();
		poseStack.translate(origin.x - camera.x, origin.y - camera.y, origin.z - camera.z);
		poseStack.mulPose(yawMatrix(yaw));

		Matrix4f[] matrices = clip.worldMatrices(time);

		context.submitNodeCollector().submitCustomGeometry(poseStack, renderType(),
				(pose, buffer) -> drawNodes(level, clip, matrices, pose.pose(), buffer, origin, yaw));
	}

	private static RenderType renderType() {
		if (renderType == null) {
			renderType = RenderTypes.entitySolid(WHITE);
		}
		return renderType;
	}

	private static float yawOf(BlockState state) {
		if (state.hasProperty(ProjectorBlock.FACING)) {
			return state.getValue(ProjectorBlock.FACING).toYRot();
		}
		return 0.0f;
	}

	/** Minecraft yaw convention: +Z at 0 degrees, -X at 90 degrees. */
	private static Matrix4f yawMatrix(float yawDegrees) {
		double angle = Math.toRadians(yawDegrees);
		float cos = (float) Math.cos(angle);
		float sin = (float) Math.sin(angle);
		return new Matrix4f(
				cos, 0.0f, -sin, 0.0f,
				0.0f, 1.0f, 0.0f, 0.0f,
				sin, 0.0f, cos, 0.0f,
				0.0f, 0.0f, 0.0f, 1.0f);
	}

	private static void drawNodes(ClientLevel level, AnimationClip clip, Matrix4f[] matrices,
			Matrix4f basePose, VertexConsumer buffer, Vec3 origin, float yaw) {
		Vector3f a = new Vector3f();
		Vector3f b = new Vector3f();
		Vector3f c = new Vector3f();
		Vector3f d = new Vector3f();

		for (int index = 0; index < clip.nodes().size(); index++) {
			Matrix4f matrix = new Matrix4f(basePose).mul(matrices[index]);

			// Sample the world light at the centre of this node's geometry, so scenes sitting
			// in a dark room are dark while outdoor scenes are fully lit. Using the node origin
			// would be wrong for bones whose parts are offset far from the pivot.
			Vector3f nodeCenter = new Vector3f();
			int partCount = 0;
			for (AnimationClip.Part part : clip.nodes().get(index).parts()) {
				nodeCenter.add(part.from()).add(part.to()).mul(0.5f);
				partCount++;
			}
			if (partCount > 0) {
				nodeCenter.mul(1.0f / partCount);
			}
			matrices[index].transformPosition(nodeCenter);
			Vec3 worldPosition = origin.add(CineCorderMath.rotateYaw(yaw,
					new Vec3(nodeCenter.x, nodeCenter.y, nodeCenter.z))).add(0.0, 1.0, 0.0);
			int light = LightCoordsUtil.getLightCoords(level, BlockPos.containing(worldPosition));

			for (AnimationClip.Part part : clip.nodes().get(index).parts()) {
				drawBox(buffer, matrix, part, light, a, b, c, d);
			}
		}
	}

	private static void drawBox(VertexConsumer buffer, Matrix4f matrix, AnimationClip.Part part, int light,
			Vector3f a, Vector3f b, Vector3f c, Vector3f d) {
		Vector3f min = part.from();
		Vector3f max = part.to();
		float cx = (min.x + max.x) * 0.5f;
		float cy = (min.y + max.y) * 0.5f;
		float cz = (min.z + max.z) * 0.5f;
		float hx = (max.x - min.x) * 0.5f;
		float hy = (max.y - min.y) * 0.5f;
		float hz = (max.z - min.z) * 0.5f;
		int color = part.argb();

		// +X
		face(buffer, matrix, color, light,
				cx + hx, cy, cz, 0.0f, hy, 0.0f, 0.0f, 0.0f, hz, a, b, c, d);
		// -X
		face(buffer, matrix, color, light,
				cx - hx, cy, cz, 0.0f, 0.0f, hz, 0.0f, hy, 0.0f, a, b, c, d);
		// +Y
		face(buffer, matrix, color, light,
				cx, cy + hy, cz, 0.0f, 0.0f, hz, hx, 0.0f, 0.0f, a, b, c, d);
		// -Y
		face(buffer, matrix, color, light,
				cx, cy - hy, cz, hx, 0.0f, 0.0f, 0.0f, 0.0f, hz, a, b, c, d);
		// +Z
		face(buffer, matrix, color, light,
				cx, cy, cz + hz, hx, 0.0f, 0.0f, 0.0f, hy, 0.0f, a, b, c, d);
		// -Z
		face(buffer, matrix, color, light,
				cx, cy, cz - hz, 0.0f, hy, 0.0f, hx, 0.0f, 0.0f, a, b, c, d);
	}

	/**
	 * Emits one quad. {@code u} and {@code v} are half-extent vectors whose cross product is the
	 * outward normal, which keeps the winding counter-clockwise (Minecraft's front face).
	 */
	private static void face(VertexConsumer buffer, Matrix4f matrix, int color, int light,
			float cx, float cy, float cz,
			float ux, float uy, float uz,
			float vx, float vy, float vz,
			Vector3f a, Vector3f b, Vector3f c, Vector3f d) {
		a.set(cx - ux - vx, cy - uy - vy, cz - uz - vz);
		b.set(cx + ux - vx, cy + uy - vy, cz + uz - vz);
		c.set(cx + ux + vx, cy + uy + vy, cz + uz + vz);
		d.set(cx - ux + vx, cy - uy + vy, cz - uz + vz);

		matrix.transformPosition(a);
		matrix.transformPosition(b);
		matrix.transformPosition(c);
		matrix.transformPosition(d);

		Vector3f normal = new Vector3f(b).sub(a).cross(new Vector3f(c).sub(a));
		if (normal.lengthSquared() < 1.0e-8f) {
			return;
		}
		normal.normalize();

		buffer.addVertex(a.x, a.y, a.z, color, 0.0f, 1.0f, OverlayTexture.NO_OVERLAY, light,
				normal.x, normal.y, normal.z);
		buffer.addVertex(b.x, b.y, b.z, color, 1.0f, 1.0f, OverlayTexture.NO_OVERLAY, light,
				normal.x, normal.y, normal.z);
		buffer.addVertex(c.x, c.y, c.z, color, 1.0f, 0.0f, OverlayTexture.NO_OVERLAY, light,
				normal.x, normal.y, normal.z);
		buffer.addVertex(d.x, d.y, d.z, color, 0.0f, 0.0f, OverlayTexture.NO_OVERLAY, light,
				normal.x, normal.y, normal.z);
	}
}
