package dev.dmitri.cinecorder.block;

import dev.dmitri.cinecorder.ModRegistry;
import dev.dmitri.cinecorder.anim.AnimationClip;
import dev.dmitri.cinecorder.anim.AnimationException;
import dev.dmitri.cinecorder.anim.AnimationLibrary;
import dev.dmitri.cinecorder.anim.AnimationParser;
import dev.dmitri.cinecorder.item.CassetteData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

/**
 * The "animation table": it lists the {@code .bcam} files from
 * {@code config/cinecorder/animations} and records the selected one onto a blank cassette.
 *
 * <p>Interaction is intentionally physical (no GUI): right-click cycles through the folder,
 * right-click while holding a blank cassette records the selection.</p>
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
		if (!(level.getBlockEntity(pos) instanceof AnimationTableBlockEntity table)) {
			return InteractionResult.PASS;
		}

		if (level.isClientSide()) {
			return InteractionResult.SUCCESS;
		}

		if (stack.isEmpty()) {
			return InteractionResult.PASS;
		}

		if (CassetteData.read(stack).isPresent()) {
			CassetteData.read(stack).ifPresent(content -> player.sendSystemMessage(Component.translatable("cinecorder.table.tape_info", content.title(),
							String.format("%.2f", content.ticks() / 20.0)).withStyle(ChatFormatting.AQUA)));
			return InteractionResult.SUCCESS;
		}

		if (!stack.is(ModRegistry.BLANK_CASSETTE)) {
			return InteractionResult.PASS;
		}

		String file = table.selectedFile();
		if (file == null) {
			player.sendSystemMessage(Component.translatable("cinecorder.table.no_files")
					.withStyle(ChatFormatting.RED));
			return InteractionResult.SUCCESS;
		}

		try {
			String json = AnimationLibrary.read(file);
			AnimationClip clip = AnimationParser.parse(json, file);
			ItemStack recorded = new ItemStack(ModRegistry.ANIMATION_CASSETTE);
			CassetteData.write(recorded, clip, json);
			if (stack.getCount() > 1) {
				stack.shrink(1);
				if (!player.getInventory().add(recorded)) {
					player.drop(recorded, false, net.minecraft.util.Prediction.SERVER_ONLY);
				}
			} else {
				player.setItemInHand(hand, recorded);
			}

			level.playSound(null, pos, SoundEvents.UI_BUTTON_CLICK.value(), SoundSource.BLOCKS, 1.0f, 1.4f);
			player.sendSystemMessage(Component.translatable("cinecorder.table.recorded", clip.name(),
					String.format("%.2f", clip.duration())).withStyle(ChatFormatting.GREEN));
		} catch (AnimationException e) {
			player.sendSystemMessage(Component.translatable("cinecorder.table.broken", file, e.getMessage())
					.withStyle(ChatFormatting.RED));
		} catch (Exception e) {
			player.sendSystemMessage(Component.translatable("cinecorder.table.read_error", file)
					.withStyle(ChatFormatting.RED));
		}

		return InteractionResult.SUCCESS;
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
			BlockHitResult hitResult) {
		if (level.isClientSide() || !(level.getBlockEntity(pos) instanceof AnimationTableBlockEntity table)) {
			return InteractionResult.SUCCESS;
		}

		table.cycle();
		String file = table.selectedFile();
		if (file == null) {
			player.sendSystemMessage(Component.translatable("cinecorder.table.no_files")
					.withStyle(ChatFormatting.RED));
		} else {
			int count = Math.max(1, table.fileCount());
			player.sendSystemMessage(Component.translatable("cinecorder.table.selected", file,
					Math.floorMod(table.selectionIndex(), count) + 1, count).withStyle(ChatFormatting.YELLOW));
		}

		return InteractionResult.SUCCESS;
	}
}
