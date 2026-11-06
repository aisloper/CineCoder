package dev.cinecorder.client;

import java.util.List;

import dev.cinecorder.net.OpenTablePayload;
import dev.cinecorder.net.TableEntry;
import dev.cinecorder.net.TableSelectPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** The animation table file browser. */
public class AnimationTableScreen extends Screen {
	private static final int COLUMNS = 3;
	private static final int ROWS = 5;
	private static final int PAGE_SIZE = COLUMNS * ROWS;
	private static final int BUTTON_WIDTH = 130;
	private static final int BUTTON_HEIGHT = 20;
	private static final int GAP = 4;

	private final OpenTablePayload payload;
	private final List<TableEntry> entries;
	private int page;

	public AnimationTableScreen(OpenTablePayload payload) {
		super(Component.translatable("cinecorder.table.title"));
		this.payload = payload;
		this.entries = payload.entries();
	}

	@Override
	protected void init() {
		int pages = Math.max(1, (this.entries.size() + PAGE_SIZE - 1) / PAGE_SIZE);
		this.page = Math.max(0, Math.min(this.page, pages - 1));

		int gridWidth = COLUMNS * BUTTON_WIDTH + (COLUMNS - 1) * GAP;
		int startX = (this.width - gridWidth) / 2;
		int startY = 54;
		int first = this.page * PAGE_SIZE;

		for (int slot = 0; slot < PAGE_SIZE && first + slot < this.entries.size(); slot++) {
			TableEntry entry = this.entries.get(first + slot);
			int x = startX + (slot % COLUMNS) * (BUTTON_WIDTH + GAP);
			int y = startY + (slot / COLUMNS) * (BUTTON_HEIGHT + GAP);
			Component label = label(entry);
			Button button = Button.builder(label, pressed -> select(entry))
					.bounds(x, y, BUTTON_WIDTH, BUTTON_HEIGHT)
					.build();
			this.addRenderableWidget(button);
		}

		int navY = startY + ROWS * (BUTTON_HEIGHT + GAP) + 8;
		if (this.page > 0) {
			this.addRenderableWidget(Button.builder(Component.translatable("cinecorder.table.prev"),
					pressed -> changePage(-1))
					.bounds(this.width / 2 - 168, navY, 80, 20).build());
		}
		this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), pressed -> this.onClose())
				.bounds(this.width / 2 - 40, navY, 80, 20).build());
		if (this.page < pages - 1) {
			this.addRenderableWidget(Button.builder(Component.translatable("cinecorder.table.next"),
					pressed -> changePage(1))
					.bounds(this.width / 2 + 88, navY, 80, 20).build());
		}
	}

	private static Component label(TableEntry entry) {
		if (!entry.ready()) {
			return Component.literal(entry.title() + "  ")
					.append(Component.translatable("cinecorder.table.converting").withStyle(ChatFormatting.GRAY));
		}
		if (entry.ticks() < 0) {
			return Component.literal(entry.title());
		}
		Component duration = Component.literal(String.format("  %.1fs", entry.seconds()))
				.withStyle(ChatFormatting.DARK_GRAY);
		return Component.literal(entry.title()).append(duration);
	}

	private void changePage(int delta) {
		this.page += delta;
		this.clearWidgets();
		this.init();
	}

	private void select(TableEntry entry) {
		ClientPlayNetworking.send(new TableSelectPayload(this.payload.pos(), entry.name()));
		this.onClose();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		graphics.fill(0, 0, this.width, this.height, 0xB0101016);
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.centeredText(this.font, this.title, this.width / 2, 18, 0xFFFFFFFF);
		if (this.entries.isEmpty()) {
			graphics.centeredText(this.font, Component.translatable("cinecorder.table.no_files"),
					this.width / 2, this.height / 2, 0xFFFF7070);
		}
		int pages = Math.max(1, (this.entries.size() + PAGE_SIZE - 1) / PAGE_SIZE);
		graphics.centeredText(this.font,
				Component.translatable("cinecorder.table.page", this.page + 1, pages, this.entries.size()),
				this.width / 2, this.height - 38, 0xFFAAAAAA);
		graphics.centeredText(this.font, Component.translatable("cinecorder.table.hint"),
				this.width / 2, this.height - 26, 0xFF808080);
	}
}
