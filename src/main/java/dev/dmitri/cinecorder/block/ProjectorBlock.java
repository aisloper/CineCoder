package dev.dmitri.cinecorder.block;

import dev.dmitri.cinecorder.ModRegistry;
import dev.dmitri.cinecorder.item.AnimationCassetteItem;
import dev.dmitri.cinecorder.item.CassetteData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.util.Prediction;
import org.jspecify.annotations.Nullable;

/** A wall of glass and light: it projects a cassette's animation into the world. */
public class ProjectorBlock extends BaseEntityBlock {
	public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;
	public static final BooleanProperty ACTIVE = BooleanProperty.create("active");

	public ProjectorBlock(Properties properties) {
		super(properties);
		registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(ACTIVE, false));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING, ACTIVE);
	}

	@Override
	public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
		return defaultBlockState()
				.setValue(FACING, context.getHorizontalDirection().getOpposite())
				.setValue(ACTIVE, false);
	}

	@Override
	protected BlockState rotate(BlockState state, Rotation rotation) {
		return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
	}

	@Override
	protected BlockState mirror(BlockState state, Mirror mirror) {
		return state.rotate(mirror.getRotation(state.getValue(FACING)));
	}

	@Override
	protected boolean shouldChangedStateKeepBlockEntity(BlockState oldState) {
		return true;
	}

	@Override
	public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new ProjectorBlockEntity(pos, state);
	}

	@Override
	public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state,
			BlockEntityType<T> type) {
		if (level.isClientSide()) {
			return null;
		}
		return createTickerHelper(type, ModRegistry.PROJECTOR_BE, ProjectorBlockEntity::tick);
	}

	@Override
	protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
			Player player, InteractionHand hand, BlockHitResult hitResult) {
		// The vanilla dispatcher only calls useWithoutItem when useItemOn returns
		// TRY_WITH_EMPTY_HAND. Handle the empty hand here so right-clicking with an empty
		// hand actually plays/stops the projector on both the client and the server.
		if (stack.isEmpty()) {
			return useWithoutItem(state, level, pos, player, hitResult);
		}

		if (!(level.getBlockEntity(pos) instanceof ProjectorBlockEntity projector)) {
			return InteractionResult.PASS;
		}

		if (stack.getItem() instanceof AnimationCassetteItem || CassetteData.read(stack).isPresent()) {
			if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
				if (projector.hasCassette()) {
					serverPlayer.sendOverlayMessage(Component.translatable("cinecorder.projector.occupied"));
					return InteractionResult.SUCCESS;
				}
				projector.insertCassette(stack);
				if (!serverPlayer.getAbilities().instabuild) {
					stack.shrink(1);
				}
				CassetteData.read(projector.cassette()).ifPresent(content -> serverPlayer.sendOverlayMessage(Component.translatable("cinecorder.projector.inserted", content.title())));
			}
			return InteractionResult.SUCCESS;
		}

		if (stack.is(ModRegistry.BLANK_CASSETTE)) {
			if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
				serverPlayer.sendOverlayMessage(Component.translatable("cinecorder.projector.blank_tape"));
			}
			return InteractionResult.SUCCESS;
		}

		return InteractionResult.PASS;
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
			BlockHitResult hitResult) {
		if (!(level.getBlockEntity(pos) instanceof ProjectorBlockEntity projector)) {
			return InteractionResult.PASS;
		}

		if (!(player instanceof ServerPlayer serverPlayer)) {
			return InteractionResult.SUCCESS;
		}

		if (serverPlayer.isShiftKeyDown()) {
			if (projector.hasCassette()) {
				ItemStack cassette = projector.removeCassette();
				if (!serverPlayer.getInventory().add(cassette)) {
					serverPlayer.drop(cassette, false, Prediction.SERVER_ONLY);
				}
				serverPlayer.sendOverlayMessage(Component.translatable("cinecorder.projector.ejected"));
			}
			return InteractionResult.SUCCESS;
		}

		if (projector.isPlaying()) {
			if (projector.isViewer(serverPlayer.getUUID())) {
				projector.removeViewer(serverPlayer);
			} else {
				projector.addViewer(serverPlayer);
			}
		} else {
			projector.start(serverPlayer);
		}

		return InteractionResult.SUCCESS;
	}

	@Override
	protected boolean hasAnalogOutputSignal(BlockState state) {
		return true;
	}

	@Override
	protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos, Direction direction) {
		return level.getBlockEntity(pos) instanceof ProjectorBlockEntity projector && projector.isPlaying() ? 15 : 0;
	}
}
