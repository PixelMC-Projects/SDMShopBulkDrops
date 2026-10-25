package cn.pixelmc.sdmshopbulk;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.selector.EntitySelectorParser;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Expand item selectors only while executing the administrator's kill command. */
@Mod.EventBusSubscriber(modid = ShopBulkDrops.ID)
public final class AdministratorCleanup {
    private static final Pattern ITEM_TYPE = Pattern.compile("^(\\s*type\\s*=\\s*)(?:minecraft:)?item(\\s*)$");

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void register(RegisterCommandsEvent event) {
        for (var root : List.copyOf(event.getDispatcher().getRoot().getChildren())) {
            if (!(root.getName().equals("kill") || root.getName().endsWith(":kill"))) continue;
            var targets = root.getChild("targets");
            if (targets == null || targets.getCommand() instanceof CleanupExecutor) continue;
            if (!(targets instanceof ArgumentCommandNode<?, ?> argument) || !(argument.getType() instanceof EntityArgument))
                throw new IllegalStateException("Another mod changed the kill command target argument; cannot safely install cargo cleanup");
            // Brigadier merges the executable while retaining the existing permissions and child nodes.
            root.addChild(targets.createBuilder().executes(new CleanupExecutor(targets.getCommand())).build());
        }
    }

    private record CleanupExecutor(Command<CommandSourceStack> original) implements Command<CommandSourceStack> {
        @Override public int run(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
            CommandSourceStack source = context.getSource();
            if (!source.hasPermission(2)) return original.run(context);
            String input = context.getNodes().stream().filter(node -> node.getNode().getName().equals("targets"))
                    .findFirst().orElseThrow().getRange().get(context.getInput());
            String expanded = expandItemSelector(input);
            Collection<? extends Entity> targets;
            if (expanded.equals(input)) {
                targets = EntityArgument.getEntities(context, "targets");
                if (targets.stream().noneMatch(PurchaseCargo.class::isInstance)) return original.run(context);
            } else {
                targets = new EntitySelectorParser(new StringReader(expanded)).parse().findEntities(source);
                if (targets.isEmpty()) throw EntityArgument.NO_ENTITIES_FOUND.create();
            }
            for (Entity target : targets) {
                if (target instanceof PurchaseCargo cargo) cargo.deleteByAdministrator(source);
                else target.kill();
            }
            if (targets.size() == 1) {
                Entity target = targets.iterator().next();
                source.sendSuccess(() -> Component.translatable("commands.kill.success.single", target.getDisplayName()), true);
            } else source.sendSuccess(() -> Component.translatable("commands.kill.success.multiple", targets.size()), true);
            return targets.size();
        }
    }

    /** Keep quoted strings, NBT, nested selectors, exclusions and all non-type options intact. */
    static String expandItemSelector(String input) {
        if (!input.startsWith("@e[")) return input;
        StringBuilder output = new StringBuilder(input.length() + 32);
        output.append("@e[");
        int start = 3, depth = 0;
        char quote = 0;
        boolean escaped = false;
        for (int index = start; index < input.length(); ++index) {
            char c = input.charAt(index);
            if (quote != 0) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == quote) quote = 0;
                continue;
            }
            if (c == '"' || c == '\'') { quote = c; continue; }
            if (c == '{' || c == '[') { ++depth; continue; }
            if (c == '}' || (c == ']' && depth > 0)) { --depth; continue; }
            if (depth == 0 && (c == ',' || c == ']')) {
                String option = input.substring(start, index);
                var match = ITEM_TYPE.matcher(option);
                output.append(match.matches() ? match.group(1) + "#sdmshopbulk:kill_items" + match.group(2) : option);
                output.append(c);
                start = index + 1;
                if (c == ']') return output.append(input.substring(start)).toString();
            }
        }
        return input;
    }

    private AdministratorCleanup() {}
}
