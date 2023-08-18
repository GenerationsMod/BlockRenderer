package com.unascribed.blockrenderer;

import com.google.common.primitives.Doubles;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.GraphicsStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import org.joml.Quaternionf;

public class EntityRenderTask extends RenderTask {
	
	public final Entity entity;
	
	public EntityRenderTask(Entity entity) {
		this.entity = entity;
	}

	@Override
	public String getCategory() {
		return "entities";
	}
	
	@Override
	public Component getPreviewDisplayName() {
		return entity.getDisplayName();
	}
	
	@Override
	public String getDisplayName() {
		return entity.getDisplayName().getString();
	}

	@Override
	public ResourceLocation getId() {
		return entity.getType().builtInRegistryHolder().key().location();
	}

	@Override
	public void renderPreview(GuiGraphics matrices, int x, int y) {
		matrices.pose().pushPose();
		try {
//			RenderSystem.multMatrix(matrices.getLast().getMatrix());
			matrices.pose().translate(x+8, y+8, 0);
			AABB rbb = entity.getBoundingBoxForCulling();
			drawEntity(matrices.pose(), entity, 16/(float)Doubles.max(rbb.getXsize(), rbb.getYsize(), rbb.getZsize()));
		} finally {
			matrices.pose().popPose();
		}
	}

	@Override
	public void render(GuiGraphics matrices, int renderSize) {
		matrices.pose().pushPose();
		try {
			matrices.pose().translate(0.5f, 0.5f, 0);
			AABB rbb = entity.getBoundingBoxForCulling();
			drawEntity(matrices.pose(), entity, 1/(float)Doubles.max(rbb.getXsize(), rbb.getYsize(), rbb.getZsize()));
		} finally {
			matrices.pose().popPose();
		}
	}
	
	public static void drawEntity(PoseStack matrices, Entity entity, float scale) {
		if (entity == null) return;
		float yaw = -45;
		float pitch = 0;
		matrices.pushPose();
		matrices.scale(scale, scale, scale);
		Quaternionf rot = Axis.ZP.rotationDegrees(180f);
		Quaternionf xRot = Axis.XP.rotationDegrees(20f);
		rot.mul(xRot);
		matrices.mulPose(rot);
		float oldYaw = entity.yRotO;
		float oldPitch = entity.xRotO;
		LivingEntity lentity = entity instanceof LivingEntity ? (LivingEntity)entity : null;
		Float oldYawOfs = lentity != null ? lentity.yBodyRot : null;
		Float oldPrevYawHead = lentity != null ? lentity.yHeadRotO : null;
		Float oldYawHead = lentity != null ? lentity.yHeadRot : null;
		entity.yRotO = yaw;
		entity.xRotO = -pitch;
		if (lentity != null) {
			lentity.yBodyRot = yaw;
			lentity.yHeadRotO = entity.yRotO;
			lentity.yHeadRotO = entity.xRotO;
		}
		EntityRenderDispatcher erm = Minecraft.getInstance().getEntityRenderDispatcher();
		xRot.conjugate();
		erm.overrideCameraOrientation(xRot);
		erm.setRenderShadow(false);
		GraphicsStatus oldFanciness = Minecraft.getInstance().options.graphicsMode().get();
		Minecraft.getInstance().options.graphicsMode().set(GraphicsStatus.FANCY);
		try {
			MultiBufferSource.BufferSource buf = Minecraft.getInstance().renderBuffers().bufferSource();
			erm.render(entity, 0, 0, 0, 0, 1, matrices, buf, 0xF000F0);
			buf.endBatch();
		} finally {
			Minecraft.getInstance().options.graphicsMode().set(oldFanciness);
			erm.setRenderShadow(true);
			entity.yRotO = oldYaw;
			entity.xRotO = oldPitch;
			if (lentity != null) {
				lentity.yBodyRot = oldYawOfs;
				lentity.yHeadRotO = oldPrevYawHead;
				lentity.yHeadRot = oldYawHead;
			}
			matrices.popPose();
		}
	}
	
}