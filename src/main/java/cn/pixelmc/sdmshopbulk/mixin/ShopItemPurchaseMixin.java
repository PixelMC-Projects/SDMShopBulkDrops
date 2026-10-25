package cn.pixelmc.sdmshopbulk.mixin;

import cn.pixelmc.sdmshopbulk.BulkPurchase;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.sixik.sdmshoprework.api.shop.AbstractShopEntry;
import net.sixik.sdmshoprework.common.shop.type.ShopItemEntryType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ShopItemEntryType.class, remap = false)
public abstract class ShopItemPurchaseMixin {
    @Shadow public ItemStack itemStack;

    @Inject(method = "buy", at = @At("HEAD"), cancellable = true, remap = false)
    private void bulkDelivery(Player player, int purchases, AbstractShopEntry entry, CallbackInfo callback) {
        if (player instanceof ServerPlayer serverPlayer && BulkPurchase.handle(serverPlayer, itemStack, purchases, entry)) callback.cancel();
    }
}
