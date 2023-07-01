package com.unascribed.blockrenderer;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

public class ItemRenderTask extends RenderTask {

	public final ItemStack stack;
	
	public ItemRenderTask(ItemStack stack) {
		this.stack = stack;
	}
	
	@Override
	public String getCategory() {
		return "items";
	}
	
	@Override
	public Component getPreviewDisplayName() {
		return stack.getDisplayName();
	}

	@Override
	public String getDisplayName() {
		return stack.getDisplayName().getString();
	}

	@Override
	public ResourceLocation getId() {
		return stack.getItem().builtInRegistryHolder().key().location();
	}

	@Override
	public void renderPreview(PoseStack matrices, int x, int y) {
		matrices.pushPose();
		Minecraft.getInstance().getItemRenderer().renderAndDecorateItem(matrices, stack, x, y);
		matrices.popPose();
	}

	@Override
	public void render(PoseStack matricies, int renderSize) {
		Minecraft.getInstance().getItemRenderer().renderAndDecorateItem(matricies, stack, 0, 0);
	}

}
