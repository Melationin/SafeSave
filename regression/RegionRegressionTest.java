package com.carpet.safesave;

import com.carpet.safesave.safesave.region.RegionTicketPolicy;
import com.carpet.safesave.util.ResumeTime;
import java.util.List;
import java.util.Set;

/** Dependency-free regression checks, run by regionRegressionTest / check in both versions. */
public final class RegionRegressionTest {
    private static long chunk(int x, int z) { return (x & 0xffffffffL) | ((z & 0xffffffffL) << 32); }
    private static void expect(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        long a = chunk(10, 10), b = chunk(20, 20), c = chunk(22, 22), d = chunk(40, 40);
        // e 与 b 相距 1；c 与 b 相距 2。用来区分 BLOCK_TICKING 光晕（半径 1）与 FULL 光晕（半径 2）。
        long e = chunk(21, 21);
        var regions = List.of(Set.of(a, b), Set.of(c, d));

        // REGION 票是 ENTITY_TICKING(31)，原版模拟边界是 BLOCK_TICKING(32)，所以它自己撑出的
        // "需要接管"的一圈是 32 - 31 = 1。距离 2 是 FULL(33)，原版惰性，不该被拉进来。
        final int regionRadius = 1;

        expect(RegionTicketPolicy.required(regions, List.of(new RegionTicketPolicy.Demand(a, 0)),
                        regionRadius).equals(Set.of(a, b)),
                "One external ticket must activate a whole region, but not a region two chunks away");
        expect(RegionTicketPolicy.required(regions, List.of(new RegionTicketPolicy.Demand(a, 0)),
                        2).equals(Set.of(a, b, c, d)),
                "Two-chunk FULL propagation is still expressible when the caller asks for radius 2");
        expect(RegionTicketPolicy.required(List.of(Set.of(a, b), Set.of(e, d)),
                        List.of(new RegionTicketPolicy.Demand(a, 0)), regionRadius).equals(Set.of(a, b, e, d)),
                "A region one chunk from an already-selected region must be pulled in (BLOCK_TICKING halo)");
        expect(RegionTicketPolicy.required(regions, List.of(), regionRadius).isEmpty(),
                "A cycle of region tickets must unload after the last external root disappears");
        expect(RegionTicketPolicy.required(regions,
                        List.of(new RegionTicketPolicy.Demand(a, -1)), regionRadius).isEmpty(),
                "Tickets that cannot push anything to BLOCK_TICKING must not activate a region");
        expect(RegionTicketPolicy.required(regions,
                        List.of(new RegionTicketPolicy.Demand(chunk(9, 9), 1)), regionRadius)
                .containsAll(Set.of(a, b)),
                "External ticket propagation must count, not just tickets located inside the region");
        expect(RegionTicketPolicy.required(List.of(Set.of(a, b), Set.of(b, d)),
                        List.of(new RegionTicketPolicy.Demand(a, 0)), regionRadius).equals(Set.of(a, b, d)),
                "Overlapping regions share a lifetime");

        expect(RegionTicketPolicy.reaches(chunk(-2, -2), chunk(0, 0), 2), "Negative coordinate packing");
        expect(!RegionTicketPolicy.reaches(chunk(Integer.MIN_VALUE, 0), chunk(Integer.MAX_VALUE, 0), 2),
                "Distance calculation must not overflow");
        expect(RegionTicketPolicy.reaches(b, e, 1) && !RegionTicketPolicy.reaches(b, c, 1),
                "Chebyshev metric must match ChunkTracker's 8-neighbour propagation");

        expect(ResumeTime.rebase(102, 100, 1000) == 1002, "Preserve remaining scheduled delay");
        expect(ResumeTime.rebase(100, 100, 1000) == 1000, "Preserve lastTicked == gameTime");
        expect(ResumeTime.rebase(99, 100, 1000) == 999, "Preserve age of a piston, do not mark every piston as ticked now");
        expect(ResumeTime.rebase(99, Long.MIN_VALUE, 1000) == 99, "Legacy NBT without a timestamp remains unchanged");
        expect(ResumeTime.rebase(ResumeTime.rebase(105, 100, 1000), 1000, 2000) == 2005,
                "Repeated unload and reload must not accumulate drift");
        System.out.println("Region ticket and resume-time regression checks passed.");
    }
}
