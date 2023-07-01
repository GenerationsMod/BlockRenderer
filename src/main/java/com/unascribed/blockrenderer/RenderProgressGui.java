package com.unascribed.blockrenderer;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Overlay;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FastColor;
import net.minecraft.util.Mth;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class RenderProgressGui extends Overlay {

	private static final int BG = 0xFF263238;
	private static final int BG_NOALPHA = BG & 0xFFFFFF;

	private final Minecraft mc;
	private final int total;
	private final String joined;
	private Component title;
	private List<Component> subTitles = Collections.emptyList();
	private AsyncRenderer renderer;
	private float progress;
	private long fadeOutStart = -1;
	private long fadeInStart = -1;
	private long start = -1;
	private int rendered;
	private RenderTask task = null;
	private boolean canceled;

	public RenderProgressGui(Minecraft mc, int total, String joined) {
		this.mc = mc;
		this.total = total;
		this.joined = joined;
		this.title = Component.translatable("gui.blockrenderer.rendering", this.total, this.joined);
	}

	public void setFutures(List<CompletableFuture<Void>> futures) {
		this.renderer = new AsyncRenderer(futures);
	}

	public void cancel() {
		if (!canceled) {
			canceled = true;
			title = Component.translatable("gui.blockrenderer.render_cancelled", rendered, total, total - rendered);
			subTitles = Collections.emptyList();
			task = null;
			renderer.cancel();
		}
	}

	public void update(RenderTask task) {
		rendered++;
		int remaining = total - rendered;
		if (remaining > 0) {
			if (canceled) {
				//If canceled just update how much actually has been rendered so far
				title = Component.translatable("gui.blockrenderer.render_cancelled", rendered, total, remaining);
			} else {
				//Otherwise update the subtitles and the like
				subTitles = new ArrayList<>();
				subTitles.add(Component.translatable("gui.blockrenderer.progress", rendered, total, remaining));
				subTitles.add(task.getPreviewDisplayName());
				this.task = task;
			}
		} else {
			title = Component.translatable("gui.blockrenderer.rendered", total, joined);
			subTitles = Collections.emptyList();
			this.task = null;
		}
	}

	@Override
	public void render(@Nonnull PoseStack matrix, int mouseX, int mouseY, float partialTicks) {
		int scaledWidth = mc.getWindow().getGuiScaledWidth();
		int scaledHeight = mc.getWindow().getGuiScaledHeight();
		long now = Util.getMillis();
		if (start == -1) {
			start = now;
		}
		if ((renderer.asyncPartDone() || mc.screen != null) && fadeInStart == -1) {
			fadeInStart = now;
		}
		float fadeOutTime = fadeOutStart > -1 ? (now - fadeOutStart) / 1000f : -1;
		float fadeInTime = fadeInStart > -1 ? (now - fadeInStart) / 500f : -1;
		int a;
		if (fadeOutTime >= 1) {
			if (mc.screen != null) {
				mc.screen.render(matrix, 0, 0, partialTicks);
			}
			a = Mth.ceil((1 - Mth.clamp(fadeOutTime - 1, 0, 1)) * 255);
			fill(matrix, 0, 0, scaledWidth, scaledHeight, BG_NOALPHA | a << 24);
		} else {
			if (mc.screen != null && fadeInTime < 1) {
				mc.screen.render(matrix, mouseX, mouseY, partialTicks);
			}
			a = Mth.ceil(Mth.clamp(fadeInTime, 0.15, 1) * 255);
			fill(matrix, 0, 0, scaledWidth, scaledHeight, BG_NOALPHA | a << 24);
		}

		int barSize = (int)(((Math.min(scaledWidth * 0.75, scaledHeight) * 0.25)*4)/2);
		int barPosition = (int)(scaledHeight * 0.8325);
		progress = Mth.clamp(progress * 0.95f + renderer.getActualProgress() * 0.05f, 0, 1);
		renderProgressText(matrix, scaledWidth, scaledHeight, a << 24);
		if (fadeOutTime < 1) {
			drawProgressBar(matrix, scaledWidth / 2 - barSize, barPosition - 5, scaledWidth / 2 + barSize, barPosition + 5, 1 - Mth.clamp(fadeOutTime, 0, 1));
		} else if (fadeOutTime >= 2) {
			mc.setOverlay(null);
		}
		if (fadeOutStart == -1 && renderer.fullyDone() && fadeInTime >= 2) {
			fadeOutStart = Util.getMillis();
			try {
				renderer.checkExceptions();
				mc.gui.getChat().addMessage(Component.translatable("msg.blockrenderer.rendered", total, joined, now - start));
			} catch (Throwable t) {
				BlockRenderer.log.warn("Error joining renderer", t);
			}
			if (mc.screen != null) {
				mc.screen.init(mc, scaledWidth, scaledHeight);
			}
		}
	}

	private void drawProgressBar(PoseStack matrix, int xStart, int yStart, int xEnd, int yEnd, float alpha) {
		int filled = Mth.ceil((xEnd - xStart - 2) * this.progress);
		int a = Math.round(alpha * 255);
		int packed = FastColor.ARGB32.color(a, 255, 255, 255);
		fill(matrix, xStart + 1, yStart, xEnd - 1, yStart + 1, packed);
		fill(matrix, xStart + 1, yEnd, xEnd - 1, yEnd - 1, packed);
		fill(matrix, xStart, yStart, xStart + 1, yEnd, packed);
		fill(matrix, xEnd, yStart, xEnd - 1, yEnd, packed);
		fill(matrix, xStart + 2, yStart + 2, xStart + filled, yEnd - 2, packed);
	}

	private void renderProgressText(PoseStack matrix, int scaledWidth, int scaledHeight, int a) {
		matrix.pushPose();
		matrix.scale(2, 2, 0);
		if (a != 0) {
			drawCenteredString(matrix, mc.font, title, scaledWidth / 4, scaledHeight / 4 - 24, 0xFFFFFF|a);
		}
		int subTitleCount = subTitles.size();
		if (subTitleCount > 0) {
			matrix.scale(0.5F, 0.5F, 0);
			int subTitleX = scaledWidth / 2;
			int subTitleY = scaledHeight / 2;
			if (a != 0) {
				for (int i = 0; i < subTitleCount; i++) {
					drawCenteredString(matrix, mc.font, subTitles.get(i), subTitleX, subTitleY + 20 * (i - 1), 0xFFFFFF|a);
				}
			}
			if (task != null) {
				task.renderPreview(matrix, subTitleX - 8, subTitleY + 20 * (subTitleCount - 1));
			}
		}
		matrix.popPose();
	}
}