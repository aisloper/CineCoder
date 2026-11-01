package dev.dmitri.cinecorder.block;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import dev.dmitri.cinecorder.CineCorder;
import dev.dmitri.cinecorder.CineCorderMath;
import dev.dmitri.cinecorder.ModRegistry;
import dev.dmitri.cinecorder.ProjectionRegistry;
import dev.dmitri.cinecorder.anim.AnimationClip;
import dev.dmitri.cinecorder.anim.AnimationException;
import dev.dmitri.cinecorder.entity.CameraRigEntity;
import dev.dmitri.cinecorder.item.CassetteData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetCameraPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

/**
 * Plays a cassette: moves a {@link CameraRigEntity} along the animation camera path and seats
 * viewers in front of it.
 */
public class ProjectorBlockEntity extends BlockEntity {
	private static final double VIEWER_RANGE = 48.0;

	private ItemStack cassette = ItemStack.EMPTY;
	private boolean playing;
	private long startGameTime;
	private int durationTicks = 20;
	private boolean loop;
	private AnimationClip clip;
	private boolean clipParsed;
	private boolean removing;

	// Server-only state.
	private CameraRigEntity rig;
	private final Set<UUID> viewers = new HashSet<>();
	private final Map<UUID, Vec3> seats = new HashMap<>();
	private final Map<UUID, Integer> cameraAttachCountdown = new HashMap<>();

	public ProjectorBlockEntity(BlockPos pos, BlockState state) {
		super(ModRegistry.PROJECTOR_BE, pos, state);
	}

	// ------------------------------------------------------------------ cassette

	public ItemStack cassette() {
		return cassette;
	}

	public boolean hasCassette() {
		return !cassette.isEmpty();
	}

	public void insertCassette(ItemStack stack) {
		this.cassette = stack.copyWithCount(1);
		this.clipParsed = false;
		this.clip = null;
		parseClip();
		if (clip != null) {
			this.durationTicks = clip.durationTicks();
			this.loop = clip.loop();
		}
		sync();
	}

	public ItemStack removeCassette() {
		ItemStack removed = this.cassette;
		this.cassette = ItemStack.EMPTY;
		this.clip = null;
		this.clipParsed = true;
		stop();
		sync();
		return removed;
	}

	public AnimationClip clip() {
		if (!clipParsed) {
			parseClip();
		}
		return clip;
	}

	private void parseClip() {
		this.clipParsed = true;
		this.clip = null;
		if (cassette.isEmpty()) {
			return;
		}
		try {
			this.clip = CassetteData.read(cassette).map(content -> {
				try {
					return content.parse();
				} catch (AnimationException e) {
					CineCorder.LOGGER.warn("Cassette at {} contains a broken animation: {}",
							this.worldPosition, e.getMessage());
					return null;
				}
			}).orElse(null);
		} catch (RuntimeException e) {
			CineCorder.LOGGER.warn("Could not read cassette at {}", this.worldPosition, e);
		}
	}

	// ------------------------------------------------------------------ playback

	public boolean isPlaying() {
		return playing;
	}

	public long startGameTime() {
		return startGameTime;
	}

	public boolean loop() {
		return loop;
	}

	public void start(ServerPlayer player) {
		if (!(this.level instanceof ServerLevel server)) {
			return;
		}

		AnimationClip current = clip();
		if (current == null) {
			player.sendOverlayMessage(net.minecraft.network.chat.Component.translatable("cinecorder.projector.no_tape"));
			return;
		}

		if (this.playing) {
			addViewer(player);
			return;
		}

		this.playing = true;
		this.startGameTime = server.getGameTime();
		setActiveState(true);
		this.durationTicks = current.durationTicks();
		this.loop = current.loop();

		if (this.rig == null) {
			this.rig = new CameraRigEntity(ModRegistry.CAMERA_RIG, server);
			this.rig.setPos(this.worldPosition.getX() + 0.5, this.worldPosition.getY() + 1.0,
					this.worldPosition.getZ() + 0.5);
			server.addFreshEntity(this.rig);
		}

		sync();
		updateCameraRig(server);
		addViewer(player);
		server.playSound(null, this.worldPosition, SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.BLOCKS, 1.0f, 1.2f);
	}

