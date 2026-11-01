package dev.dmitri.cinecorder;

import java.util.Set;

import dev.dmitri.cinecorder.block.AnimationTableBlock;
import dev.dmitri.cinecorder.block.AnimationTableBlockEntity;
import dev.dmitri.cinecorder.block.ProjectorBlock;
import dev.dmitri.cinecorder.block.ProjectorBlockEntity;
import dev.dmitri.cinecorder.entity.CameraRigEntity;
import dev.dmitri.cinecorder.item.AnimationCassetteItem;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;

/** All game objects added by CineCorder. */
public final class ModRegistry {
	public static final String MOD_ID = "cinecorder";

	public static final Block ANIMATION_TABLE = registerBlock("animation_table", new AnimationTableBlock(
			BlockBehaviour.Properties.of()
					.setId(blockKey("animation_table"))
					.strength(2.5f, 6.0f)
					.sound(SoundType.WOOD)));

	public static final Block PROJECTOR = registerBlock("projector", new ProjectorBlock(
			BlockBehaviour.Properties.of()
					.setId(blockKey("projector"))
					.strength(3.0f, 6.0f)
					.sound(SoundType.METAL)));

	public static final Item BLANK_CASSETTE = registerItem("blank_cassette",
			new Item(new Item.Properties()
					.setId(itemKey("blank_cassette"))
					.stacksTo(16)));

	public static final Item ANIMATION_CASSETTE = registerItem("animation_cassette",
			new AnimationCassetteItem(new Item.Properties()
					.setId(itemKey("animation_cassette"))
					.stacksTo(1)));

	public static final Item ANIMATION_TABLE_ITEM = registerBlockItem("animation_table", ANIMATION_TABLE);
	public static final Item PROJECTOR_ITEM = registerBlockItem("projector", PROJECTOR);

	public static final BlockEntityType<AnimationTableBlockEntity> TABLE_BE = Registry.register(
			BuiltInRegistries.BLOCK_ENTITY_TYPE, id("animation_table"),
			new BlockEntityType<>(AnimationTableBlockEntity::new, Set.of(ANIMATION_TABLE)));

	public static final BlockEntityType<ProjectorBlockEntity> PROJECTOR_BE = Registry.register(
			BuiltInRegistries.BLOCK_ENTITY_TYPE, id("projector"),
			new BlockEntityType<>(ProjectorBlockEntity::new, Set.of(PROJECTOR)));

	public static final EntityType<CameraRigEntity> CAMERA_RIG = registerEntity("camera_rig");

	public static final ResourceKey<CreativeModeTab> CREATIVE_TAB = ResourceKey.create(
			Registries.CREATIVE_MODE_TAB, id("main"));

	private ModRegistry() {
	}

	public static void initialize() {
		Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, CREATIVE_TAB, FabricCreativeModeTab.builder()
				.title(Component.translatable("itemGroup.cinecorder"))
				.icon(() -> new ItemStack(ANIMATION_CASSETTE))
				.displayItems((parameters, output) -> {
					output.accept(ANIMATION_TABLE_ITEM);
					output.accept(PROJECTOR_ITEM);
					output.accept(BLANK_CASSETTE);
					output.accept(ANIMATION_CASSETTE);
				})
				.build());
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	private static ResourceKey<Block> blockKey(String name) {
		return ResourceKey.create(Registries.BLOCK, id(name));
	}

	private static ResourceKey<Item> itemKey(String name) {
		return ResourceKey.create(Registries.ITEM, id(name));
	}

	private static <T extends Block> T registerBlock(String name, T block) {
		return Registry.register(BuiltInRegistries.BLOCK, id(name), block);
	}

	private static Item registerItem(String name, Item item) {
		return Registry.register(BuiltInRegistries.ITEM, id(name), item);
	}

	private static Item registerBlockItem(String name, Block block) {
		ResourceKey<Item> key = itemKey(name);
		Item item = new BlockItem(block, new Item.Properties().useBlockDescriptionPrefix().setId(key));
		return Registry.register(BuiltInRegistries.ITEM, key, item);
	}

	private static EntityType<CameraRigEntity> registerEntity(String name) {
		ResourceKey<EntityType<?>> key = ResourceKey.create(Registries.ENTITY_TYPE, id(name));
		EntityType<CameraRigEntity> type = EntityType.Builder
				.<CameraRigEntity>of(CameraRigEntity::new, MobCategory.MISC)
				.noLootTable()
				.sized(0.1f, 0.1f)
				.clientTrackingRange(16)
				.updateInterval(1)
				.noSave()
				.build(key);
		return Registry.register(BuiltInRegistries.ENTITY_TYPE, key, type);
	}
}
