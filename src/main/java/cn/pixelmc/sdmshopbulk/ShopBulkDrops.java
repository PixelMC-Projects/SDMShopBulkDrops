package cn.pixelmc.sdmshopbulk;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

@Mod(ShopBulkDrops.ID)
public final class ShopBulkDrops {
    public static final String ID = "sdmshopbulk";
    private static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, ID);
    public static final RegistryObject<EntityType<PurchaseCargo>> CARGO = ENTITIES.register("purchase_cargo",
            () -> EntityType.Builder.<PurchaseCargo>of(PurchaseCargo::new, MobCategory.MISC)
                    .sized(0.35F, 0.35F).fireImmune().clientTrackingRange(8).updateInterval(10)
                    .build(ID + ":purchase_cargo"));

    public ShopBulkDrops() { ENTITIES.register(FMLJavaModLoadingContext.get().getModEventBus()); }
}