	public void stop() {
		if (!this.playing && this.viewers.isEmpty()) {
			return;
		}

		if (this.level instanceof ServerLevel server) {
			List<UUID> toRelease = new ArrayList<>(this.viewers);
			for (UUID id : toRelease) {
				ServerPlayer player = server.getServer().getPlayerList().getPlayer(id);
				if (player != null) {
					player.connection.send(new ClientboundSetCameraPacket(player));
					player.sendOverlayMessage(net.minecraft.network.chat.Component.translatable("cinecorder.projector.stopped"));
				}
			}
			server.playSound(null, this.worldPosition, SoundEvents.NOTE_BLOCK_BASS.value(), SoundSource.BLOCKS, 1.0f, 0.8f);
		}

		this.viewers.clear();
		this.seats.clear();
		this.cameraAttachCountdown.clear();
		this.playing = false;
		setActiveState(false);

		if (this.rig != null) {
			this.rig.discard();
			this.rig = null;
		}

		sync();
	}

	public void addViewer(ServerPlayer player) {
		if (!(this.level instanceof ServerLevel server) || this.rig == null) {
			return;
		}

		this.viewers.add(player.getUUID());
		this.seats.putIfAbsent(player.getUUID(), player.position());
		// The camera entity packet may race the entity spawn packet, so retry for a few ticks.
		this.cameraAttachCountdown.put(player.getUUID(), 3);
		player.connection.send(new ClientboundSetCameraPacket(this.rig));
		player.sendSystemMessage(net.minecraft.network.chat.Component.translatable("cinecorder.projector.watching",
						net.minecraft.network.chat.Component.translatable("cinecorder.projector.exit_hint")));
	}

	public void removeViewer(ServerPlayer player) {
		this.cameraAttachCountdown.remove(player.getUUID());
		if (this.viewers.remove(player.getUUID())) {
			this.seats.remove(player.getUUID());
			player.connection.send(new ClientboundSetCameraPacket(player));
		}
	}

	public boolean isViewer(UUID uuid) {
		return this.viewers.contains(uuid);
	}

	private float facingYaw() {
		BlockState state = getBlockState();
		if (state.hasProperty(ProjectorBlock.FACING)) {
			return state.getValue(ProjectorBlock.FACING).toYRot();
		}
		return 0.0f;
	}

	private void updateCameraRig(ServerLevel server) {
		AnimationClip current = clip();
		if (this.rig == null || current == null) {
			return;
		}

		Vec3 origin = Vec3.atCenterOf(this.worldPosition);
		double time = Math.max(0.0, (server.getGameTime() - this.startGameTime) / 20.0);
		float yaw = facingYaw();

		if (current.camera() != null && !current.camera().isEmpty()) {
			AnimationClip.CameraPose pose = current.camera().sample(time);
			Vec3 cameraPos = new Vec3(pose.position().x, pose.position().y, pose.position().z);
			Vec3 position = origin.add(CineCorderMath.rotateYaw(yaw, cameraPos));
			this.rig.setPos(position.x, position.y, position.z);
			this.rig.setYRot(pose.yaw() + yaw);
			this.rig.setXRot(pose.pitch());
		} else {
			Vec3 position = origin.add(CineCorderMath.rotateYaw(yaw, new Vec3(0.0, 2.0, 6.0)));
			this.rig.setPos(position.x, position.y, position.z);
			this.rig.setYRot(yaw);
			this.rig.setXRot(10.0f);
		}
	}

	private void keepViewersSeated(ServerLevel server) {
		for (UUID id : new ArrayList<>(this.viewers)) {
			ServerPlayer player = server.getServer().getPlayerList().getPlayer(id);
			if (player == null || player.isRemoved() || player.level() != server
					|| player.isShiftKeyDown()
					|| player.position().distanceToSqr(Vec3.atCenterOf(this.worldPosition))
							> VIEWER_RANGE * VIEWER_RANGE) {
				if (player != null) {
					removeViewer(player);
				} else {
					this.viewers.remove(id);
					this.seats.remove(id);
				}
				continue;
			}

			Vec3 seat = this.seats.get(id);
			if (seat != null && player.position().distanceToSqr(seat) > 0.01) {
				player.teleportTo(seat.x, seat.y, seat.z);
				player.setDeltaMovement(Vec3.ZERO);
			}
		}

	}

