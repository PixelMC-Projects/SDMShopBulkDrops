package cn.pixelmc.sdmshopbulk;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/** Work is bounded by the 36 main inventory slots, never by the purchase amount. */
public final class MainInventoryPlan {
    public record Change(int slot, ItemStack before, ItemStack after) {}
    private final List<Change> changes = new ArrayList<>();
    public final long inserted;
    public final long remaining;

    public MainInventoryPlan(Inventory inventory, ItemStack prototype, long total) {
        if (total < 0 || prototype.isEmpty()) throw new IllegalArgumentException("Invalid delivery");
        long left = total;
        int maximum = Math.min(inventory.getMaxStackSize(), prototype.getMaxStackSize());
        // Merge existing partial stacks first, then use empty main/hotbar slots.
        for (int pass = 0; pass < 2 && left > 0; ++pass) {
            for (int slot = 0; slot < inventory.items.size() && left > 0; ++slot) {
                ItemStack current = inventory.items.get(slot);
                boolean empty = current.isEmpty();
                if ((pass == 0 && empty) || (pass == 1 && !empty)) continue;
                if (!empty && !sameItemData(current, prototype)) continue;
                int available = maximum - (empty ? 0 : current.getCount());
                if (available <= 0) continue;
                int amount = (int) Math.min(left, available);
                ItemStack result = empty ? prototype.copyWithCount(amount) : current.copyWithCount(current.getCount() + amount);
                changes.add(new Change(slot, current.copy(), result));
                left -= amount;
            }
        }
        remaining = left;
        inserted = total - left;
    }

    public boolean stillValid(Inventory inventory) {
        return changes.stream().allMatch(change -> ItemStack.matches(inventory.items.get(change.slot()), change.before()));
    }

    public void apply(Inventory inventory) {
        if (!stillValid(inventory)) throw new IllegalStateException("Inventory changed during delivery");
        try {
            for (Change change : changes) inventory.items.set(change.slot(), change.after());
        } catch (RuntimeException failure) {
            for (Change change : changes) inventory.items.set(change.slot(), change.before());
            throw failure;
        }
        inventory.setChanged();
    }

    public static boolean sameItemData(ItemStack first, ItemStack second) {
        if (!ItemStack.isSameItemSameTags(first, second)) return false;
        // Preserve Forge capability data too, not just vanilla display/enchantment tags.
        return first.copyWithCount(1).save(new net.minecraft.nbt.CompoundTag())
                .equals(second.copyWithCount(1).save(new net.minecraft.nbt.CompoundTag()));
    }
}
