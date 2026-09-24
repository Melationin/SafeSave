package com.carpet.safesave.mixin.blockevent;

import com.carpet.safesave.safesave.blockevent.BlockEventOrderHolder;
import net.minecraft.world.level.BlockEventData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/*
 * record 的 equals/hashCode 只由分量生成，本字段不参与，
 * 因此 ServerLevel.blockEvents 这个有序集合仍按 (pos, block, paramA, paramB) 去重：
 * 重复入队时集合保留先入队的旧实例，序号自然只在首次入队时写入。
 */
@Mixin(BlockEventData.class)
public abstract class BlockEventDataMixin implements BlockEventOrderHolder {

    // 刻意不带初始化器：进入 blockEvents 的每个实例都已被赋过序号，不需要哨兵值。
    @Unique
    private long SS$order;

    @Override
    public long SS$blockEventOrder() {
        return this.SS$order;
    }

    @Override
    public void SS$assignBlockEventOrder(final long order) {
        this.SS$order = order;
    }
}
