package com.example.encephalon.command;

import com.example.encephalon.EncephalonMod;
import com.example.encephalon.behavior.Behavior;
import com.example.encephalon.behavior.Behaviors;
import com.example.encephalon.config.MobConfigData;
import com.example.encephalon.config.Tri;
import com.example.encephalon.scope.Scope;
import com.example.encephalon.scope.ScopeType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * /mobai list, /mobai set <behavior> <on|off|inherit>   (global scope only for now).
 * TODO: scope argument, setting values, reset, profile, explain.
 */
@EventBusSubscriber(modid = EncephalonMod.MOD_ID)
public final class MobAiCommand {
    private MobAiCommand() {
    }

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("mobai")
                .requires(s -> s.hasPermission(2))
                .then(Commands.literal("list").executes(MobAiCommand::list))
                .then(Commands.literal("set")
                        .then(Commands.argument("behavior", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(
                                        Behaviors.all().stream().map(x -> x.id().getPath()), b))
                                .then(Commands.argument("state", StringArgumentType.word())
                                        .suggests((c, b) -> SharedSuggestionProvider.suggest(
                                                new String[] {"on", "off", "inherit"}, b))
                                        .executes(MobAiCommand::set)))));
    }

    private static Scope scopeFor(Behavior b) {
        return b.scopes().contains(ScopeType.WORLD) ? Scope.WORLD : Scope.ALL;
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        MobConfigData data = MobConfigData.get(ctx.getSource().getServer());
        for (Behavior b : Behaviors.all()) {
            Tri t = data.toggle(scopeFor(b), b);
            ctx.getSource().sendSuccess(() -> Component.translatable("encephalon.command.list", b.name(), t.name()), false);
        }
        return Behaviors.all().size();
    }

    private static int set(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "behavior");
        Behavior b = Behaviors.byName(name);
        if (b == null) {
            src.sendFailure(Component.translatable("encephalon.command.unknown", name));
            return 0;
        }
        Tri state;
        try {
            state = Tri.valueOf(StringArgumentType.getString(ctx, "state").toUpperCase());
        } catch (IllegalArgumentException e) {
            return 0;
        }
        Scope scope = scopeFor(b);
        MobConfigData.get(src.getServer()).setToggle(scope, b, state);
        src.sendSuccess(() -> Component.translatable("encephalon.command.set", b.name(), state.name(), scope.key()), true);
        return 1;
    }
}
