package cn.pixelmc.sdmshopbulk;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = ShopBulkDrops.ID, value = Dist.CLIENT)
public final class VisualClient {
    private static boolean connecting;
    private static int stage, frames;
    private static final Path OUTPUT = Path.of("../deployment");

    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (stage == 2 && ++frames > 20) { mc.stop(); return; }
        if (!connecting && mc.screen instanceof TitleScreen && mc.getOverlay() == null) {
            connecting = true;
            ConnectScreen.startConnecting(mc.screen, mc, ServerAddress.parseString("127.0.0.1:25595"),
                    new ServerData("Local shop cargo verification", "127.0.0.1:25595", false), false);
        }
        if (mc.player != null) {
            mc.player.setYRot(180); mc.player.yRotO = 180;
            mc.player.setXRot(0); mc.player.xRotO = 0;
            mc.options.hideGui = true;
        }
    }

    @SubscribeEvent public static void render(TickEvent.RenderTickEvent event) throws Exception {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.getOverlay() != null || mc.screen != null) return;
        List<PurchaseCargo> cargo = new ArrayList<>();
        mc.level.entitiesForRendering().forEach(entity -> { if (entity instanceof PurchaseCargo item) cargo.add(item); });
        if (cargo.size() != 5) return;
        for (PurchaseCargo item : cargo) {
            if (!item.isCurrentlyGlowing() || item.getTeamColor() != 0xFFFF55) throw new AssertionError("Yellow outline metadata missing");
            if (item.getCustomName() == null || item.getCustomName().getStyle().getColor() == null
                    || item.getCustomName().getStyle().getColor().getValue() != PurchaseCargo.labelColor(item.amount()))
                throw new AssertionError("Client quantity label color did not synchronize");
        }
        if (stage == 0) {
            if (++frames < 180) return;
            capture(mc, "shop-cargo-preview.png");
            mc.getConnection().sendCommand("bulkvisual update");
            stage = 1; frames = 0;
        } else if (stage == 1) {
            if (cargo.stream().noneMatch(item -> item.amount() == 5000) || ++frames < 20) return;
            capture(mc, "shop-cargo-updated-preview.png");
            Files.writeString(OUTPUT.resolve("visual-verification.json"), "{\"clientConnectedToLocalServer\":true,\"cargoEntities\":5,\"yellowOutlineMetadata\":true,\"quantityAndColorLiveSync\":true}\n");
            com.mojang.logging.LogUtils.getLogger().info("BULK_VISUAL_PASS: real client/server spawn, yellow outlines, five colors, live quantity update");
            mc.getConnection().sendCommand("stop");
            stage = 2; frames = 0;
        } else if (++frames > 20) mc.stop();
    }

    private static void capture(Minecraft mc, String name) throws Exception {
        Files.createDirectories(OUTPUT);
        try (var image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) { image.writeToFile(OUTPUT.resolve(name)); }
    }
}
