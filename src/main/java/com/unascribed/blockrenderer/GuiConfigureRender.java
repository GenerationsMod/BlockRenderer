package com.unascribed.blockrenderer;

import com.google.common.base.Strings;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nonnull;

public class GuiConfigureRender extends Screen {

	private final String prefill;
	private final Screen old;
	private EditBox text;
	private Slider slider;
	private double sliderValue;
	private boolean fixSliderMax;

	public GuiConfigureRender(Screen old, String prefill) {
		super(Component.empty());
		this.old = old;
		this.prefill = Strings.nullToEmpty(prefill);
		this.sliderValue = 512;
	}

	@Override
	public void resize(@Nonnull Minecraft minecraft, int width, int height) {
		String oldText = text.getValue();
		this.init(minecraft, width, height);
		text.setValue(oldText);
		fixSliderMax = true;
	}

	@Override
	public void init() {
//		minecraft.keyboardHandler.enableRepeatEvents(true); TODO: Figure if this is needed
		text = new EditBox(font, width / 2 - 100, height / 6 + 50, 200, 20, Component.empty());
		text.setMaxLength(4096);
		text.setValue(prefill);

		addWidget(Button.builder(Component.translatable("gui.blockrenderer.cancel"), button -> minecraft.setScreen(old)).bounds(width / 2 - 100, height / 6 + 120, 98, 20).build());

		addWidget(Button.builder(Component.translatable("gui.blockrenderer.render"), button -> render()).bounds(width / 2 + 2, height / 6 + 120, 98, 20).build());
		slider = addWidget(new Slider(width / 2 - 100, height / 6 + 80, 200, 20, Component.translatable("gui.blockrenderer.render_size"),
				sliderValue, 16, getSliderMax()));
		
		text.setFocused(true);
		text.setCanLoseFocus(false);
	}

	private int getSliderMax() {
		return Math.min(2048, Math.min(minecraft.getWindow().getWidth(), minecraft.getWindow().getHeight()));
	}

	private int round(double value) {
		int val = (int) value;
		// There's a more efficient method in Mth, but it rounds up. We want the nearest.
		int nearestPowerOfTwo = (int) Math.pow(2, Math.ceil(Math.log(val) / Math.log(2)));
		int minSize = Math.min(minecraft.getWindow().getHeight(), minecraft.getWindow().getWidth());
		if (nearestPowerOfTwo < minSize && Math.abs(val - nearestPowerOfTwo) < 32) {
			val = nearestPowerOfTwo;
		}
		return Math.min(val, minSize);
	}

	@Override
	public void render(@Nonnull PoseStack matrix, int mouseX, int mouseY, float partialTicks) {
		renderBackground(matrix);
		if (text.getValue().isEmpty()) {
			text.setSuggestion(I18n.get("gui.blockrenderer.namespace"));
		} else {
			text.setSuggestion("");
		}
		super.render(matrix, mouseX, mouseY, partialTicks);
		drawCenteredString(matrix, minecraft.font, Component.translatable("gui.blockrenderer.configure"), width / 2, height / 6, -1);
		int displayWidth = minecraft.getWindow().getWidth();
		int displayHeight = minecraft.getWindow().getHeight();
		boolean widthCap = (displayWidth < 2048);
		boolean heightCap = (displayHeight < 2048);
		String translationKey = null;
		if (widthCap && heightCap) {
			if (displayWidth > displayHeight) {
				translationKey = "gui.blockrenderer.capped_height";
			} else if (displayWidth == displayHeight) {
				translationKey = "gui.blockrenderer.capped_both";
			} else { //displayHeight > displayWidth
				translationKey = "gui.blockrenderer.capped_width";
			}
		} else if (widthCap) {
			translationKey = "gui.blockrenderer.capped_width";
		} else if (heightCap) {
			translationKey = "gui.blockrenderer.capped_height";
		}
		if (translationKey != null) {
			drawCenteredString(matrix, minecraft.font, Component.translatable(translationKey, Math.min(displayHeight, displayWidth)),
					width / 2, height / 6 + 104, 0xFFFFFF);
		}
		text.renderWidget(matrix, mouseX, mouseY, partialTicks);
	}

	private void render() {
		if (minecraft.level != null) {
			BlockRenderer.renderHandler.pendingBulkRender = text.getValue();
			BlockRenderer.renderHandler.pendingBulkRenderSize = round(sliderValue);
			BlockRenderer.renderHandler.pendingBulkItems = true;
			BlockRenderer.renderHandler.pendingBulkEntities = false;
			BlockRenderer.renderHandler.pendingBulkStructures = false;
		}
		minecraft.setScreen(old);
	}

	@Override
	public void tick() {
		super.tick();
		text.tick();
		if (fixSliderMax) {//Ugly "hack" because the window's size isn't actually updated yet during init after a resize
			fixSliderMax = false;
			slider.updateSliderMax(getSliderMax());
		}
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (text.canConsumeInput() && keyCode != GLFW.GLFW_KEY_ESCAPE) {//Manually handle hitting escape to make the whole interface go away
			if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
				//Handle processing both the enter key and the numpad enter key
				render();
				return true;
			}
			return text.keyPressed(keyCode, scanCode, modifiers);
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	public boolean charTyped(char c, int keyCode) {
		if (text.canConsumeInput()) {
			return text.charTyped(c, keyCode);
		}
		return super.charTyped(c, keyCode);
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int mouseButton) {
		if (text.isMouseOver(mouseX, mouseY)) {
			return text.mouseClicked(mouseX, mouseY, mouseButton);
		}
		return super.mouseClicked(mouseX, mouseY, mouseButton);
	}

	@Override
	public void onClose() {
		super.onClose();
//		minecraft.keyboardListener.enableRepeatEvents(false);
	}

	private static double normalizeValue(double value, double min, double max) {
		return Mth.clamp((Mth.clamp(value, min, max) - min) / (max - min), 0.0D, 1.0D);
	}

	private class Slider extends AbstractSliderButton {

		private final double minValue;
		private double maxValue;

		public Slider(int x, int y, int width, int height, Component message, double defaultValue, double minValue, double maxValue) {
			super(x, y, width, height, message, normalizeValue(defaultValue, minValue, maxValue));
			this.minValue = minValue;
			this.maxValue = maxValue;
			updateMessage();
		}

		@Override
		protected void updateMessage() {
			setMessage(Component.translatable("gui.blockrenderer.selected_dimensions", round(GuiConfigureRender.this.sliderValue)));
		}

		private double denormalizeValue() {
			return Mth.clamp(Mth.lerp(Mth.clamp(sliderValue, 0.0D, 1.0D), minValue, maxValue), minValue, maxValue);
		}

		@Override
		protected void applyValue() {
			GuiConfigureRender.this.sliderValue = denormalizeValue();
		}

		protected void updateSliderMax(double maxValue) {
			double value = denormalizeValue();
			this.maxValue = maxValue;
			sliderValue = normalizeValue(value, minValue, this.maxValue);
			applyValue();
			updateMessage();
		}
	}
}
