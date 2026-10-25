package cn.pixelmc.sdmshopbulk;

import java.util.Optional;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.network.NetworkHooks;

/** Not an ItemEntity: ordinary drops, hoppers and item-magnet scans never share its inventory. */
public final class PurchaseCargo extends Entity {
    private static final EntityDataAccessor<ItemStack> ITEM = SynchedEntityData.defineId(PurchaseCargo.class, EntityDataSerializers.ITEM_STACK);
    private static final EntityDataAccessor<Long> AMOUNT = SynchedEntityData.defineId(PurchaseCargo.class, EntityDataSerializers.LONG);
    private static final EntityDataAccessor<Optional<UUID>> OWNER = SynchedEntityData.defineId(PurchaseCargo.class, EntityDataSerializers.OPTIONAL_UUID);
    private long nextPickupTime;
    private boolean authorizedRemoval;

    public PurchaseCargo(EntityType<? extends PurchaseCargo> type, Level level) {
        super(type, level);
        setGlowingTag(true);
        setCustomNameVisible(true);
    }

    @Override protected void defineSynchedData() {
        entityData.define(ITEM, ItemStack.EMPTY);
        entityData.define(AMOUNT, 0L);
        entityData.define(OWNER, Optional.empty());
    }

    void configure(UUID owner, ItemStack item, long quantity) {
        if (item.isEmpty() || quantity < 0) throw new IllegalArgumentException("Invalid cargo");
        entityData.set(OWNER, Optional.of(owner));
        entityData.set(ITEM, item.copyWithCount(1));
        setAmount(quantity);
        nextPickupTime = level().getGameTime() + 40;
    }

    public ItemStack item() { return entityData.get(ITEM); }
    public long amount() { return entityData.get(AMOUNT); }
    public UUID owner() { return entityData.get(OWNER).orElse(null); }

    void setAmount(long amount) {
        if (amount < 0) throw new IllegalArgumentException("Negative cargo");
        entityData.set(AMOUNT, amount);
        setCustomName(amount > 0 ? label(amount) : Component.empty());
    }

    public static int labelColor(long amount) {
        if (amount > 100_000) return 0xFF5555;
        if (amount > 50_000) return 0xFFAA00;
        if (amount > 10_000) return 0xFFFF55;
        if (amount > 5_000) return 0x55FF55;
        return 0xFFFFFF;
    }

    public static Component label(long amount) {
        return Component.literal(amount + "x").withStyle(Style.EMPTY.withColor(labelColor(amount)));
    }

    @Override public int getTeamColor() { return 0xFFFF55; }
    @Override public boolean fireImmune() { return true; }
    @Override public boolean ignoreExplosion() { return true; }
    @Override public boolean isPushable() { return false; }
    @Override public boolean canBeCollidedWith() { return false; }
    @Override public boolean isPickable() { return amount() > 0; }
    @Override public boolean canChangeDimensions() { return false; }
    @Override public boolean shouldBeSaved() { return amount() > 0 && super.shouldBeSaved(); }

    @Override public boolean isInvulnerableTo(DamageSource source) {
        return !source.is(DamageTypes.FELL_OUT_OF_WORLD);
    }

    @Override public boolean hurt(DamageSource source, float amount) {
        if (source.is(DamageTypes.FELL_OUT_OF_WORLD)) {
            destroyCargo();
            return true;
        }
        return false;
    }

    @Override protected void onBelowWorld() {
        if (!level().isClientSide) destroyCargo();
    }

    @Override public void remove(RemovalReason reason) {
        if (!level().isClientSide && !authorizedRemoval && amount() > 0
                && (reason == RemovalReason.KILLED || reason == RemovalReason.DISCARDED)) return;
        super.remove(reason);
    }

    void destroyCargo() {
        authorizedRemoval = true;
        discard();
    }

    void deleteByAdministrator(CommandSourceStack source) {
        if (level().isClientSide || !source.hasPermission(2) || source.getServer() != level().getServer())
            throw new IllegalArgumentException("Cargo cleanup requires an administrator command");
        com.mojang.logging.LogUtils.getLogger().info("[ShopBulk] {} deleted cargo {} (buyer={}, amount={}, item={})",
                source.getTextName(), getUUID(), owner(), amount(), item());
        destroyCargo();
    }

    @Override public void tick() {
        super.tick();
        if (isRemoved()) return;
        if (!isNoGravity()) setDeltaMovement(getDeltaMovement().add(0, -0.04, 0));
        move(MoverType.SELF, getDeltaMovement());
        Vec3 velocity = getDeltaMovement().scale(0.98);
        if (onGround()) velocity = velocity.multiply(0.6, 0, 0.6);
        setDeltaMovement(velocity);
        // Deliberately no age-based expiry or scan for neighbouring ordinary items.
    }

    public static boolean realPlayer(Player player) {
        if (!(player instanceof ServerPlayer) || player instanceof FakePlayer) return false;
        for (Class<?> type = player.getClass(); type != null; type = type.getSuperclass()) {
            if (type.getName().equals("dev.dubhe.curtain.features.player.patches.EntityPlayerMPFake")
                    || type.getName().equals("carpet.patches.EntityPlayerMPFake")) return false;
        }
        return true;
    }

    @Override public void playerTouch(Player player) { collect(player); }

    @Override public InteractionResult interact(Player player, InteractionHand hand) {
        if (level().isClientSide) return InteractionResult.SUCCESS;
        return collect(player) > 0 ? InteractionResult.SUCCESS : InteractionResult.PASS;
    }

    public long collect(Player player) {
        if (!realPlayer(player) || !player.getUUID().equals(owner()) || amount() <= 0 || isRemoved()
                || player.isSpectator() || !player.isAlive() || player.level() != level()
                || player.distanceToSqr(this) > 9 || level().getGameTime() < nextPickupTime) return 0;
        nextPickupTime = level().getGameTime() + 10;
        MainInventoryPlan plan = new MainInventoryPlan(player.getInventory(), item(), amount());
        if (plan.inserted == 0) return 0;
        plan.apply(player.getInventory());
        setAmount(plan.remaining);
        player.containerMenu.broadcastChanges();
        player.awardStat(Stats.ITEM_PICKED_UP.get(item().getItem()), (int) plan.inserted);
        level().playSound(null, getX(), getY(), getZ(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.2F, 1.0F);
        if (amount() == 0) destroyCargo();
        return plan.inserted;
    }

    @Override protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putInt("CargoVersion", 1);
        tag.put("Item", item().copyWithCount(1).save(new CompoundTag()));
        tag.putLong("Quantity", amount());
        if (owner() != null) tag.putUUID("Buyer", owner());
    }

    @Override protected void readAdditionalSaveData(CompoundTag tag) {
        ItemStack item = ItemStack.of(tag.getCompound("Item"));
        long amount = tag.getLong("Quantity");
        if (item.isEmpty() || amount <= 0 || !tag.hasUUID("Buyer")) {
            com.mojang.logging.LogUtils.getLogger().error("Invalid shop cargo data for entity {}", getUUID());
            destroyCargo();
            return;
        }
        configure(tag.getUUID("Buyer"), item, amount);
        setGlowingTag(true);
        setCustomNameVisible(true);
    }

    @Override public Packet<ClientGamePacketListener> getAddEntityPacket() { return NetworkHooks.getEntitySpawningPacket(this); }
}
