package com.carpet.safesave.mixin.scheduled;


import com.carpet.safesave.safesave.scheduled.TickContainerHolder;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.LevelTicks;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

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
