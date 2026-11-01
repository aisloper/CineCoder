package dev.dmitri.cinecorder.block;

import java.util.List;

import dev.dmitri.cinecorder.anim.AnimationLibrary;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** Remembers which animation is selected on an {@link AnimationTableBlock}. */
public class AnimationTableBlockEntity extends BlockEntity {
	private int index;

	public AnimationTableBlockEntity(BlockPos pos, BlockState state) {
		super(dev.dmitri.cinecorder.ModRegistry.TABLE_BE, pos, state);
	}

	public String selectedFile() {
		List<String> files = AnimationLibrary.files();
		if (files.isEmpty()) {
			return null;
		}
		return files.get(Math.floorMod(this.index, files.size()));
	}

	public int selectionIndex() {
		return this.index;
	}

	public int fileCount() {
		return AnimationLibrary.files().size();
	}

	public void cycle() {
		this.index++;
		setChanged();
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
