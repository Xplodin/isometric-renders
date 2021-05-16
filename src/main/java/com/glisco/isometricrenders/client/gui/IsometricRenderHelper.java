package com.glisco.isometricrenders.client.gui;

import com.glisco.isometricrenders.client.RuntimeConfig;
import com.glisco.isometricrenders.mixin.CameraInvoker;
import com.glisco.isometricrenders.mixin.MinecraftClientAccessor;
import com.glisco.isometricrenders.mixin.NativeImageAccessor;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.model.json.ModelTransformation;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.ScreenshotUtils;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.util.math.Vector3f;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Util;
import net.minecraft.util.collection.DefaultedList;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;

import java.io.File;
import java.nio.IntBuffer;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

public class IsometricRenderHelper {

    public static boolean allowParticles = true;

    private static final DateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd_HH.mm.ss");

    public static void batchRenderItemGroupBlocks(ItemGroup group, boolean insaneResolutions) {
        DefaultedList<ItemStack> stacks = DefaultedList.of();
        group.appendStacks(stacks);

        MinecraftClient.getInstance().openScreen(new BatchIsometricBlockRenderScreen(extractBlocks(stacks), insaneResolutions));
    }

    public static void batchRenderItemGroupItems(ItemGroup group, boolean insaneResolutions) {
        DefaultedList<ItemStack> stacks = DefaultedList.of();
        group.appendStacks(stacks);

        MinecraftClient.getInstance().openScreen(new BatchIsometricItemRenderScreen(stacks.iterator(), insaneResolutions));
    }

    public static void renderItemGroupAtlas(ItemGroup group, int size, int columns, float scale) {
        DefaultedList<ItemStack> stacks = DefaultedList.of();
        group.appendStacks(stacks);
        renderItemAtlas(stacks, size, columns, scale);
    }

    public static void renderItemAtlas(List<ItemStack> stacks, int size, int columns, float scale) {
        ItemAtlasRenderScreen screen = new ItemAtlasRenderScreen();

        screen.setRenderCallback((matrices, vertexConsumerProvider, tickDelta) -> {

            matrices.translate(-0.88 + 0.05, 0.925 - 0.05, 0);
            matrices.scale(0.125f, 0.125f, 1);

            int rows = (int) Math.ceil(stacks.size() / (double) RuntimeConfig.atlasColumns);

            for (int i = 0; i < rows; i++) {

                matrices.push();
                matrices.translate(0, -1.2 * i, 0);

                for (int j = 0; j < RuntimeConfig.atlasColumns; j++) {

                    int index = i * RuntimeConfig.atlasColumns + j;
                    if (index > stacks.size() - 1) continue;

                    ItemStack stack = stacks.get(index);

                    MinecraftClient.getInstance().getItemRenderer().renderItem(stack, ModelTransformation.Mode.GUI, 15728880, OverlayTexture.DEFAULT_UV, matrices, vertexConsumerProvider);

                    matrices.translate(1.2, 0, 0);

                }

                matrices.pop();

            }
        });

        MinecraftClient.getInstance().openScreen(screen);
    }

