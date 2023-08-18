package com.unascribed.blockrenderer;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

public abstract class RenderTask {

	public abstract String getCategory();
	
	public abstract Component getPreviewDisplayName();
	public abstract String getDisplayName();
	public abstract ResourceLocation getId();
	
	public abstract void renderPreview(GuiGraphics matrices, int x, int y);
	public abstract void render(GuiGraphics matrices, int renderSize);
	
}
