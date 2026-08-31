package com.carpet.safesave.safesave.region;


import com.carpet.safesave.rules.SafeSaveRules;
import com.carpet.safesave.safesave.SafeSaveLevelAccess;
import com.carpet.safesave.safesave.SafeSaveLevelState;
import com.carpet.safesave.safesave.scheduled.TickContainers;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;

import static com.carpet.safesave.util.Util.dimensionId;

/**
 * ProtectedRegion 的保存快照与启动恢复屏障。
 *
 * <p>Region 不做局部冻结：每次保存时，只把当时全部区块均完整加载的 Region 标记为下次启动目标；
 * region 解冻模式会全局冻结服务器，直到这些目标再次全部加载或超时。
 */
public final class ProtectedRegionManager {

    private static final int MAX_MISSING_NAMES_IN_LOG = 8;

    private ProtectedRegionManager() {
    }


    public static int captureSaveState(final ServerLevel level, final SafeSaveLevelState levelState, final boolean capture) {
        int required = 0;
        for (ProtectedRegion region : levelState.protectedRegions.byName.values()) {
            if (capture) {
                region.requiredAtStartup = SafeSaveRules.safeSaveRegions && isFullyLoaded(level, region);
            }
            if (region.requiredAtStartup) {
                required++;
            }
        }
        return required;
    }

    public static boolean isFullyLoaded(final ServerLevel level, final ProtectedRegion region) {
        Long2ObjectMap<?> blockContainers = TickContainers.blockContainers(level);
        Long2ObjectMap<?> fluidContainers = TickContainers.fluidContainers(level);
        for (long key : region.chunks) {
            if (!TickContainers.isReady(blockContainers.get(key), fluidContainers.get(key))
                    || !level.isPositionTickingWithEntitiesLoaded(key)) {
                return false;
            }
        }
        return !region.chunks.isEmpty();
    }

    public static StartupStatus startupStatus(final MinecraftServer server) {
        int required = 0;
        int loaded = 0;
        List<String> missing = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            ProtectedRegionState state = SafeSaveLevelAccess.of(level).protectedRegions;
            for (ProtectedRegion region : state.byName.values()) {
                if (!region.requiredAtStartup) {
                    continue;
                }
                required++;
                if (isFullyLoaded(level, region)) {
                    loaded++;
                } else if (missing.size() < MAX_MISSING_NAMES_IN_LOG) {
                    missing.add(dimensionId(level) + ":" + region.name);
                }
            }
        }
        return new StartupStatus(required, loaded, List.copyOf(missing));
    }

    public record StartupStatus(
            /** 上次保存选中的启动目标总数 */
            int required,
            /** 当前已完整加载的目标数 */
            int loaded,
            /** 未加载目标名（"维度:region"，最多保留前 8 个） */
            List<String> missing) {
        public boolean complete() {
            return this.loaded >= this.required;
        }

        public int missingCount() {
            return Math.max(this.required - this.loaded, 0);
        }

        public String missingDescription() {
            if (this.missing.isEmpty()) {
                return "[]";
            }
            String suffix = this.missingCount() > this.missing.size()
                    ? ", ... +" + (this.missingCount() - this.missing.size()) : "";
            return "[" + String.join(", ", this.missing) + suffix + "]";
        }
    }
}
