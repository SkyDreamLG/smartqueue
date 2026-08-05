package org.skydream.smartqueue;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class Config {

    // ── Main (queue settings) ──

    private static final ModConfigSpec.Builder MAIN_BUILDER = new ModConfigSpec.Builder();
    public static final ModConfigSpec MAIN_SPEC;

    public static final ModConfigSpec.BooleanValue ENABLED;
    public static final ModConfigSpec.IntValue EFFECTIVE_MAX_PLAYERS;
    public static final ModConfigSpec.IntValue MAX_QUEUE_SIZE;
    public static final ModConfigSpec.IntValue NORMAL_ADMIT_INTERVAL_TICKS;
    public static final ModConfigSpec.IntValue VIP_ADMIT_INTERVAL_TICKS;
    public static final ModConfigSpec.IntValue REJOIN_GRACE_TICKS;
    public static final ModConfigSpec.BooleanValue STAFF_BYPASS_QUEUE;

    static {
        MAIN_BUILDER.comment("SmartQueue Main Configuration",
                "All values can be hot-reloaded by editing this file while the server is running.",
                "Use /smartqueue reload after editing.");

        MAIN_BUILDER.push("queue");
        ENABLED = MAIN_BUILDER
                .comment("Master switch. When false, all queuing is disabled and queued players are admitted immediately.")
                .translation("smartqueue.config.enabled")
                .define("enabled", true);

        EFFECTIVE_MAX_PLAYERS = MAIN_BUILDER
                .comment("Maximum number of active (non-queued) players allowed on the server at once.",
                        "Players beyond this count will be placed in the queue.")
                .translation("smartqueue.config.effective_max_players")
                .defineInRange("effective_max_players", 20, 1, 1024);

        MAX_QUEUE_SIZE = MAIN_BUILDER
                .comment("Maximum number of players that can wait in the queue.",
                        "New connections beyond this limit will be rejected.")
                .translation("smartqueue.config.max_queue_size")
                .defineInRange("max_queue_size", 50, 0, 1024);

        NORMAL_ADMIT_INTERVAL_TICKS = MAIN_BUILDER
                .comment("Interval in server ticks (20 ticks = 1 second) between admitting normal players from the queue.")
                .translation("smartqueue.config.normal_admit_interval_ticks")
                .defineInRange("normal_admit_interval_ticks", 100, 1, 72000);

        VIP_ADMIT_INTERVAL_TICKS = MAIN_BUILDER
                .comment("Interval in server ticks (20 ticks = 1 second) between admitting staff/VIP players from the queue.")
                .translation("smartqueue.config.vip_admit_interval_ticks")
                .defineInRange("vip_admit_interval_ticks", 40, 1, 72000);

        REJOIN_GRACE_TICKS = MAIN_BUILDER
                .comment("Time window in server ticks after disconnecting during which a player can rejoin",
                        "and keep their queue position. 0 disables rejoin position restore.",
                        "Default: 6000 ticks = 5 minutes.")
                .translation("smartqueue.config.rejoin_grace_ticks")
                .defineInRange("rejoin_grace_ticks", 6000, 0, 1728000);

        STAFF_BYPASS_QUEUE = MAIN_BUILDER
                .comment("Staff behavior when the server is full.",
                        "false (default) — Staff enter the queue but are placed at the front.",
                        "true — Staff skip the queue entirely and join directly.",
                        "WARNING: When set to true, ensure effective_max_players is LOWER than",
                        "server.properties max-players to reserve slots for staff.",
                        "Otherwise staff may push the server beyond the vanilla player limit.")
                .translation("smartqueue.config.staff_bypass_queue")
                .define("staff_bypass_queue", false);
        MAIN_BUILDER.pop();

        MAIN_SPEC = MAIN_BUILDER.build();
    }

    // ── Staff list ──

    private static final ModConfigSpec.Builder STAFF_BUILDER = new ModConfigSpec.Builder();
    public static final ModConfigSpec STAFF_SPEC;

    public static final ModConfigSpec.ConfigValue<List<? extends String>> STAFF_LIST;

    static {
        STAFF_BUILDER.comment("SmartQueue Staff List",
                "Player usernames (case-insensitive) who get highest priority in the queue.",
                "Can be hot-reloaded while the server is running.");

        STAFF_LIST = STAFF_BUILDER
                .comment("Staff player usernames.")
                .translation("smartqueue.config.staff_list")
                .defineListAllowEmpty("staff", List.of(), o -> o instanceof String s && !s.isBlank());

        STAFF_SPEC = STAFF_BUILDER.build();
    }

    // ── VIP list ──

    private static final ModConfigSpec.Builder VIP_BUILDER = new ModConfigSpec.Builder();
    public static final ModConfigSpec VIP_SPEC;

    public static final ModConfigSpec.ConfigValue<List<? extends String>> VIP_LIST;

    static {
        VIP_BUILDER.comment("SmartQueue VIP List",
                "Player usernames (case-insensitive) who get priority in the queue.",
                "VIPs are admitted at the faster VIP admit interval.",
                "Can be hot-reloaded while the server is running.");

        VIP_LIST = VIP_BUILDER
                .comment("VIP player usernames.")
                .translation("smartqueue.config.vip_list")
                .defineListAllowEmpty("vip", List.of(), o -> o instanceof String s && !s.isBlank());

        VIP_SPEC = VIP_BUILDER.build();
    }

    public static void register(ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, MAIN_SPEC, "smartqueue-server.toml");
        container.registerConfig(ModConfig.Type.SERVER, STAFF_SPEC, "smartqueue-staff.toml");
        container.registerConfig(ModConfig.Type.SERVER, VIP_SPEC, "smartqueue-vip.toml");
    }

    public static void onReload(ModConfigEvent.Reloading event) {
        Smartqueue.LOGGER.info("SmartQueue config reloaded.");
    }

    // ── Save lists to file ──

    public static void saveStaffConfig() {
        saveListConfig("smartqueue-staff.toml", "staff", STAFF_LIST.get());
    }

    public static void saveVipConfig() {
        saveListConfig("smartqueue-vip.toml", "vip", VIP_LIST.get());
    }

    private static void saveListConfig(String fileName, String key, List<? extends String> list) {
        Path path = FMLPaths.CONFIGDIR.get().resolve(fileName);
        try {
            StringBuilder sb = new StringBuilder();
            if (list.isEmpty()) {
                sb.append(key).append(" = []\n");
            } else {
                sb.append(key).append(" = [");
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append('"').append(list.get(i)).append('"');
                }
                sb.append("]\n");
            }
            Files.writeString(path, sb.toString());
            Smartqueue.LOGGER.debug("Saved {}: {}", fileName, list);
        } catch (IOException e) {
            Smartqueue.LOGGER.warn("Failed to save {}: {}", fileName, e.getMessage());
        }
    }

    private Config() {}
}
