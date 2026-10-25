package cn.pixelmc.sdmshopbulk.client;

import cn.pixelmc.sdmshopbulk.PurchaseCargo;
import cn.pixelmc.sdmshopbulk.ShopBulkDrops;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

public final class CargoRenderer extends EntityRenderer<PurchaseCargo> {
    private final ItemRenderer items;
    public CargoRenderer(EntityRendererProvider.Context context) {
        super(context);
        items = context.getItemRenderer();
        shadowRadius = 0.15F;
    }

    @Override public void render(PurchaseCargo cargo, float yaw, float partialTick, PoseStack pose,
            MultiBufferSource buffers, int light) {
        if (cargo.amount() <= 0 || cargo.item().isEmpty()) return;
        pose.pushPose();
        pose.translate(0, 0.15 + Math.sin((cargo.tickCount + partialTick) * 0.1) * 0.06, 0);
        pose.mulPose(Axis.YP.rotationDegrees((cargo.tickCount + partialTick) * 3));
        items.renderStatic(cargo.item(), ItemDisplayContext.GROUND, light, OverlayTexture.NO_OVERLAY,
                pose, buffers, cargo.level(), cargo.getId());
        pose.popPose();
        super.render(cargo, yaw, partialTick, pose, buffers, light);
    }

    @Override protected boolean shouldShowName(PurchaseCargo cargo) { return cargo.amount() > 0 && cargo.hasCustomName(); }

    @Override protected void renderNameTag(PurchaseCargo cargo, Component name, PoseStack pose,
            MultiBufferSource buffers, int light) {
        // Quantity is UI information: keep its exact color legible even in unlit areas.
        super.renderNameTag(cargo, name, pose, buffers, LightTexture.FULL_BRIGHT);
    }

    @Override public ResourceLocation getTextureLocation(PurchaseCargo cargo) {
        return net.minecraft.world.inventory.InventoryMenu.BLOCK_ATLAS;
    }

    @Mod.EventBusSubscriber(modid = ShopBulkDrops.ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent public static void register(EntityRenderersEvent.RegisterRenderers event) {
            event.registerEntityRenderer(ShopBulkDrops.CARGO.get(), CargoRenderer::new);
        }
    }
}
