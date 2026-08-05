package org.skydream.smartqueue.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.skydream.smartqueue.Config;
import org.skydream.smartqueue.Smartqueue;
import org.skydream.smartqueue.queue.QueueEntry;
import org.skydream.smartqueue.queue.QueueManager;

import java.util.List;

@EventBusSubscriber(modid = Smartqueue.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class QueueCommands {

    private QueueCommands() {}

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        dispatcher.register(Commands.literal("smartqueue")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("toggle")
                        .then(Commands.argument("state", StringArgumentType.word())
                                .suggests((ctx, builder) -> builder.suggest("on").suggest("off").buildFuture())
                                .executes(QueueCommands::toggle))
                        .executes(ctx -> {
                            boolean enabled = Config.ENABLED.get();
                            ctx.getSource().sendSuccess(
                                    () -> Component.translatable("smartqueue.command.toggle",
                                            enabled ? "ON" : "OFF"), true);
                            return 1;
                        })
                )
                .then(Commands.literal("pause")
                        .executes(ctx -> {
                            QueueManager qm = QueueManager.getInstance();
                            if (qm.isPaused()) {
                                ctx.getSource().sendSuccess(
                                        () -> Component.translatable("smartqueue.command.already_paused"), true);
                            } else {
                                qm.setPaused(true);
                                ctx.getSource().sendSuccess(
                                        () -> Component.translatable("smartqueue.command.paused"), true);
                            }
                            return 1;
                        })
                )
                .then(Commands.literal("resume")
                        .executes(ctx -> {
                            QueueManager qm = QueueManager.getInstance();
                            if (!qm.isPaused()) {
                                ctx.getSource().sendSuccess(
                                        () -> Component.translatable("smartqueue.command.not_paused"), true);
                            } else {
                                qm.setPaused(false);
                                ctx.getSource().sendSuccess(
                                        () -> Component.translatable("smartqueue.command.resumed"), true);
                            }
                            return 1;
                        })
                )
                .then(Commands.literal("reload")
                        .executes(ctx -> {
                            ctx.getSource().sendSuccess(
                                    () -> Component.translatable("smartqueue.command.reloaded"), true);
                            return 1;
                        })
                )
                .then(Commands.literal("status")
                        .executes(QueueCommands::status)
                )
                .then(Commands.literal("staff")
                        .then(Commands.literal("add")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .executes(ctx -> addStaff(ctx, StringArgumentType.getString(ctx, "name")))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .executes(ctx -> removeStaff(ctx, StringArgumentType.getString(ctx, "name")))))
                        .then(Commands.literal("list")
                                .executes(ctx -> listStaff(ctx)))
                )
                .then(Commands.literal("vip")
                        .then(Commands.literal("add")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .executes(ctx -> addVip(ctx, StringArgumentType.getString(ctx, "name")))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .executes(ctx -> removeVip(ctx, StringArgumentType.getString(ctx, "name")))))
                        .then(Commands.literal("list")
                                .executes(ctx -> listVip(ctx)))
                )
        );
    }

    private static int toggle(CommandContext<CommandSourceStack> ctx) {
        String state = StringArgumentType.getString(ctx, "state");
        boolean enable = "on".equalsIgnoreCase(state);
        QueueManager.getInstance().setEnabled(enable);
        ctx.getSource().sendSuccess(
                () -> Component.translatable("smartqueue.command.toggle", enable ? "ON" : "OFF"), true);
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        QueueManager qm = QueueManager.getInstance();
        List<QueueEntry> queue = qm.getQueueList();
        boolean enabled = Config.ENABLED.get();
        boolean paused = qm.isPaused();
        int active = qm.activeCount();
        int queued = qm.queueSize();

        ctx.getSource().sendSuccess(() -> Component.literal(
                "§6=== SmartQueue Status ==="), false);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "§eEnabled: §f" + enabled + "  §ePaused: §f" + paused), false);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "§eActive Players: §f" + active + " §e/ §f" + Config.EFFECTIVE_MAX_PLAYERS.get()), false);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "§eQueue Size: §f" + queued + " §e/ §f" + Config.MAX_QUEUE_SIZE.get()), false);

        if (!queue.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("§eQueue:"), false);
            for (int i = 0; i < queue.size(); i++) {
                QueueEntry entry = queue.get(i);
                String type = entry.staff ? "§c[STAFF]" : entry.vip ? "§d[VIP]" : "§7[NORMAL]";
                final int pos = i + 1;
                ctx.getSource().sendSuccess(() -> Component.literal(
                        "  §f" + pos + ". " + type + " §f" + entry.getName()), false);
            }
        }
        return 1;
    }

    private static int addStaff(CommandContext<CommandSourceStack> ctx, String name) {
        List<? extends String> list = Config.STAFF_LIST.get();
        boolean already = list.stream().anyMatch(s -> s.equalsIgnoreCase(name));
        if (already) {
            ctx.getSource().sendSuccess(
                    () -> Component.translatable("smartqueue.command.staff.already", name), true);
        } else {
            QueueManager.getInstance().addStaff(name);
            ctx.getSource().sendSuccess(
                    () -> Component.translatable("smartqueue.command.staff.added", name), true);
        }
        return 1;
    }

    private static int removeStaff(CommandContext<CommandSourceStack> ctx, String name) {
        List<? extends String> list = Config.STAFF_LIST.get();
        boolean found = list.stream().anyMatch(s -> s.equalsIgnoreCase(name));
        if (!found) {
            ctx.getSource().sendSuccess(
                    () -> Component.translatable("smartqueue.command.staff.not_found", name), true);
        } else {
            QueueManager.getInstance().removeStaff(name);
            ctx.getSource().sendSuccess(
                    () -> Component.translatable("smartqueue.command.staff.removed", name), true);
        }
        return 1;
    }

    private static int listStaff(CommandContext<CommandSourceStack> ctx) {
        List<? extends String> list = Config.STAFF_LIST.get();
        if (list.isEmpty()) {
            ctx.getSource().sendSuccess(
                    () -> Component.translatable("smartqueue.command.list.empty", "Staff"), true);
        } else {
            ctx.getSource().sendSuccess(
                    () -> Component.translatable("smartqueue.command.list.show", "Staff", String.join(", ", list)), true);
        }
        return 1;
    }

    private static int addVip(CommandContext<CommandSourceStack> ctx, String name) {
        List<? extends String> list = Config.VIP_LIST.get();
        boolean already = list.stream().anyMatch(s -> s.equalsIgnoreCase(name));
        if (already) {
            ctx.getSource().sendSuccess(
                    () -> Component.translatable("smartqueue.command.vip.already", name), true);
        } else {
            QueueManager.getInstance().addVip(name);
            ctx.getSource().sendSuccess(
                    () -> Component.translatable("smartqueue.command.vip.added", name), true);
        }
        return 1;
    }

    private static int removeVip(CommandContext<CommandSourceStack> ctx, String name) {
        List<? extends String> list = Config.VIP_LIST.get();
        boolean found = list.stream().anyMatch(s -> s.equalsIgnoreCase(name));
        if (!found) {
            ctx.getSource().sendSuccess(
                    () -> Component.translatable("smartqueue.command.vip.not_found", name), true);
        } else {
            QueueManager.getInstance().removeVip(name);
            ctx.getSource().sendSuccess(
                    () -> Component.translatable("smartqueue.command.vip.removed", name), true);
        }
        return 1;
    }

    private static int listVip(CommandContext<CommandSourceStack> ctx) {
        List<? extends String> list = Config.VIP_LIST.get();
        if (list.isEmpty()) {
            ctx.getSource().sendSuccess(
                    () -> Component.translatable("smartqueue.command.list.empty", "VIP"), true);
        } else {
            ctx.getSource().sendSuccess(
                    () -> Component.translatable("smartqueue.command.list.show", "VIP", String.join(", ", list)), true);
        }
        return 1;
    }
}
