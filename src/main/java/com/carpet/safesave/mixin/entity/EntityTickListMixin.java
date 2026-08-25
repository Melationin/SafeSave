package com.carpet.safesave.mixin.entity;

import com.carpet.safesave.safesave.entity.EntityOrderHolder;
import com.carpet.safesave.safesave.entity.EntityOrderManager;
import com.carpet.safesave.safesave.entity.EntityTickListAccess;
import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityTickList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * 实体 tick 列表的接入：序号分配（进入列表时）、活跃表快照/整体重建。
 */
@Mixin(EntityTickList.class)
public abstract class EntityTickListMixin implements EntityTickListAccess {

    @Shadow
    private Int2ObjectMap<Entity> active;

    @Override
    public List<Entity> SS$snapshotActive() {
        return new ArrayList<>(this.active.values());
    }

    @Override
    public void SS$rebuildActive(final List<Entity> ordered) {
        Int2ObjectLinkedOpenHashMap<Entity> rebuilt = new Int2ObjectLinkedOpenHashMap<>(ordered.size());
        for (Entity entity : ordered) {
            rebuilt.put(entity.getId(), entity);
        }
        this.active = rebuilt;
    }

    /**
     * {@code add} 是所有实体进入 tick 列表的唯一入口：
     * 新生成的实体在此分配序号；从 NBT 加载的有序号实体不需要额外记录，
     * 其所在区块会在非冻结 tick 开头由 {@code SafeSaveManager.rebuildNewChunks} 统一识别并重排。
     */
    @Inject(method = "add", at = @At("HEAD"))
    private void SS$onEntityAdded(final Entity entity, final CallbackInfo ci) {
        if (!(entity instanceof EntityOrderHolder holder)) {
            return;
        }
        if (holder.SS$entityOrder() == Long.MIN_VALUE) {
            holder.SS$assignEntityOrder(EntityOrderManager.nextOrder());
        }
    }
}
