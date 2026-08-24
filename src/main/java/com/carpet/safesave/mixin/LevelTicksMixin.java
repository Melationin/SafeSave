package com.carpet.safesave.mixin;


import com.carpet.safesave.safesave.scheduledtick.TickContainerHolder;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.function.BiConsumer;

/**
 * 世界级计划刻索引的调试插桩 + 容器访问。
 *
 * <p>Shadow 字段刻意声明为通配符类型：目标是 {@code LevelTicks<T>}，但 JVM 字段描述符会擦除泛型，
 * 因此通配符既能匹配，又保持这是一个普通（非泛型）mixin。
 */
@Mixin(LevelTicks.class)
public abstract class LevelTicksMixin implements TickContainerHolder {

    @Shadow
    @Final
    private Long2ObjectMap<LevelChunkTicks<?>> allContainers;

    @Override
    public Long2ObjectMap<?> SS$containers() {
        return this.allContainers;
    }

}