	public void serverTick(ServerLevel server) {
		if (!this.playing) {
			return;
		}
		setActiveState(true);

		AnimationClip current = clip();
		if (current == null) {
			stop();
			return;
		}

		if (this.rig == null) {
			this.rig = new CameraRigEntity(ModRegistry.CAMERA_RIG, server);
			this.rig.setPos(this.worldPosition.getX() + 0.5, this.worldPosition.getY() + 1.0,
					this.worldPosition.getZ() + 0.5);
			server.addFreshEntity(this.rig);
		}

		long elapsed = server.getGameTime() - this.startGameTime;
		if (elapsed >= this.durationTicks) {
			if (this.loop) {
				this.startGameTime += this.durationTicks;
			} else {
				stop();
				return;
			}
		}

		updateCameraRig(server);
		refreshCameraAttachments(server);
		keepViewersSeated(server);
	}

	private void refreshCameraAttachments(ServerLevel server) {
		if (this.rig == null || this.cameraAttachCountdown.isEmpty()) {
			return;
		}
		for (UUID id : new ArrayList<>(this.cameraAttachCountdown.keySet())) {
			int left = this.cameraAttachCountdown.getOrDefault(id, 0) - 1;
			ServerPlayer player = server.getServer().getPlayerList().getPlayer(id);
			if (player == null || left <= 0) {
				this.cameraAttachCountdown.remove(id);
				continue;
			}
			player.connection.send(new ClientboundSetCameraPacket(this.rig));
			this.cameraAttachCountdown.put(id, left);
		}
	}

	// ------------------------------------------------------------------ syncing

	private void setActiveState(boolean active) {
		if (this.removing || !(this.level instanceof ServerLevel)) {
			return;
		}
		BlockState state = getBlockState();
		if (state.hasProperty(ProjectorBlock.ACTIVE) && state.getValue(ProjectorBlock.ACTIVE) != active) {
			this.level.setBlock(this.worldPosition, state.setValue(ProjectorBlock.ACTIVE, active), Block.UPDATE_ALL);
		}
	}

	private void sync() {
		if (this.removing) {
			return;
		}
		setChanged();
		if (this.level != null && !this.level.isClientSide()) {
			this.level.sendBlockUpdated(this.worldPosition, getBlockState(), getBlockState(), Block.UPDATE_ALL);
		}
	}

	@Override
	public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
		return saveWithoutMetadata(registries);
	}

	@Override
	public Packet<ClientGamePacketListener> getUpdatePacket() {
		return ClientboundBlockEntityDataPacket.create(this);
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		if (!this.cassette.isEmpty()) {
			output.store("Cassette", ItemStack.CODEC, this.cassette);
		}
		output.putBoolean("Playing", this.playing);
		output.putLong("Start", this.startGameTime);
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		this.cassette = input.read("Cassette", ItemStack.CODEC).orElse(ItemStack.EMPTY);
		this.playing = input.getBooleanOr("Playing", false);
		this.startGameTime = input.getLongOr("Start", 0L);
		this.clipParsed = false;
		parseClip();
		if (this.clip != null) {
			this.durationTicks = this.clip.durationTicks();
			this.loop = this.clip.loop();
		}
		if (this.level != null && this.level.isClientSide()) {
			ProjectionRegistry.add(this);
		}
	}

	@Override
	public void setLevel(Level level) {
		super.setLevel(level);
		if (level.isClientSide()) {
			ProjectionRegistry.add(this);
		}
	}

	@Override
	public void setRemoved() {
		this.removing = true;
		if (this.level != null && !this.level.isClientSide()) {
			stop();
		} else {
			ProjectionRegistry.remove(this);
		}
		super.setRemoved();
	}

	@Override
	public void clearRemoved() {
		super.clearRemoved();
		this.removing = false;
		if (this.level != null && this.level.isClientSide()) {
			ProjectionRegistry.add(this);
		}
	}

	public static void tick(Level level, BlockPos pos, BlockState state, ProjectorBlockEntity projector) {
		if (level instanceof ServerLevel server) {
			projector.serverTick(server);
		}
	}
}
