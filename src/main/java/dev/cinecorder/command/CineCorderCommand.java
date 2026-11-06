package dev.cinecorder.command;

import java.util.List;
import java.util.stream.Collectors;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;

import dev.cinecorder.ModRegistry;
import dev.cinecorder.block.AnimationTableBlockEntity;
import dev.cinecorder.block.ProjectorBlockEntity;
import dev.cinecorder.net.CineCorderNetworking;
import dev.cinecorder.anim.AnimationClip;
import dev.cinecorder.anim.AnimationException;
import dev.cinecorder.anim.AnimationLibrary;
import dev.cinecorder.anim.AnimationParser;
import dev.cinecorder.anim.AnimationStore;
import dev.cinecorder.anim.BlendConverter;
import dev.cinecorder.item.CassetteData;
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
				.then(Commands.literal("rebuild")
						.requires(Commands.<CommandSourceStack>hasPermission(Commands.LEVEL_GAMEMASTERS))
						.executes(context -> rebuild(context.getSource())))
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
				.then(Commands.literal("open")
						.requires(Commands.<CommandSourceStack>hasPermission(Commands.LEVEL_GAMEMASTERS))
						.then(Commands.argument("pos", BlockPosArgument.blockPos())
								.executes(context -> open(context.getSource(),
										BlockPosArgument.getLoadedBlockPos(context, "pos")))))
				.then(Commands.literal("record")
						.requires(Commands.<CommandSourceStack>hasPermission(Commands.LEVEL_GAMEMASTERS))
						.then(Commands.argument("pos", BlockPosArgument.blockPos())
								.then(Commands.argument("name", StringArgumentType.greedyString())
										.executes(context -> record(context.getSource(),
												BlockPosArgument.getLoadedBlockPos(context, "pos"),
												StringArgumentType.getString(context, "name"))))))
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

	private static int open(CommandSourceStack source, BlockPos pos) {
		try {
			ServerPlayer player = source.getPlayerOrException();
			if (source.getLevel().getBlockEntity(pos) instanceof AnimationTableBlockEntity table) {
				CineCorderNetworking.openTable(player, table);
				return 1;
			}
			source.sendFailure(Component.translatable("cinecorder.command.no_table"));
		} catch (Exception e) {
			source.sendFailure(Component.translatable("cinecorder.command.no_table"));
		}
		return 0;
	}

	private static int rebuild(CommandSourceStack source) {
		AnimationLibrary.reload();
		BlendConverter.clearErrors();
		AnimationLibrary.requestBlends();
		int rebuilt = AnimationStore.rebuild(false);
		source.sendSuccess(() -> Component.translatable("cinecorder.command.rebuilt", rebuilt), false);
		source.sendSuccess(() -> Component.translatable("cinecorder.command.rebuild_pending"), false);
		return rebuilt;
	}

	private static int reload(CommandSourceStack source) {
		AnimationLibrary.reload();
		int count = AnimationLibrary.files().size();
		source.sendSuccess(() -> Component.translatable("cinecorder.command.reloaded", count), false);
		return count;
	}

	private static int record(CommandSourceStack source, BlockPos pos, String name) {
		try {
			ServerPlayer player = source.getPlayerOrException();
			if (source.getLevel().getBlockEntity(pos) instanceof AnimationTableBlockEntity table) {
				return table.record(player, name) ? 1 : 0;
			}
			source.sendFailure(Component.translatable("cinecorder.command.no_table"));
		} catch (Exception e) {
			source.sendFailure(Component.translatable("cinecorder.command.no_table"));
		}
		return 0;
	}

	private static int give(CommandSourceStack source, String file) {
		try {
			ServerPlayer player = source.getPlayerOrException();
			String json = AnimationLibrary.read(file);
			AnimationClip clip = AnimationParser.parse(json, file);
			String id = AnimationStore.store(json, file);
			ItemStack cassette = new ItemStack(ModRegistry.ANIMATION_CASSETTE);
			CassetteData.write(cassette, id, file, clip);
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
