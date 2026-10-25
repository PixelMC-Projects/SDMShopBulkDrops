package cn.pixelmc.sdmshopbulk;

import com.mojang.logging.LogUtils;
import java.util.Comparator;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.sixik.sdmshoprework.api.shop.AbstractShopEntry;

public final class BulkPurchase {
    public static final int THRESHOLD = 128;

    public static boolean validNumbers(int purchases, AbstractShopEntry entry) {
        if (purchases <= 0 || entry.entryCount <= 0 || entry.entryPrice < 0) return false;
        try {
            return Math.multiplyExact((long) purchases, entry.entryCount) > 0
                    && Math.multiplyExact((long) purchases, entry.entryPrice) >= 0;
        } catch (ArithmeticException overflow) { return false; }
    }

    public static long ordinaryEntityCount(int purchases, int perPurchase, long inserted) {
        long total = Math.multiplyExact((long) purchases, perPurchase);
        return inserted >= total ? 0 : purchases - inserted / perPurchase;
    }

    /** True means handled/rejected; false preserves SDM's original small-purchase behaviour. */
    public static boolean handle(ServerPlayer player, ItemStack prototype, int purchases, AbstractShopEntry entry) {
        if (!validNumbers(purchases, entry) || prototype.isEmpty()) {
            player.displayClientMessage(Component.translatable("message.sdmshopbulk.invalid"), false);
            return true;
        }
        long total = Math.multiplyExact((long) purchases, entry.entryCount);
        MainInventoryPlan beforePayment = new MainInventoryPlan(player.getInventory(), prototype, total);
        if (ordinaryEntityCount(purchases, entry.entryCount, beforePayment.inserted) < THRESHOLD) return false;
        long price = Math.multiplyExact((long) purchases, entry.entryPrice);
        if (entry.shopSellerType.getCount(player) < price) {
            player.displayClientMessage(Component.translatable("message.sdmshopbulk.failed"), false);
            return true;
        }
        // Use only nearby shop cargo owned by this buyer, never any ItemEntity.
        PurchaseCargo cargo = player.serverLevel().getEntitiesOfClass(PurchaseCargo.class,
                player.getBoundingBox().inflate(3), entity -> !entity.isRemoved()
                        && player.getUUID().equals(entity.owner()) && MainInventoryPlan.sameItemData(entity.item(), prototype)
                        && entity.amount() <= Long.MAX_VALUE - total)
                .stream().min(Comparator.comparingDouble(player::distanceToSqr)).orElse(null);
        boolean fresh = cargo == null;
        if (fresh) {
            cargo = new PurchaseCargo(ShopBulkDrops.CARGO.get(), player.level());
            cargo.setPos(player.getX(), player.getY() + 0.5, player.getZ());
            cargo.configure(player.getUUID(), prototype, 0);
            // Reserve the one entity before charging. A cancelled spawn cannot eat the payment.
            if (!player.serverLevel().addFreshEntity(cargo)) {
                cargo.destroyCargo();
                player.displayClientMessage(Component.translatable("message.sdmshopbulk.failed"), false);
                return true;
            }
        }
        boolean paid;
        try { paid = entry.shopSellerType.buy(player, entry, price); }
        catch (RuntimeException failure) {
            LogUtils.getLogger().error("Shop currency callback failed for buyer {}; no duplicate retry will be attempted", player.getUUID(), failure);
            if (fresh) cargo.destroyCargo();
            player.displayClientMessage(Component.translatable("message.sdmshopbulk.failed"), false);
            return true;
        }
        if (!paid) {
            if (fresh) cargo.destroyCargo();
            player.displayClientMessage(Component.translatable("message.sdmshopbulk.failed"), false);
            return true;
        }
        long leftover;
        try {
            // Item-currency payments may themselves free main inventory slots.
            MainInventoryPlan delivery = new MainInventoryPlan(player.getInventory(), prototype, total);
            delivery.apply(player.getInventory());
            leftover = delivery.remaining;
        } catch (RuntimeException failure) {
            // Inventory plans roll back before throwing. Keep all paid goods in the reserved cargo.
            LogUtils.getLogger().error("Shop inventory insertion failed; preserving all paid goods as cargo for {}", player.getUUID(), failure);
            leftover = total;
        }
        cargo.setAmount(Math.addExact(cargo.amount(), leftover));
        if (cargo.amount() == 0) cargo.destroyCargo();
        player.containerMenu.broadcastChanges();
        player.displayClientMessage(Component.translatable("message.sdmshopbulk.compressed"), false);
        return true;
    }

    private BulkPurchase() {}
}
