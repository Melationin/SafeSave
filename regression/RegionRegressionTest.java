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
        var regions = List.of(Set.of(a, b), Set.of(c, d));
        expect(RegionTicketPolicy.required(regions, List.of(new RegionTicketPolicy.Demand(a, 0)))
                .equals(Set.of(a, b, c, d)), "One external ticket must activate whole regions, including the FULL halo");
        expect(RegionTicketPolicy.required(regions, List.of()).isEmpty(),
                "A cycle of region tickets must unload after the last external root disappears");
        expect(RegionTicketPolicy.required(regions, List.of(new RegionTicketPolicy.Demand(a, -1))).isEmpty(),
                "Generation-only tickets must not activate a region");
        expect(RegionTicketPolicy.required(regions, List.of(new RegionTicketPolicy.Demand(chunk(9, 9), 1)))
                .containsAll(Set.of(a, b)), "External ticket propagation must count, not just tickets located inside the region");
        expect(RegionTicketPolicy.required(List.of(Set.of(a, b), Set.of(b, d)),
                List.of(new RegionTicketPolicy.Demand(a, 0))).equals(Set.of(a, b, d)), "Overlapping regions share a lifetime");
        expect(RegionTicketPolicy.reaches(chunk(-2, -2), chunk(0, 0), 2), "Negative coordinate packing");
        expect(!RegionTicketPolicy.reaches(chunk(Integer.MIN_VALUE, 0), chunk(Integer.MAX_VALUE, 0), 2),
                "Distance calculation must not overflow");
        expect(ResumeTime.rebase(102, 100, 1000) == 1002, "Preserve remaining scheduled delay");
        expect(ResumeTime.rebase(100, 100, 1000) == 1000, "Preserve lastTicked == gameTime");
        expect(ResumeTime.rebase(99, 100, 1000) == 999, "Preserve age of a piston, do not mark every piston as ticked now");
        expect(ResumeTime.rebase(99, Long.MIN_VALUE, 1000) == 99, "Legacy NBT without a timestamp remains unchanged");
        expect(ResumeTime.rebase(ResumeTime.rebase(105, 100, 1000), 1000, 2000) == 2005,
                "Repeated unload and reload must not accumulate drift");
        System.out.println("Region ticket and resume-time regression checks passed.");
    }
}
