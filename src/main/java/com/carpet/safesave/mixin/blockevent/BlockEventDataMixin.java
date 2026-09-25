package com.carpet.safesave.mixin.blockevent;

import com.carpet.safesave.safesave.blockevent.BlockEventOrderHolder;
import net.minecraft.world.level.BlockEventData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;


@Mixin(BlockEventData.class)
public abstract class BlockEventDataMixin implements BlockEventOrderHolder {

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
