package dev.cinecorder.block;

import dev.cinecorder.ModRegistry;
import dev.cinecorder.item.CassetteData;
import dev.cinecorder.net.CineCorderNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

/**
 * The animation table. Right-clicking it opens the animation browser; a blank cassette in the
 * player's hand is used as the recording target.
 */
public class AnimationTableBlock extends BaseEntityBlock {
	public AnimationTableBlock(Properties properties) {
		super(properties);
	}

	@Override
	public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new AnimationTableBlockEntity(pos, state);
	}

	@Override
	protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
			Player player, InteractionHand hand, BlockHitResult hitResult) {
		if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) {
			return InteractionResult.SUCCESS;
		}

		if (!(level.getBlockEntity(pos) instanceof AnimationTableBlockEntity table)) {
			return InteractionResult.PASS;
		}

		if (CassetteData.read(stack).isPresent()) {
			CassetteData.read(stack).ifPresent(content -> serverPlayer.sendSystemMessage(
					Component.translatable("cinecorder.table.tape_info", content.title(),
							String.format("%.2f", content.ticks() / 20.0)).withStyle(ChatFormatting.AQUA)));
			return InteractionResult.SUCCESS;
		}

		CineCorderNetworking.openTable(serverPlayer, table);
		return InteractionResult.SUCCESS;
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
			BlockHitResult hitResult) {
		if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer
				&& level.getBlockEntity(pos) instanceof AnimationTableBlockEntity table) {
			CineCorderNetworking.openTable(serverPlayer, table);
		}
		return InteractionResult.SUCCESS;
	}
}
