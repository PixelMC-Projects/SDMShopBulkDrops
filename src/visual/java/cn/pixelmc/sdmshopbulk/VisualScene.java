package cn.pixelmc.sdmshopbulk;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Local visual test only; never part of the released mod. */
@Mod.EventBusSubscriber(modid = ShopBulkDrops.ID)
public final class VisualScene {
    private static final List<PurchaseCargo> CARGO = new ArrayList<>();

    @SubscribeEvent public static void joined(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        player.server.getPlayerList().op(player.getGameProfile());
        player.setGameMode(GameType.SPECTATOR);
        player.teleportTo(player.serverLevel(), 0, 100, 1, 180, 0);
        var level = player.serverLevel();
        level.setDayTime(6000);
        level.setWeatherParameters(6000, 0, false, false);
        for (int x = -6; x <= 6; ++x) for (int y = 99; y <= 104; ++y)
            level.setBlockAndUpdate(new BlockPos(x, y, -7), Blocks.BLACK_CONCRETE.defaultBlockState());
        long[] quantities = {4000, 7500, 25000, 75000, 150000};
        for (int i = 0; i < quantities.length; ++i) {
            PurchaseCargo cargo = new PurchaseCargo(ShopBulkDrops.CARGO.get(), level);
            cargo.configure(player.getUUID(), new ItemStack(Items.APPLE), quantities[i]);
            cargo.setNoGravity(true);
            cargo.setPos(-3.2 + i * 1.6, 100.7, -4);
            level.addFreshEntity(cargo);
            CARGO.add(cargo);
        }
        com.mojang.logging.LogUtils.getLogger().info("BULK_VISUAL_SCENE_READY: five cargo entities for local rendering");
    }

    @SubscribeEvent public static void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("bulkvisual").requires(source -> source.hasPermission(2))
                .then(Commands.literal("update").executes(context -> {
                    if (CARGO.size() != 5) return 0;
                    CARGO.get(4).setAmount(5000);
                    return 1;
                })));
    }
}
