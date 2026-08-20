package com.example.carpet.mixin;

import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockEventData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Read access to {@code ServerLevel.blockEvents}.
 *
 * <p>Note this is an {@code ObjectLinkedOpenHashSet}, not a queue: identical
 * {@code BlockEventData(pos, block, paramA, paramB)} records added within the same tick are silently
 * de-duplicated. The debug channel surfaces that, which is otherwise invisible.
 *
 * <p>An {@code @Accessor} interface is used rather than {@code @Shadow} because this field's
 * visibility differs between 26.1 and 26.2.
 */
@Mixin(ServerLevel.class)
public interface ServerLevelBlockEventsAccessor {

    @Accessor("blockEvents")
    ObjectLinkedOpenHashSet<BlockEventData> carpetExample$blockEvents();
}
