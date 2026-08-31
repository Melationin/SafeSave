package com.carpet.safesave.safesave.entity;


import com.carpet.safesave.debug.DebugLog;
import com.carpet.safesave.safesave.SafeSaveLevelAccess;
import com.carpet.safesave.safesave.SafeSaveLevelState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityTickList;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

import static com.carpet.safesave.util.Util.dimensionId;


public final class EntityOrderManager {

    private static final Comparator<Entity> ENTITY_ORDER = Comparator.comparingLong(
            e -> e instanceof EntityOrderHolder h && h.SS$entityOrder() != Long.MIN_VALUE
                    ? h.SS$entityOrder() : Long.MAX_VALUE);

    private EntityOrderManager() {
    }

    public static long nextOrder(final Entity entity) {
        if (entity.level() instanceof ServerLevel serverLevel) {
            return SafeSaveLevelAccess.of(serverLevel).entityOrder.next();
        }
        return 0L;
    }

    public static void observeOrder(final Entity entity, final long restored) {
        if (entity.level() instanceof ServerLevel serverLevel) {
            SafeSaveLevelAccess.of(serverLevel).entityOrder.observe(restored);
        }
    }

    public static void rebuildChunks(final ServerLevel level, final Collection<Long> newChunks) {
        if (newChunks == null || newChunks.isEmpty()) {
            return;
        }
        EntityTickList list = ((ServerLevelTickListAccess) level).SS$getEntityTickList();
        List<Entity> all = ((EntityTickListAccess) list).SS$snapshotActive();

        List<Entity> affected = new ArrayList<>();
        for (Entity entity : all) {
            if (newChunks.contains(entity.chunkPosition().pack())) {
                affected.add(entity);
            }
        }
        if (affected.size() < 2) {
            return;
        }
        affected.sort(ENTITY_ORDER);
        all.removeAll(affected);
        all.addAll(affected);
        ((EntityTickListAccess) list).SS$rebuildActive(all);
        DebugLog.info("{}: rebuilt cross-chunk tick order of {} entity(ies) in {} newly loaded chunk(s)",
                dimensionId(level), affected.size(), newChunks.size());
    }
}
