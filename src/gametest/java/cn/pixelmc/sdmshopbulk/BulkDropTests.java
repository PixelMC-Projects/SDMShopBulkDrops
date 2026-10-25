package cn.pixelmc.sdmshopbulk;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.sixik.sdmshoprework.api.shop.AbstractShopEntry;
import net.sixik.sdmshoprework.api.shop.AbstractShopSellerType;
import net.sixik.sdmshoprework.common.shop.ShopEntry;
import net.sixik.sdmshoprework.common.shop.type.ShopItemEntryType;

@GameTestHolder(ShopBulkDrops.ID)
@PrefixGameTestTemplate(false)
public final class BulkDropTests {
    private static final class TestPlayer extends ServerPlayer {
        final List<String> messages = new ArrayList<>();
        TestPlayer(GameTestHelper h) {
            super(h.getLevel().getServer(), h.getLevel(), new GameProfile(UUID.randomUUID(), "bulk_test"));
        }
        @Override public void displayClientMessage(Component message, boolean actionBar) {
            if (!actionBar) messages.add(message.getString());
        }
    }

    private static TestPlayer player(GameTestHelper h, boolean full) {
        TestPlayer p = new TestPlayer(h);
        Connection connection = new Connection(PacketFlow.SERVERBOUND) {
            private final EmbeddedChannel embedded = new EmbeddedChannel();
            @Override public io.netty.channel.Channel channel() { return embedded; }
            @Override public void send(Packet<?> packet) {}
            @Override public void send(Packet<?> packet, PacketSendListener listener) {}
            @Override public boolean isConnected() { return true; }
        };
        p.connection = new ServerGamePacketListenerImpl(h.getLevel().getServer(), connection, p);
        p.setGameMode(GameType.SURVIVAL);
        p.setNoGravity(true);
        Vec3 position = Vec3.atBottomCenterOf(h.absolutePos(new net.minecraft.core.BlockPos(1, 1, 1)));
        p.teleportTo(position.x, position.y, position.z);
        if (full) for (int i = 0; i < p.getInventory().items.size(); ++i)
            p.getInventory().items.set(i, new ItemStack(Items.STONE, 64));
        h.getLevel().addNewPlayer(p);
        return p;
    }

    private static final class Wallet extends AbstractShopSellerType<String> {
        long balance = Long.MAX_VALUE / 4;
        int charges;
        boolean fail;
        Wallet() { super("test"); }
        @Override public long getCount(Player p) { return balance; }
        @Override public boolean buy(Player p, AbstractShopEntry e, long cost) {
            ++charges;
            if (fail || cost < 0 || balance < cost) return false;
            balance -= cost;
            return true;
        }
        @Override public String getEnumName() { return "test"; }
        @Override public String getId() { return "test"; }
        @Override public Wallet copy() { return new Wallet(); }
        @Override public void deserializeNBT(CompoundTag tag) {}
    }

    private record Offer(ShopItemEntryType type, ShopEntry entry, Wallet wallet) {
        void buy(Player player, int quantity) { type.buy(player, quantity, entry); }
    }

    private static Offer offer(ItemStack item, int perPurchase) {
        if (java.util.Arrays.stream(ShopItemEntryType.class.getDeclaredMethods())
                .noneMatch(method -> method.getName().contains("bulkDelivery"))) {
            throw new AssertionError("SDM purchase mixin is not active; refusing to run a bulk test");
        }
        ShopEntry entry = new ShopEntry(null);
        entry.entryCount = perPurchase;
        entry.entryPrice = 5;
        Wallet wallet = new Wallet();
        entry.shopSellerType = wallet;
        ShopItemEntryType type = new ShopItemEntryType(item);
        entry.setEntryType(type);
        type.setShopEntry(entry);
        return new Offer(type, entry, wallet);
    }

    private static List<PurchaseCargo> cargo(TestPlayer p) {
        return p.serverLevel().getEntitiesOfClass(PurchaseCargo.class, p.getBoundingBox().inflate(4),
                entity -> p.getUUID().equals(entity.owner()) && !entity.isRemoved());
    }

