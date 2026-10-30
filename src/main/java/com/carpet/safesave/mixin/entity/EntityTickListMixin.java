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


    @Inject(method = "add", at = @At("HEAD"))
    private void SS$onEntityAdded(final Entity entity, final CallbackInfo ci) {
        if (this.active.containsKey(entity.getId()) || !(entity instanceof EntityOrderHolder holder)) {
            return;
        }
        if (holder.SS$markTickListEntry() || holder.SS$entityOrder() == Long.MIN_VALUE) {
            holder.SS$assignEntityOrder(EntityOrderManager.nextOrder(entity));
        }
    }
}
