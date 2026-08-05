package org.skydream.smartqueue.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import javax.annotation.Nullable;

@Mixin(Minecraft.class)
public interface MinecraftAccessor {
    @Accessor("pendingConnection")
    @Nullable
    Connection smartqueue$getPendingConnection();
}
