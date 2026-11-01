package dev.dmitri.cinecorder.item;

import java.util.function.Consumer;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

/** A cassette that carries a recorded 3D animation. */
public class AnimationCassetteItem extends Item {
	public AnimationCassetteItem(Properties properties) {
		super(properties);
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
			Consumer<Component> tooltip, TooltipFlag flag) {
		CassetteData.read(stack).ifPresent(content -> {
			tooltip.accept(Component.translatable("cinecorder.cassette.title", content.title())
					.withStyle(ChatFormatting.AQUA));
			tooltip.accept(Component.translatable("cinecorder.cassette.duration",
					String.format("%.2f", content.ticks() / 20.0)).withStyle(ChatFormatting.GRAY));
			if (content.loop()) {
				tooltip.accept(Component.translatable("cinecorder.cassette.loop")
						.withStyle(ChatFormatting.DARK_GRAY));
			}
		});
	}
}
