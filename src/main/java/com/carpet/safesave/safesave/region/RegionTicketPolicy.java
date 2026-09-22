package com.carpet.safesave.safesave.region;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Pure ticket policy, independent of Minecraft's asynchronous chunk lifecycle. */
public final class RegionTicketPolicy {
    private RegionTicketPolicy() {}

    /**
     * @param chunk            票据所在区块（打包的 {@code ChunkPos}）
     * @param simulationRadius 该票据能把周围推进到"原版会真正模拟"的最低级别
     *                         （{@code BLOCK_TICKING}）的 Chebyshev 半径，由调用方按
     *                         {@code BLOCK_TICKING_LEVEL - ticket.getTicketLevel()} 算出。
     *                         半径 &lt; 0 表示这张票推不动任何区块，永远不会触发 region。
     */
    public record Demand(long chunk, int simulationRadius) {}

    /** 打包的 {@code ChunkPos} 上的 Chebyshev 距离判定。原版 {@code ChunkTracker} 用 8 邻域、
     * 每步级别 +1 传播票据，所以 Chebyshev 距离 d 处级别 = 票级别 + d —— 这里与之一一对应。 */
    public static boolean reaches(long from, long to, int radius) {
        return radius >= 0
                && Math.abs((long) (int) from - (int) to) <= radius
                && Math.abs((long) (int) (from >>> 32) - (int) (to >>> 32)) <= radius;
    }

    /** 从外部根重算应被接管的 region 集合（**绝不**以上一轮的 region 票据为根，否则会自我续期）。
     *
     * <p>触发条件是"原版本来就会模拟"：外部票把某个 region 区块推到 {@code BLOCK_TICKING}
     * 及以上，或已被选中的 region 区块的 {@code BLOCK_TICKING} 光晕罩到了它 —— 因为
     * {@code maySimulate} 对"受保护但无票"的区块返回 false，这种区块若不接管就会被冻住，
     * 而原版本来是模拟的。
     *
     * @param regionRadius 我们自己的 REGION 票（{@code ENTITY_TICKING}）撑出的
     *                     {@code BLOCK_TICKING} 光晕半径，即 {@code 1}。
     */
    public static Set<Long> required(List<? extends Collection<Long>> regions, List<Demand> external,
                                     int regionRadius) {
        Set<Long> result = new HashSet<>();
        Set<Integer> selected = new HashSet<>();
        boolean changed;
        do {
            changed = false;
            for (int i = 0; i < regions.size(); i++) {
                if (selected.contains(i)) continue;
                Collection<Long> region = regions.get(i);
                boolean hit = region.stream().anyMatch(chunk ->
                        external.stream().anyMatch(d -> reaches(d.chunk(), chunk, d.simulationRadius()))
                        || result.stream().anyMatch(source -> reaches(source, chunk, regionRadius)));
                if (hit) {
                    selected.add(i);
                    result.addAll(region);
                    changed = true;
                }
            }
        } while (changed);
        return result;
    }
}
