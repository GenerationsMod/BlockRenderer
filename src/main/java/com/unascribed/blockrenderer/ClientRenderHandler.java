package com.unascribed.blockrenderer;

import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.io.Files;
import com.mojang.blaze3d.platform.GlStateManager.DestFactor;
import com.mojang.blaze3d.platform.GlStateManager.SourceFactor;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.datafixers.util.Pair;
import net.minecraft.Util;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Overlay;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent.Phase;
import net.minecraftforge.event.TickEvent.RenderTickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.registries.ForgeRegistries;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.*;

public class ClientRenderHandler {

	private static final ScheduledExecutorService SCHEDULER = new ScheduledThreadPoolExecutor(0);
	private static final DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd_HH.mm.ss");

	protected KeyMapping bind;
	protected boolean down = false;
	protected String pendingBulkRender;
	protected int pendingBulkRenderSize;
	protected boolean pendingBulkItems;
	protected boolean pendingBulkEntities;
	protected boolean pendingBulkStructures;
	private float oldZLevel;

	public ClientRenderHandler() {
		bind = new KeyMapping("key.blockrenderer.render", GLFW.GLFW_KEY_GRAVE_ACCENT, "key.blockrenderer.category");
		MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGHEST, this::onFrameStart);
	}

	public void onFrameStart(RenderTickEvent e) {
		//Quick primer: OpenGL is double-buffered. This means, where we draw to is
		// /not/ on the screen. As such, we are free to do whatever we like before
		// Minecraft renders, as long as we put everything back the way it was.
		if (e.phase == Phase.START) {
			Minecraft mc = Minecraft.getInstance();
			Overlay loadingGui = mc.getOverlay();
			if (loadingGui instanceof RenderProgressGui && InputConstants.isKeyDown(Minecraft.getInstance().getWindow().getWindow(), GLFW.GLFW_KEY_ESCAPE)) {
				//If we are currently rendering our progress bar and the user hit escapes, cancel the bulk rendering
				((RenderProgressGui) loadingGui).cancel();
				return;
			}
			if (pendingBulkRender != null) {
				//We *must* call render code in pre-render. If we don't, it won't work right.
				bulkRender(pendingBulkRender, pendingBulkRenderSize, pendingBulkItems, pendingBulkEntities, pendingBulkStructures);
				pendingBulkRender = null;
			}
			if (isKeyDown(bind)) {
				if (!down) {
					down = true;
					if (mc.level == null) {
						mc.gui.getChat().addMessage(Component.translatable("msg.blockrenderer.no_world"));
						return;
					}
					Slot hovered = null;
					Screen currentScreen = mc.screen;
					if (currentScreen instanceof ChatScreen) return;
					if (currentScreen instanceof AbstractContainerScreen<?>) {
						hovered = ((AbstractContainerScreen<?>) currentScreen).getSlotUnderMouse();
					}

					if (Screen.hasControlDown()) {
						String modId = null;
						if (hovered != null && hovered.hasItem()) {
							modId = hovered.getItem().getItem().builtInRegistryHolder().key().location().getNamespace();
						}
						mc.setScreen(new GuiConfigureRender(mc.screen, modId));
					} else if (currentScreen instanceof AbstractContainerScreen<?>) {
						if (hovered == null) {
							mc.gui.getChat().addMessage(Component.translatable("msg.blockrenderer.slot.absent"));
						} else {
							ItemStack stack = hovered.getItem();
							if (stack.isEmpty()) {
								mc.gui.getChat().addMessage(Component.translatable("msg.blockrenderer.slot.empty"));
							} else {
								int size = 512;
								if (Screen.hasShiftDown()) {
									size = (int) (16 * mc.getWindow().getGuiScale());
								}
								mc.gui.getChat().addMessage(render(mc, new ItemRenderTask(stack), size, new File("renders/items"), true));
							}
						}
					} else {
						mc.gui.getChat().addMessage(Component.translatable("msg.blockrenderer.not_container"));
					}
				}
			} else {
				down = false;
			}
		}
	}

	private void bulkRender(String modidSpec, int size, boolean items, boolean entities, boolean structures) {
		Minecraft mc = Minecraft.getInstance();
		mc.setScreen(new PauseScreen(true));
		Set<String> modIds = Sets.newHashSet();
		for (String str : modidSpec.split(",")) {
			modIds.add(str.trim());
		}
		List<RenderTask> toRender = new ArrayList<>();
		NonNullList<ItemStack> li = NonNullList.create();
		boolean wildcard = modIds.contains("*");
		if (items) {
			for (Entry<ResourceKey<Item>, Item> entry : ForgeRegistries.ITEMS.getEntries()) {
				if (wildcard || modIds.contains(entry.getKey().location().getNamespace())) {
					li.clear();
					Item item = entry.getValue();
					li.add(item.getDefaultInstance());
//					TODO: Figure out how to do this in 1.19.4+
//					CreativeModeTabs group = item.getGroup();
//					if (group == null && item instanceof EnchantedBookItem) {
//						//Vanilla has special handing for filling the enchanted book item's group, so just grab a single enchanted book
//						li.add(new ItemStack(item));
//					} else {
//						try {
//							item.fillItemGroup(group, li);
//						} catch (Throwable t) {
//							BlockRenderer.log.warn("Failed to get renderable items for {} and group {}", item.getRegistryName(), group, t);
//						}
//					}
					for (ItemStack is : li) {
						toRender.add(new ItemRenderTask(is));
					}
				}
			}
		}
		if (entities) {
			for (Entry<ResourceKey<EntityType<?>>, EntityType<?>> entry : ForgeRegistries.ENTITY_TYPES.getEntries()) {
				if (wildcard || modIds.contains(entry.getKey().location().getNamespace())) {
					li.clear();
					EntityType<?> entityType = entry.getValue();
					try {
						Entity e = entityType.create(mc.level);
						if (e == null) continue;
						toRender.add(new EntityRenderTask(e));
					} catch (Throwable t) {
						BlockRenderer.log.warn("Failed to get renderable entity for {}", entry.getKey().registry());
					}
				}
			}
		}
		File folder = new File("renders/" + dateFormat.format(new Date()) + "_" + sanitize(modidSpec) + "/");
		RenderProgressGui progressBar = new RenderProgressGui(mc, toRender.size(), Joiner.on(", ").join(modIds));
		List<CompletableFuture<Void>> futures = new ArrayList<>();
		//Create futures for generating and saving the images for each item
		// we split our items to render into batches, and then delay each batch
		// by batchIndex + 1. This allows us to have our progress bar properly
		// render instead of us freezing the game trying to render all the items
		// during a single tick
		List<List<RenderTask>> batchedLists = Lists.partition(toRender, 10);
		for (int batchIndex = 0, batchedCount = batchedLists.size(); batchIndex < batchedCount; batchIndex++) {
			futures.add(createFuture(batchedLists.get(batchIndex), size, folder, false, batchIndex + 1, progressBar));
		}
		progressBar.setFutures(futures);
		mc.setOverlay(progressBar);
	}

	private PoseStack setUpRenderState(Minecraft mc, int desiredSize) {

//		RenderSystem.pushMatrix();
		//As we render to the back-buffer, we need to cap our render size
		// to be within the window's bounds. If we didn't do this, the results
		// of our readPixels up ahead would be undefined. And nobody likes
		// undefined behavior.
		Window window = mc.getWindow();
		int size = Math.min(Math.min(window.getHeight(), window.getWidth()), desiredSize);

		//Switches from 3D to 2D
		RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
		RenderSystem.backupProjectionMatrix();

		RenderSystem.getProjectionMatrix().identity().setOrtho(0, window.getWidth(), window.getHeight(), 0, 1000, 3000);

//		RenderSystem.matrixMode(GL11.GL_PROJECTION);
//		RenderSystem.loadIdentity();
//		RenderSystem.ortho(0, window.getWidth(), window.getHeight(), 0, 1000, 3000);

		var stack = RenderSystem.getModelViewStack();
		stack.pushPose();

//		RenderSystem.matrixMode(GL11.GL_MODELVIEW);
		stack.setIdentity();
		stack.translate(0.0F, 0.0F, -2000.0F);
		RenderSystem.applyModelViewMatrix();
//		RenderHelper.enableStandardItemLighting();
		Lighting.setupFor3DItems();

		PoseStack posestack1 = new PoseStack();

		float scale = size / (16f);
		posestack1.translate(0, 0, -(scale * 100));

		posestack1.scale(scale, scale, scale);
		posestack1.translate(0, 0, -50);
//		oldZLevel = mc.getItemRenderer().zLevel;
//		mc.getItemRenderer().zLevel = -50;

//		RenderSystem.enableRescaleNormal();
//		RenderSystem.enableColorMaterial();
		RenderSystem.enableDepthTest();
		RenderSystem.enableBlend();
		RenderSystem.blendFuncSeparate(SourceFactor.SRC_ALPHA, DestFactor.ONE_MINUS_SRC_ALPHA, SourceFactor.SRC_ALPHA, DestFactor.ONE);
		RenderSystem.blendFunc(SourceFactor.SRC_ALPHA, DestFactor.ONE_MINUS_SRC_ALPHA);
//		RenderSystem.disableAlphaTest();

		return posestack1;
	}

	private void tearDownRenderState() {
//		RenderSystem.disableLighting();
//		RenderSystem.disableColorMaterial();
		RenderSystem.disableDepthTest();
		RenderSystem.disableBlend();

//		Minecraft.getInstance().getItemRenderer().zLevel = oldZLevel;
		RenderSystem.restoreProjectionMatrix();
		RenderSystem.getModelViewStack().popPose();
	}

	private Component render(Minecraft mc, RenderTask task, int size, File folder, boolean includeDateInFilename) {
		var stack = setUpRenderState(mc, size);
		RenderSystem.clearColor(0, 0, 0, 0);
		RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
		try {
			task.render(stack, size);
		} catch (Throwable t) {
			BlockRenderer.log.warn("Failed to render "+task.getId(), t);
			return Component.translatable("msg.blockrenderer.render.fail");
		}
		BufferedImage image = readPixels(size, size);
		tearDownRenderState();
		// This code would need to be refactored to perform this save off-thread and not block the
		// main thread. This is a problem for later.
		File file = saveImage(image, task, folder, includeDateInFilename);
		return Component.translatable("msg.blockrenderer.render.success", file.getPath());
	}

	private CompletableFuture<Void> createFuture(List<RenderTask> tasks, int size, File folder, boolean includeDateInFilename, int tickDelay, RenderProgressGui progressBar) {
		Executor gameExecutor;
		if (tickDelay == 0) {
			gameExecutor = Minecraft.getInstance();
		} else {
			//Note: We delay our executors by the given number of ticks so that we
			// can allow the progress screen to properly render instead of clogging
			// up the main game thread on rendering all the items
			gameExecutor = r -> SCHEDULER.schedule(() -> Minecraft.getInstance().execute(r), tickDelay * 50, TimeUnit.MILLISECONDS);
		}
		return CompletableFuture.supplyAsync(() -> {
			//Setup the render state for our batch on the main thread,
			// render the entire batch gathering the images for it
			// and revert the render state
			var stack = setUpRenderState(Minecraft.getInstance(), size);
			List<Pair<RenderTask, BufferedImage>> images = new ArrayList<>();
			for (RenderTask task : tasks) {
				RenderSystem.clearColor(0, 0, 0, 0);
				RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
				task.render(stack, size);
				images.add(Pair.of(task, readPixels(size, size)));
				//Update the progress bar
				progressBar.update(task);
			}
			tearDownRenderState();
			return images;
		}, gameExecutor).thenAcceptAsync(images -> {
			//Save images off thread
			int types = 0;
			if (pendingBulkItems) types++;
			if (pendingBulkEntities) types++;
			if (pendingBulkStructures) types++;
			for (Pair<RenderTask, BufferedImage> image : images) {
				saveImage(image.getSecond(), image.getFirst(), types > 1 ? new File(folder, image.getFirst().getCategory()) : folder, includeDateInFilename);
			}
		}, Util.backgroundExecutor());
	}

	private static File saveImage(BufferedImage image, RenderTask task, File folder, boolean includeDateInFilename) {
		try {
			String fileName = (includeDateInFilename ? dateFormat.format(new Date()) + "_" : "") + sanitize(task.getDisplayName());
			File f = new File(folder, fileName + ".png");
			int i = 2;
			while (f.exists()) {
				f = new File(folder, fileName + "_" + i + ".png");
				i++;
			}
			Files.createParentDirs(f);
			f.createNewFile();
			ImageIO.write(image, "PNG", f);
			return f;
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
	}

	private static String sanitize(String str) {
		return str.replaceAll("[^A-Za-z0-9-_ ]", "_");
	}

	private static BufferedImage readPixels(int width, int height) {
		ByteBuffer buf = BufferUtils.createByteBuffer(width * height * 4);
		RenderSystem.readPixels(0, Minecraft.getInstance().getWindow().getHeight() - height, width, height, GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, buf);
		BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		int[] pixels = new int[width * height];
		buf.asIntBuffer().get(pixels);
		// Load the pixels into the BufferedImage, flipped vertically as OpenGL and
		// Minecraft disagree about which way is up
		for (int y = 0; y < height; y++) {
			img.setRGB(0, (height-1)-y, width, 1, pixels, y*width, width);
		}
		return img;
	}

	private static boolean isKeyDown(KeyMapping keyBinding) {
		InputConstants.Key key = keyBinding.getKey();
		int keyCode = key.getValue();
		if (keyCode != InputConstants.UNKNOWN.getValue()) {
			long windowHandle = Minecraft.getInstance().getWindow().getWindow();
			try {
				if (key.getType() == InputConstants.Type.KEYSYM) {
					return InputConstants.isKeyDown(windowHandle, keyCode);
				} else if (key.getType() == InputConstants.Type.MOUSE) {
					return GLFW.glfwGetMouseButton(windowHandle, keyCode) == GLFW.GLFW_PRESS;
				}
			} catch (Exception ignored) {
			}
		}
		return false;
	}
	
}