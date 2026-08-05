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
                .then(Commands.literal("status")
                        .executes(QueueCommands::status))
                .then(Commands.literal("toggle")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.argument("state", StringArgumentType.word())
                                .suggests((ctx, builder) -> builder.suggest("on").suggest("off").buildFuture())
                                .executes(QueueCommands::toggle))
                        .executes(ctx -> {
                            boolean enabled = Config.ENABLED.get();
                            Component state = Component.translatable(
                                    enabled ? "smartqueue.command.state.on" : "smartqueue.command.state.off");
                            ctx.getSource().sendSuccess(
                                    () -> Component.translatable("smartqueue.command.toggle", state), true);
                            return 1;
                        })
                )
                .then(Commands.literal("pause")
                        .requires(src -> src.hasPermission(2))
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
                        .requires(src -> src.hasPermission(2))
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
                        .requires(src -> src.hasPermission(2))
                        .executes(ctx -> {
                            ctx.getSource().sendSuccess(
                                    () -> Component.translatable("smartqueue.command.reloaded"), true);
                            return 1;
                        })
                )
                .then(Commands.literal("staff")
                        .requires(src -> src.hasPermission(2))
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
                        .requires(src -> src.hasPermission(2))
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
        Component stateText = Component.translatable(
                enable ? "smartqueue.command.state.on" : "smartqueue.command.state.off");
        ctx.getSource().sendSuccess(
                () -> Component.translatable("smartqueue.command.toggle", stateText), true);
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        QueueManager qm = QueueManager.getInstance();
        QueueManager.QueueSnapshot snap = qm.getSnapshot();
        boolean enabled = Config.ENABLED.get();
        boolean paused = qm.isPaused();
        boolean proportional = Config.PROPORTIONAL_MODE.get();
        int active = qm.activeCount();
        int queued = qm.queueSize();
        int effectiveMax = Config.EFFECTIVE_MAX_PLAYERS.get();
        int vipSlots = qm.effectiveVipSlots();

        ctx.getSource().sendSuccess(
                () -> Component.translatable("smartqueue.command.status.header"), false);
        ctx.getSource().sendSuccess(
                () -> Component.translatable("smartqueue.command.status.enabled", enabled, paused), false);
        ctx.getSource().sendSuccess(
                () -> Component.translatable("smartqueue.command.status.active",
                        active, effectiveMax), false);

        if (proportional) {
            int vipRatio = Config.PROPORTIONAL_VIP_COUNT.get();
            int normalRatio = Config.PROPORTIONAL_NORMAL_COUNT.get();
            ctx.getSource().sendSuccess(
                    () -> Component.translatable("smartqueue.command.status.proportional",
                            vipRatio, normalRatio), false);
            if (snap.antiImbalance()) {
                ctx.getSource().sendSuccess(
                        () -> Component.translatable("smartqueue.command.status.anti_imbalance",
                                snap.skippedNormalCount()), false);
            } else {
                ctx.getSource().sendSuccess(
                        () -> Component.translatable("smartqueue.command.status.balanced"), false);
            }
        }

        if (vipSlots > 0) {
            int vipOnline = qm.countVipEligibleOnline();
            int vipAvailable = Math.max(0, vipSlots - vipOnline);
            int nonVipLimit = effectiveMax - vipSlots;
            int nonVipOnline = qm.countNonVipOnline();
            ctx.getSource().sendSuccess(
                    () -> Component.translatable("smartqueue.command.status.vip_slots",
                            vipSlots, vipOnline, vipAvailable), false);
            ctx.getSource().sendSuccess(
                    () -> Component.translatable("smartqueue.command.status.nonvip_limit",
                            nonVipLimit, nonVipOnline), false);
        }

        ctx.getSource().sendSuccess(
                () -> Component.translatable("smartqueue.command.status.queue_size",
                        queued, Config.MAX_QUEUE_SIZE.get()), false);

        if (queued == 0) return 1;

        QueueEntry next = snap.nextAdmit();

        // Staff queue
        if (!snap.staff().isEmpty()) {
            ctx.getSource().sendSuccess(
                    () -> Component.translatable("smartqueue.command.status.queue_header_staff",
                            snap.staff().size()), false);
            renderQueueEntries(ctx, snap.staff(), next, "smartqueue.command.status.staff");
        }

        // Priority rejoin queue
        if (!snap.priority().isEmpty()) {
            ctx.getSource().sendSuccess(
                    () -> Component.translatable("smartqueue.command.status.queue_header_priority",
                            snap.priority().size()), false);
            renderQueueEntries(ctx, snap.priority(), next,
                    "smartqueue.command.status.priority_rejoin");
        }

        // VIP queue
        if (!snap.vip().isEmpty()) {
            ctx.getSource().sendSuccess(
                    () -> Component.translatable("smartqueue.command.status.queue_header_vip",
                            snap.vip().size()), false);
            renderQueueEntries(ctx, snap.vip(), next, "smartqueue.command.status.vip");
        }

        // Normal queue
        if (!snap.normal().isEmpty()) {
            ctx.getSource().sendSuccess(
                    () -> Component.translatable("smartqueue.command.status.queue_header_normal",
                            snap.normal().size()), false);
            renderQueueEntries(ctx, snap.normal(), next, "smartqueue.command.status.normal");
        }

        return 1;
    }

    private static void renderQueueEntries(CommandContext<CommandSourceStack> ctx,
                                           List<QueueEntry> entries, QueueEntry next, String typeKey) {
        for (int i = 0; i < entries.size(); i++) {
            QueueEntry entry = entries.get(i);
            final int pos = i + 1;
            boolean isNext = (next != null && entry == next);
            ctx.getSource().sendSuccess(
                    () -> Component.translatable(
                            isNext ? "smartqueue.command.status.queue_entry_next"
                                    : "smartqueue.command.status.queue_entry",
                            pos, Component.translatable(typeKey), entry.getName()), false);
        }
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
