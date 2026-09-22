package com.carpet.safesave.safesave.region;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Pure ticket policy, independent of Minecraft's asynchronous chunk lifecycle. */
public final class RegionTicketPolicy {
    private RegionTicketPolicy() {}

    public record Demand(long chunk, int fullRadius) {}

    public static boolean reaches(long from, long to, int radius) {
        return radius >= 0
                && Math.abs((long) (int) from - (int) to) <= radius
                && Math.abs((long) (int) (from >>> 32) - (int) (to >>> 32)) <= radius;
    }

    /** Include regions touched by the two-chunk FULL halo of our entity-ticking tickets.
     * Recompute from external roots, never from yesterday's region tickets (no self retention).
     */
    public static Set<Long> required(List<? extends Collection<Long>> regions, List<Demand> external) {
        Set<Long> result = new HashSet<>();
        Set<Integer> selected = new HashSet<>();
        boolean changed;
        do {
            changed = false;
            for (int i = 0; i < regions.size(); i++) {
                if (selected.contains(i)) continue;
                Collection<Long> region = regions.get(i);
                boolean hit = region.stream().anyMatch(chunk ->
                        external.stream().anyMatch(d -> reaches(d.chunk(), chunk, d.fullRadius()))
                        || result.stream().anyMatch(source -> reaches(source, chunk, 2)));
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
