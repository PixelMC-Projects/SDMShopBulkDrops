package cn.pixelmc.sdmshopbulk.mixin;

import cn.pixelmc.sdmshopbulk.BulkPurchase;
import dev.architectury.networking.NetworkManager;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.sixik.sdmshoprework.common.shop.ShopBase;
import net.sixik.sdmshoprework.common.shop.type.ShopItemEntryType;
import net.sixik.sdmshoprework.network.client.SendBuyShopEntryC2S;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = SendBuyShopEntryC2S.class, remap = false)
public abstract class ShopPacketValidationMixin {
    @Shadow @Final private UUID tabUUID;
    @Shadow @Final private UUID entryUUID;
    @Shadow @Final private int count;

    @Inject(method = "handle", at = @At("HEAD"), cancellable = true, remap = false)
    private void rejectOverflow(NetworkManager.PacketContext context, CallbackInfo callback) {
        var tab = ShopBase.SERVER.getShopTab(tabUUID);
        if (tab == null) return;
        var entry = tab.getShopEntry(entryUUID);
        if (entry != null && !entry.isSell && entry.getEntryType() instanceof ShopItemEntryType
                && !BulkPurchase.validNumbers(count, entry)) {
            context.getPlayer().displayClientMessage(Component.translatable("message.sdmshopbulk.invalid"), false);
            callback.cancel();
        }
    }
}
