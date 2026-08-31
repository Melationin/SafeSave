package com.carpet.safesave.safesave.region;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.ListTag;

import java.util.LinkedHashMap;
import java.util.Map;

public final class ProtectedRegionCodec {

    private static final String KEY_NAME = "name";
    private static final String KEY_CHUNKS = "chunks";
    private static final String KEY_REQUIRED_AT_STARTUP = "required_at_startup";

    private ProtectedRegionCodec() {
    }

    public static ListTag save(final Map<String, ProtectedRegion> regions) {
        ListTag list = new ListTag();
        for (ProtectedRegion region : regions.values()) {
            CompoundTag tag = new CompoundTag();
            tag.putString(KEY_NAME, region.name);
            tag.put(KEY_CHUNKS, new LongArrayTag(region.chunks.toLongArray()));
            tag.putBoolean(KEY_REQUIRED_AT_STARTUP, region.requiredAtStartup);
            list.add(tag);
        }
        return list;
    }

    /*按保存顺序恢复的 name -> region；空列表返回空 map */
    public static Map<String, ProtectedRegion> load(final ListTag list) {
        Map<String, ProtectedRegion> out = new LinkedHashMap<>();
        if (list == null || list.isEmpty()) {
            return out;
        }
        for (int i = 0; i < list.size(); i++) {
            final int index = i;
            list.getCompound(index).ifPresent(tag -> {
                String name = tag.getStringOr(KEY_NAME, "");
                if (name.isEmpty()) {
                    return;
                }
                ProtectedRegion region = new ProtectedRegion(name);
                long[] arr = tag.getLongArray(KEY_CHUNKS).orElseGet(() -> new long[0]);
                for (long key : arr) {
                    region.chunks.add(key);
                }
                region.requiredAtStartup = tag.getBooleanOr(KEY_REQUIRED_AT_STARTUP, false);
                out.put(name, region);
            });
        }
        return out;
    }
}