    private static List<ItemEntity> drops(TestPlayer p) {
        return p.serverLevel().getEntitiesOfClass(ItemEntity.class, p.getBoundingBox().inflate(3), entity -> !entity.isRemoved());
    }

    private static void clean(TestPlayer p) {
        cargo(p).forEach(PurchaseCargo::destroyCargo);
        drops(p).forEach(Entity::discard);
        p.serverLevel().removePlayerImmediately(p, Entity.RemovalReason.DISCARDED);
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void exact127And128ThresholdUsesActualSdmPurchase(GameTestHelper h) {
        TestPlayer p = player(h, true);
        try {
            Offer offer = offer(new ItemStack(Items.APPLE), 1);
            offer.buy(p, 127);
            h.assertTrue(drops(p).size() == 127 && cargo(p).isEmpty(), "127 would-be entities must use the unchanged native path");
            h.assertTrue(p.messages.isEmpty(), "Small purchases must not get the compression message");
            drops(p).forEach(Entity::discard);
            offer.buy(p, 128);
            h.assertTrue(drops(p).isEmpty() && cargo(p).size() == 1, "128 would-be entities must create exactly one cargo");
            h.assertTrue(cargo(p).get(0).amount() == 128, "Cargo must contain every purchased item");
            h.assertTrue(p.messages.size() == 1 && p.messages.get(0).equals(Component.translatable("message.sdmshopbulk.compressed").getString()), "Chat notification must occur once");
            h.assertTrue(offer.wallet.charges == 2, "Each purchase must charge exactly once");
            h.succeed();
        } finally { clean(p); }
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void fillsOnlyMainSlotsAndAccountsForPartialStacks(GameTestHelper h) {
        TestPlayer p = player(h, false);
        try {
            p.getInventory().items.set(0, new ItemStack(Items.APPLE, 63));
            p.getInventory().items.set(1, new ItemStack(Items.STONE, 64));
            int capacity = 1 + 34 * 64;
            Offer offer = offer(new ItemStack(Items.APPLE), 1);
            offer.buy(p, capacity + 128);
            h.assertTrue(cargo(p).size() == 1 && cargo(p).get(0).amount() == 128, "Fill all actual available capacity before dropping cargo");
            for (int i = 0; i < 36; ++i) h.assertTrue(p.getInventory().items.get(i).getCount() == 64, "Every main slot should be full");
            h.assertTrue(p.getInventory().armor.stream().allMatch(ItemStack::isEmpty), "Armor slots must not be used");
            h.assertTrue(p.getInventory().offhand.stream().allMatch(ItemStack::isEmpty), "Offhand must not be used");
            h.assertTrue(drops(p).isEmpty(), "Large delivery must never first spawn normal drops");
            h.assertTrue(BulkPurchase.ordinaryEntityCount(129, 10, 19) == 128, "Partially fitting final delivery still counts as a drop");
            h.succeed();
        } finally { clean(p); }
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void enormousPurchaseHasConstantWorkAndPersistsFullNbt(GameTestHelper h) {
        TestPlayer p = player(h, true);
        try {
            ItemStack item = new ItemStack(Items.DIAMOND_SWORD);
            item.setDamageValue(17);
            item.setHoverName(Component.literal("NBT preservation"));
            item.getOrCreateTag().putString("bulk_test_quality", "unique");
            Offer offer = offer(item, 64);
            long start = System.nanoTime();
            offer.buy(p, 2_000_000_000);
            h.assertTrue(System.nanoTime() - start < 2_000_000_000L, "Two billion purchases must not run a per-purchase loop");
            PurchaseCargo entity = cargo(p).get(0);
            h.assertTrue(entity.amount() == 128_000_000_000L, "Total must exceed int capacity without overflow");
            h.assertTrue(entity.item().getCount() == 1, "Rendering stack must never carry the virtual quantity");
            CompoundTag saved = entity.saveWithoutId(new CompoundTag());
            PurchaseCargo loaded = new PurchaseCargo(ShopBulkDrops.CARGO.get(), h.getLevel());
            loaded.load(saved);
            h.assertTrue(loaded.amount() == entity.amount() && p.getUUID().equals(loaded.owner()), "Owner and 64-bit count must survive world serialization");
            h.assertTrue(MainInventoryPlan.sameItemData(loaded.item(), item), "Damage, name and custom data must survive");
            h.assertTrue(loaded.getTeamColor() == 0xFFFF55 && loaded.isCurrentlyGlowing(), "Yellow outline must survive reload");
            entity.tickCount = 100_000;
            entity.tick();
            h.assertTrue(!entity.isRemoved(), "Old cargo must not expire after five minutes");
            h.succeed();
        } finally { clean(p); }
    }

    @GameTest(template = "empty", timeoutTicks = 85)
    public static void onlyBuyerPicksUpPartiallyAndQuantityChangesLive(GameTestHelper h) {
        TestPlayer owner = player(h, true), stranger = player(h, false);
        Offer offer = offer(new ItemStack(Items.APPLE), 1);
        offer.buy(owner, 200);
        PurchaseCargo entity = cargo(owner).get(0);
        entity.setNoGravity(true);
        h.runAfterDelay(42, () -> {
            h.assertTrue(entity.collect(stranger) == 0, "Strangers must not be allowed to collect cargo");
            FakePlayer fake = new FakePlayer(h.getLevel(), new GameProfile(owner.getUUID(), "fake_owner"));
            fake.setPos(owner.position());
            h.assertTrue(entity.collect(fake) == 0, "A fake player must not collect even with the owner's UUID");
            owner.getInventory().items.set(5, ItemStack.EMPTY);
            h.assertTrue(entity.collect(owner) == 64 && entity.amount() == 136, "Pickup must fit only the one free main slot");
            h.assertTrue(entity.getCustomName().getString().equals("136x"), "Remaining quantity must update immediately");
            h.assertTrue(owner.getInventory().offhand.get(0).isEmpty(), "Do not fill offhand during collection");
        });
        h.runAfterDelay(54, () -> {
            owner.getInventory().items.set(6, ItemStack.EMPTY);
            owner.getInventory().items.set(7, ItemStack.EMPTY);
            h.assertTrue(entity.collect(owner) == 128 && entity.amount() == 8, "Second collection must leave exact remainder");
        });
        h.runAfterDelay(66, () -> {
            try {
                owner.getInventory().items.set(8, ItemStack.EMPTY);
                h.assertTrue(entity.collect(owner) == 8 && entity.isRemoved(), "Cargo disappears only after the last unit is collected");
                h.succeed();
            } finally { clean(owner); clean(stranger); }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void mergeIsRestrictedToSameBuyerAndExactItemData(GameTestHelper h) {
        TestPlayer p = player(h, true), other = player(h, true);
        try {
            Offer offer = offer(new ItemStack(Items.APPLE), 1);
            ItemEntity ordinary = new ItemEntity(h.getLevel(), p.getX(), p.getY(), p.getZ(), new ItemStack(Items.APPLE, 7));
            h.getLevel().addFreshEntity(ordinary);
            offer.buy(p, 128);
            offer.buy(p, 128);
            h.assertTrue(cargo(p).size() == 1 && cargo(p).get(0).amount() == 256, "Consecutive large purchases should reuse nearby cargo");
            h.assertTrue(ordinary.isAlive() && ordinary.getItem().getCount() == 7, "Normal drops must not be consumed or converted");
            ItemStack named = new ItemStack(Items.APPLE).setHoverName(Component.literal("Different NBT"));
            offer(named, 1).buy(p, 128);
            h.assertTrue(cargo(p).size() == 2, "Different NBT must not merge");
            offer.buy(other, 128);
            h.assertTrue(cargo(other).size() == 1 && cargo(p).size() == 2, "Buyers must not share cargo");
            offer.buy(p, 1);
            h.assertTrue(cargo(p).size() == 2 && drops(p).size() == 2, "A small purchase stays ordinary even next to cargo");
            h.succeed();
        } finally { clean(p); clean(other); }
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void cargoSurvivesDamageAndDiscardButNotTheVoid(GameTestHelper h) {
        TestPlayer p = player(h, true);
        try {
            offer(new ItemStack(Items.APPLE), 1).buy(p, 128);
            PurchaseCargo entity = cargo(p).get(0);
            for (var source : List.of(h.getLevel().damageSources().inFire(), h.getLevel().damageSources().onFire(),
                    h.getLevel().damageSources().lava(), h.getLevel().damageSources().generic(),
                    h.getLevel().damageSources().explosion((Entity) null, null))) {
                h.assertFalse(entity.hurt(source, 10000), "Environmental damage must be ignored");
            }
            entity.discard();
            entity.kill();
            h.assertFalse(entity.isRemoved(), "Generic discard/kill cleanup must not delete paid cargo");
            h.assertTrue(entity.fireImmune() && entity.ignoreExplosion(), "Cargo must resist fire and explosion physics");
            h.assertFalse((Object) entity instanceof ItemEntity, "Vanilla hoppers and ordinary item collectors must not see cargo as an ItemEntity");
            entity.hurt(h.getLevel().damageSources().fellOutOfWorld(), 10000);
            h.assertTrue(entity.isRemoved(), "Void removal is explicitly allowed");
            h.succeed();
        } finally { clean(p); }
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void administratorItemCleanupIncludesCargoAndHonorsDistance(GameTestHelper h) throws Exception {
        TestPlayer p = player(h, true);
        try {
            String marker = "cleanup_" + UUID.randomUUID().toString().replace("-", "");
            offer(new ItemStack(Items.APPLE), 1).buy(p, 128);
            PurchaseCargo near = cargo(p).get(0);
            near.addTag(marker);
            ItemEntity ordinary = new ItemEntity(h.getLevel(), p.getX() + 1, p.getY(), p.getZ(), new ItemStack(Items.APPLE));
            ordinary.addTag(marker); h.getLevel().addFreshEntity(ordinary);
            PurchaseCargo far = new PurchaseCargo(ShopBulkDrops.CARGO.get(), h.getLevel());
            far.configure(p.getUUID(), new ItemStack(Items.APPLE), 256);
            far.setPos(p.getX() + 9, p.getY(), p.getZ()); far.addTag(marker);
            h.getLevel().addFreshEntity(far);
            var source = h.getLevel().getServer().createCommandSourceStack().withLevel(h.getLevel())
                    .withPosition(p.position()).withPermission(2).withSuppressedOutput();
            var selector = new net.minecraft.commands.arguments.selector.EntitySelectorParser(
                    new com.mojang.brigadier.StringReader("@e[type=item,tag=" + marker + "]")).parse();
            h.assertTrue(selector.findEntities(source).stream().noneMatch(PurchaseCargo.class::isInstance),
                    "Ordinary item selectors outside kill must still exclude cargo");
            var dispatcher = h.getLevel().getServer().getCommands().getDispatcher();
            try {
                dispatcher.execute("kill @e[type=item,tag=" + marker + "]", source.withPermission(0));
            } catch (com.mojang.brigadier.exceptions.CommandSyntaxException expected) { }
            h.assertFalse(near.isRemoved() || ordinary.isRemoved(), "Non-admin cleanup must not bypass permissions");
            int deleted = dispatcher.execute("kill @e[type=minecraft:item,tag=" + marker + ",distance=..3]", source);
            h.assertTrue(deleted == 2 && near.isRemoved() && ordinary.isRemoved(), "Admin item cleanup must delete both kinds");
            h.assertFalse(far.isRemoved(), "Distance filters must also apply to cargo");
            dispatcher.execute("kill " + far.getUUID(), source);
            h.assertTrue(far.isRemoved(), "Admin explicit UUID cleanup must work");
            h.succeed();
        } finally { clean(p); }
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void administratorCleanupHonorsExecuteSortLimitAndCargoType(GameTestHelper h) throws Exception {
        TestPlayer p = player(h, true);
        try {
            String marker = "limit_" + UUID.randomUUID().toString().replace("-", "");
            offer(new ItemStack(Items.APPLE), 1).buy(p, 128);
            PurchaseCargo near = cargo(p).get(0); near.addTag(marker);
            ItemEntity ordinary = new ItemEntity(h.getLevel(), p.getX() + 2, p.getY(), p.getZ(), new ItemStack(Items.APPLE));
            ordinary.addTag(marker); h.getLevel().addFreshEntity(ordinary);
            var source = h.getLevel().getServer().createCommandSourceStack().withLevel(h.getLevel())
                    .withPosition(p.position()).withPermission(2).withSuppressedOutput();
            var dispatcher = h.getLevel().getServer().getCommands().getDispatcher();
            int deleted = dispatcher.execute("execute positioned ~ ~ ~ run kill @e[type=item,tag=" + marker
                    + ",distance=..5,sort=nearest,limit=1]", source);
            h.assertTrue(deleted == 1 && near.isRemoved() && !ordinary.isRemoved(),
                    "Execute cleanup must apply one global sort and limit across both kinds");
            offer(new ItemStack(Items.APPLE), 1).buy(p, 128);
            PurchaseCargo typed = cargo(p).get(0); typed.addTag(marker);
            dispatcher.execute("kill @e[type=sdmshopbulk:purchase_cargo,tag=" + marker + "]", source);
            h.assertTrue(typed.isRemoved() && !ordinary.isRemoved(), "Explicit cargo type must not delete ordinary drops");
            String quoted = "@e[type=item,name=\"type=item\",nbt={CustomName:'{\"text\":\"type=item\"}'}]";
            h.assertTrue(AdministratorCleanup.expandItemSelector(quoted).equals(quoted.replace("@e[type=item,", "@e[type=#sdmshopbulk:kill_items,")),
                    "Only the top-level positive type option may expand");
            h.assertTrue(AdministratorCleanup.expandItemSelector("@e[type=!item]").equals("@e[type=!item]"),
                    "Negative type filters must not be broadened");
            h.succeed();
        } finally { clean(p); }
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void failedChargeAndCancelledSpawnDoNotDeliverOrChargeTwice(GameTestHelper h) {
        TestPlayer p = player(h, true);
        Consumer<EntityJoinLevelEvent> cancel = event -> {
            if (event.getEntity() instanceof PurchaseCargo entity && p.getUUID().equals(entity.owner())) event.setCanceled(true);
        };
        try {
            Offer failed = offer(new ItemStack(Items.APPLE), 1);
            failed.wallet.fail = true;
            long money = failed.wallet.balance;
            failed.buy(p, 128);
            h.assertTrue(failed.wallet.charges == 1 && failed.wallet.balance == money, "Rejected payment must not retry the native purchase");
            h.assertTrue(cargo(p).isEmpty() && drops(p).isEmpty(), "Failed payment must not issue goods");
            Offer blocked = offer(new ItemStack(Items.APPLE), 1);
            MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGHEST, false, EntityJoinLevelEvent.class, cancel);
            blocked.buy(p, 128);
            h.assertTrue(blocked.wallet.charges == 0 && cargo(p).isEmpty(), "Cancelled entity spawn must happen before charging");
            h.succeed();
        } finally { MinecraftForge.EVENT_BUS.unregister(cancel); clean(p); }
    }

    @GameTest(template = "empty", timeoutTicks = 10)
    public static void colorBoundariesAndOverflowAreExact(GameTestHelper h) {
        long[] amounts = {1, 5000, 5001, 10000, 10001, 50000, 50001, 100000, 100001};
        int[] colors = {0xFFFFFF, 0xFFFFFF, 0x55FF55, 0x55FF55, 0xFFFF55, 0xFFFF55, 0xFFAA00, 0xFFAA00, 0xFF5555};
        for (int i = 0; i < amounts.length; ++i) {
            h.assertTrue(PurchaseCargo.labelColor(amounts[i]) == colors[i], "Color threshold must use strict greater-than");
            h.assertTrue(PurchaseCargo.label(amounts[i]).getString().equals(amounts[i] + "x"), "Quantity must be exact, not rounded");
        }
        Offer offer = offer(new ItemStack(Items.APPLE), 1);
        offer.entry.entryPrice = Long.MAX_VALUE;
        h.assertFalse(BulkPurchase.validNumbers(2, offer.entry), "Money multiplication must reject overflow");
        h.assertFalse(BulkPurchase.validNumbers(-1, offer.entry), "Negative quantities must be rejected");
        h.succeed();
    }
}
