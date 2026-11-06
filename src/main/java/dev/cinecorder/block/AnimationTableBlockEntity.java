package dev.cinecorder.block;

import java.util.List;

import dev.cinecorder.CineCorder;
import dev.cinecorder.ModRegistry;
import dev.cinecorder.anim.AnimationClip;
import dev.cinecorder.anim.AnimationException;
import dev.cinecorder.anim.AnimationLibrary;
import dev.cinecorder.anim.AnimationParser;
import dev.cinecorder.anim.AnimationStore;
import dev.cinecorder.item.CassetteData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Prediction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** Remembers the selected animation and copies animations onto cassettes. */
public class AnimationTableBlockEntity extends BlockEntity {
	private int index;

	public AnimationTableBlockEntity(BlockPos pos, BlockState state) {
		super(ModRegistry.TABLE_BE, pos, state);
	}

	public List<String> files() {
		return AnimationLibrary.files();
	}

	public int fileCount() {
		return AnimationLibrary.files().size();
	}

	public int selectionIndex() {
		return this.index;
	}

	public String selectedFile() {
		List<String> files = AnimationLibrary.files();
		if (files.isEmpty()) {
			return null;
		}
		return files.get(Math.floorMod(this.index, files.size()));
	}

	public void setSelected(String name) {
		int position = AnimationLibrary.files().indexOf(name);
		if (position >= 0) {
			this.index = position;
			setChanged();
		}
	}

	public void cycle() {
		this.index++;
		setChanged();
	}

	/** Copies the named animation onto the blank cassette the player is holding. */
	public boolean record(ServerPlayer player, String name) {
		CineCorder.LOGGER.debug("Record request '{}' from {} (main hand: {})",
				name, player.getName().getString(), player.getMainHandItem());
		Held held = findBlankCassette(player);
		if (held == null) {
			player.sendSystemMessage(Component.translatable("cinecorder.table.need_blank")
					.withStyle(ChatFormatting.RED));
			return false;
		}

		try {
			String json = AnimationLibrary.read(name);
			AnimationClip clip = AnimationParser.parse(json, name);
			String id = AnimationStore.store(json, name);
			ItemStack recorded = new ItemStack(ModRegistry.ANIMATION_CASSETTE);
			CassetteData.write(recorded, id, name, clip);

			if (held.stack().getCount() > 1) {
				held.stack().shrink(1);
				if (!player.getInventory().add(recorded)) {
					player.drop(recorded, false, Prediction.SERVER_ONLY);
				}
			} else {
				player.setItemInHand(held.hand(), recorded);
			}

			setSelected(name);
			if (this.level != null) {
				this.level.playSound(null, this.worldPosition, SoundEvents.UI_BUTTON_CLICK.value(),
						SoundSource.BLOCKS, 1.0f, 1.4f);
			}
			player.sendSystemMessage(Component.translatable("cinecorder.table.recorded", clip.name(),
					String.format("%.2f", clip.duration())).withStyle(ChatFormatting.GREEN));
			return true;
		} catch (AnimationException e) {
			player.sendSystemMessage(Component.translatable("cinecorder.table.convert_wait", e.getMessage())
					.withStyle(ChatFormatting.YELLOW));
		} catch (Exception e) {
			player.sendSystemMessage(Component.translatable("cinecorder.table.read_error", name)
					.withStyle(ChatFormatting.RED));
		}
		return false;
	}

	private static Held findBlankCassette(ServerPlayer player) {
		for (InteractionHand hand : InteractionHand.values()) {
			ItemStack stack = player.getItemInHand(hand);
			if (stack.is(ModRegistry.BLANK_CASSETTE)) {
				return new Held(hand, stack);
			}
		}
		return null;
	}

	private record Held(InteractionHand hand, ItemStack stack) {
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		output.putInt("Index", this.index);
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		this.index = input.getIntOr("Index", 0);
	}
}
