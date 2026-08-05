package org.skydream.smartqueue;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.config.ModConfigEvent;
import org.slf4j.Logger;

@Mod(Smartqueue.MODID)
public class Smartqueue {

    public static final String MODID = "smartqueue";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Smartqueue(IEventBus modBus, ModContainer container) {
        Config.register(container);

        modBus.addListener(this::onConfigReload);
    }

    private void onConfigReload(ModConfigEvent.Reloading event) {
        Config.onReload(event);
    }
}
