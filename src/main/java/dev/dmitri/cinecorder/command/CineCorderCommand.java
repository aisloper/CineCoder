package dev.dmitri.cinecorder.command;

import java.util.List;
import java.util.stream.Collectors;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;

import dev.dmitri.cinecorder.ModRegistry;
import dev.dmitri.cinecorder.block.ProjectorBlockEntity;
import dev.dmitri.cinecorder.anim.AnimationClip;
import dev.dmitri.cinecorder.anim.AnimationException;
import dev.dmitri.cinecorder.anim.AnimationLibrary;
import dev.dmitri.cinecorder.anim.AnimationParser;
import dev.dmitri.cinecorder.item.CassetteData;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.item.ItemStack;

/** {@code /cinecorder list|reload|give <file>} */
public final class CineCorderCommand {
	private CineCorderCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext buildContext,
			Commands.CommandSelection selection) {
		dispatcher.register(Commands.literal("cinecorder")
				.then(Commands.literal("list").executes(context -> list(context.getSource())))
				.then(Commands.literal("reload")
						.requires(Commands.<CommandSourceStack>hasPermission(Commands.LEVEL_GAMEMASTERS))
						.executes(context -> reload(context.getSource())))
				.then(Commands.literal("play")
						.requires(Commands.<CommandSourceStack>hasPermission(Commands.LEVEL_GAMEMASTERS))
						.then(Commands.argument("pos", BlockPosArgument.blockPos())
								.executes(context -> play(context.getSource(),
										BlockPosArgument.getLoadedBlockPos(context, "pos")))))
				.then(Commands.literal("stop")
						.requires(Commands.<CommandSourceStack>hasPermission(Commands.LEVEL_GAMEMASTERS))
						.then(Commands.argument("pos", BlockPosArgument.blockPos())
								.executes(context -> stopProjection(context.getSource(),
										BlockPosArgument.getLoadedBlockPos(context, "pos")))))
				.then(Commands.literal("give")
						.requires(Commands.<CommandSourceStack>hasPermission(Commands.LEVEL_GAMEMASTERS))
						.then(Commands.argument("file", StringArgumentType.greedyString())
								.executes(context -> give(context.getSource(),
										StringArgumentType.getString(context, "file"))))));
	}

	private static int list(CommandSourceStack source) {
		List<String> files = AnimationLibrary.files();
		if (files.isEmpty()) {
			source.sendSuccess(() -> Component.translatable("cinecorder.command.empty",
					AnimationLibrary.directory().toString()).withStyle(ChatFormatting.RED), false);
			return 0;
		}
		String joined = files.stream().map(name -> "§b" + name + "§r").collect(Collectors.joining(", "));
		source.sendSuccess(() -> Component.translatable("cinecorder.command.list", files.size(), joined), false);
		return files.size();
	}

	private static int play(CommandSourceStack source, BlockPos pos) {
		try {
			ServerPlayer player = source.getPlayerOrException();
			if (source.getLevel().getBlockEntity(pos) instanceof ProjectorBlockEntity projector) {
				projector.start(player);
				source.sendSuccess(() -> Component.translatable("cinecorder.command.playing"), false);
				return 1;
			}
			source.sendFailure(Component.translatable("cinecorder.command.no_projector"));
		} catch (Exception e) {
			source.sendFailure(Component.translatable("cinecorder.command.no_projector"));
		}
		return 0;
	}

	private static int stopProjection(CommandSourceStack source, BlockPos pos) {
		if (source.getLevel().getBlockEntity(pos) instanceof ProjectorBlockEntity projector) {
			projector.stop();
			source.sendSuccess(() -> Component.translatable("cinecorder.command.stopped"), false);
			return 1;
		}
		source.sendFailure(Component.translatable("cinecorder.command.no_projector"));
		return 0;
	}

	private static int reload(CommandSourceStack source) {
		AnimationLibrary.reload();
		int count = AnimationLibrary.files().size();
		source.sendSuccess(() -> Component.translatable("cinecorder.command.reloaded", count), false);
		return count;
	}

	private static int give(CommandSourceStack source, String file) {
		try {
			ServerPlayer player = source.getPlayerOrException();
			String json = AnimationLibrary.read(file);
			AnimationClip clip = AnimationParser.parse(json, file);
			ItemStack cassette = new ItemStack(ModRegistry.ANIMATION_CASSETTE);
			CassetteData.write(cassette, clip, json);
			if (!player.getInventory().add(cassette)) {
				player.drop(cassette, false, Prediction.SERVER_ONLY);
			}
			source.sendSuccess(() -> Component.translatable("cinecorder.command.gave", clip.name()), false);
			return 1;
		} catch (AnimationException e) {
			source.sendFailure(Component.translatable("cinecorder.command.broken", file, e.getMessage()));
		} catch (Exception e) {
			source.sendFailure(Component.translatable("cinecorder.command.not_found", file));
		}
		return 0;
	}
}