    public static NativeImage renderIntoImage(int size, RenderCallback renderCallback) {

        Framebuffer framebuffer = new Framebuffer(size, size, true, MinecraftClient.IS_SYSTEM_MAC);

        RenderSystem.pushMatrix();
        RenderSystem.enableBlend();
        RenderSystem.clear(16640, MinecraftClient.IS_SYSTEM_MAC);

        float r = (RuntimeConfig.backgroundColor >> 16) / 255f;
        float g = (RuntimeConfig.backgroundColor >> 8 & 0xFF) / 255f;
        float b = (RuntimeConfig.backgroundColor & 0xFF) / 255f;

        framebuffer.setClearColor(r, g, b, 0);
        framebuffer.clear(MinecraftClient.IS_SYSTEM_MAC);

        framebuffer.beginWrite(true);

        RenderSystem.matrixMode(GL11.GL_PROJECTION);
        RenderSystem.pushMatrix();
        RenderSystem.loadIdentity();
        RenderSystem.ortho(-1, 1, 1, -1, -100.0, 100.0);
        RenderSystem.matrixMode(GL11.GL_MODELVIEW);
        RenderSystem.pushMatrix();
        RenderSystem.loadIdentity();

        RenderSystem.color4f(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.setupGuiFlatDiffuseLighting(Util.make(new Vector3f(0.2F, 1.0F, -0.7F), Vector3f::normalize), Util.make(new Vector3f(-0.2F, 1.0F, 0.7F), Vector3f::normalize));

        VertexConsumerProvider.Immediate vertexConsumers = MinecraftClient.getInstance().getBufferBuilders().getEntityVertexConsumers();
        MatrixStack matrixStack = new MatrixStack();

        renderCallback.render(matrixStack, vertexConsumers, ((MinecraftClientAccessor) MinecraftClient.getInstance()).getRenderTickCounter().tickDelta);

        vertexConsumers.draw();

        RenderSystem.popMatrix();
        RenderSystem.matrixMode(GL11.GL_PROJECTION);
        RenderSystem.popMatrix();
        RenderSystem.matrixMode(GL11.GL_MODELVIEW);
        RenderSystem.popMatrix();

        framebuffer.endWrite();

        return takeKeyedSnapshot(framebuffer, RuntimeConfig.backgroundColor, false);
    }

    public static NativeImage takeKeyedSnapshot(Framebuffer framebuffer, int backgroundColor, boolean crop) {
        NativeImage img = ScreenshotUtils.takeScreenshot(0, 0, framebuffer);
        if (framebuffer != MinecraftClient.getInstance().getFramebuffer()) framebuffer.delete();

        int argbColor = backgroundColor | 255 << 24;
        int r = (argbColor >> 16) & 0xFF;
        int b = argbColor & 0xFF;
        int abgrColor = (argbColor & 0xFF00FF00) | (b << 16) | r;

        long pointer = ((NativeImageAccessor) (Object) img).getPointer();

        final IntBuffer buffer = MemoryUtil.memIntBuffer(pointer, (img.getWidth() * img.getHeight()));
        int[] pixelColors = new int[buffer.remaining()];
        buffer.get(pixelColors);
        buffer.clear();

        for (int i = 0; i < pixelColors.length; i++) {
            if (pixelColors[i] == abgrColor) {
                pixelColors[i] = 0;
            }
        }

        buffer.put(pixelColors);
        buffer.clear();

        int i = img.getWidth();
        int j = img.getHeight();
        int k = 0;
        int l = 0;
        if (i > j) {
            k = (i - j) / 2;
            i = j;
        } else {
            l = (j - i) / 2;
            j = i;
        }

        NativeImage rect = new NativeImage(i, i, false);
        if (crop) {
            img.resizeSubRectTo(k, l, i, j, rect);
        } else {
            rect = img;
        }

        return rect;
    }

    public static File getScreenshotFilename(File directory) {
        String string = DATE_FORMAT.format(new Date());
        int i = 1;

        while (true) {
            File file = new File(directory, string + (i == 1 ? "" : "_" + i) + ".png");
            if (!file.exists()) {
                return file;
            }

            ++i;
        }
    }

    public static Camera getParticleCamera() {
        Camera camera = MinecraftClient.getInstance().getEntityRenderDispatcher().camera;
        ((CameraInvoker) camera).invokeSetRotation(RuntimeConfig.rotation, RuntimeConfig.angle);
        return camera;
    }

    public static Iterator<BlockState> extractBlocks(List<ItemStack> stacks) {
        return stacks.stream().filter(itemStack -> !itemStack.isEmpty() && itemStack.getItem() instanceof BlockItem).map(itemStack -> ((BlockItem) itemStack.getItem()).getBlock().getDefaultState()).iterator();
    }

    @FunctionalInterface
    public interface RenderCallback {
        void render(MatrixStack matrices, VertexConsumerProvider vertexConsumerProvider, float tickDelta);
    }

}
