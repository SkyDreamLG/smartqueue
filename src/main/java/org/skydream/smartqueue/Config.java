package org.skydream.smartqueue;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.BooleanValue ENABLED;
    public static final ModConfigSpec.IntValue EFFECTIVE_MAX_PLAYERS;
    public static final ModConfigSpec.IntValue MAX_QUEUE_SIZE;
    public static final ModConfigSpec.IntValue NORMAL_ADMIT_INTERVAL_TICKS;
    public static final ModConfigSpec.IntValue VIP_ADMIT_INTERVAL_TICKS;
    public static final ModConfigSpec.IntValue REJOIN_GRACE_TICKS;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> STAFF_LIST;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> VIP_LIST;

    static {
        BUILDER.comment("SmartQueue Server Configuration",
                "All values can be hot-reloaded by editing this file while the server is running.",
                "Use /smartqueue reload to confirm the reload.");

        BUILDER.push("queue");
        ENABLED = BUILDER
                .comment("Master switch. When false, all queuing is disabled and queued players are admitted immediately.")
                .translation("smartqueue.config.enabled")
                .define("enabled", true);

        EFFECTIVE_MAX_PLAYERS = BUILDER
                .comment("Maximum number of active (non-queued) players allowed on the server at once.",
                        "Players beyond this count will be placed in the queue.")
                .translation("smartqueue.config.effective_max_players")
                .defineInRange("effective_max_players", 20, 1, 1024);

        MAX_QUEUE_SIZE = BUILDER
                .comment("Maximum number of players that can wait in the queue.",
                        "New connections beyond this limit will be rejected.")
                .translation("smartqueue.config.max_queue_size")
                .defineInRange("max_queue_size", 50, 0, 1024);

        NORMAL_ADMIT_INTERVAL_TICKS = BUILDER
                .comment("Interval in server ticks (20 ticks = 1 second) between admitting normal players from the queue.")
                .translation("smartqueue.config.normal_admit_interval_ticks")
                .defineInRange("normal_admit_interval_ticks", 100, 1, 72000);

        VIP_ADMIT_INTERVAL_TICKS = BUILDER
                .comment("Interval in server ticks (20 ticks = 1 second) between admitting staff/VIP players from the queue.")
                .translation("smartqueue.config.vip_admit_interval_ticks")
                .defineInRange("vip_admit_interval_ticks", 40, 1, 72000);

        REJOIN_GRACE_TICKS = BUILDER
                .comment("Time window in server ticks after disconnecting during which a player can rejoin",
                        "and keep their queue position. 0 disables rejoin position restore.",
                        "Default: 6000 ticks = 5 minutes.")
                .translation("smartqueue.config.rejoin_grace_ticks")
                .defineInRange("rejoin_grace_ticks", 6000, 0, 1728000);
        BUILDER.pop();

        BUILDER.push("staff");
        STAFF_LIST = BUILDER
                .comment("List of player usernames (case-insensitive) who skip the queue entirely.",
                        "When the server is full, staff are placed at the front of the queue (position 1).")
                .translation("smartqueue.config.staff_list")
                .defineListAllowEmpty("list", List.of(), o -> o instanceof String s && !s.isBlank());
        BUILDER.pop();

        BUILDER.push("vip");
        VIP_LIST = BUILDER
                .comment("List of player usernames (case-insensitive) who get priority in the queue.",
                        "VIPs are placed after staff but before normal players, and admitted at the faster VIP interval.")
                .translation("smartqueue.config.vip_list")
                .defineListAllowEmpty("list", List.of(), o -> o instanceof String s && !s.isBlank());
        BUILDER.pop();

        SPEC = BUILDER.build();
    }

    public static void register(ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, SPEC);
    }

    public static void onReload(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == SPEC) {
            Smartqueue.LOGGER.info("SmartQueue config reloaded from disk.");
        }
    }

    private Config() {}
}
