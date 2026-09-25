package com.carpet.safesave.util;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.Optional;

/**
 * Version-neutral accessors for the two {@code CompoundTag} methods whose <em>return type</em>
 * changed in 1.21.5 and therefore cannot be bridged by {@link TagCompatAccess}.
 *
 * <p>{@code getString(String)} and {@code getCompound(String)} returned the raw value up to 1.21.4
 * and an {@code Optional} from 1.21.5 on. Same signature, different return type - so a mixin
 * interface cannot help and the call sites go through here instead.
 */
public final class TagCompat {
    private TagCompat() {
    }

    /** {@code tag.getCompound(key)} as an {@code Optional}, on every version. */
    public static Optional<CompoundTag> compound(final CompoundTag tag, final String key) {
        //? if >=1.21.5 {
        return tag.getCompound(key);
        //?} else {
        /*return tag.contains(key, Tag.TAG_COMPOUND)
                ? Optional.of(tag.getCompound(key))
                : Optional.empty();
        *///?}
    }

    /** {@code list.getCompound(index)} as an {@code Optional}, on every version. */
    public static Optional<CompoundTag> compound(final ListTag list, final int index) {
        //? if >=1.21.5 {
        return list.getCompound(index);
        //?} else {
        /*Tag tag = index >= 0 && index < list.size() ? list.get(index) : null;
        return tag instanceof CompoundTag compound ? Optional.of(compound) : Optional.empty();
        *///?}
    }

    /** {@code tag.getString(key)} as an {@code Optional}, on every version. */
    public static Optional<String> string(final CompoundTag tag, final String key) {
        //? if >=1.21.5 {
        return tag.getString(key);
        //?} else {
        /*return tag.contains(key, Tag.TAG_STRING)
                ? Optional.of(tag.getString(key))
                : Optional.empty();
        *///?}
    }

    /**
     * The <em>live</em> child compound stored under {@code key}, or {@code null} when the key holds
     * no compound. Callers write into the returned tag in place, so it must not be a copy - which is
     * exactly why this cannot go through {@link #compound(CompoundTag, String)}.
     */
    public static CompoundTag childOrNull(final CompoundTag tag, final String key) {
        //? if >=1.21.5 {
        return tag.getCompound(key).orElse(null);
        //?} else {
        /*return tag.contains(key, Tag.TAG_COMPOUND) ? tag.getCompound(key) : null;
        *///?}
    }

    /** {@code tag.getLongArray(key)} as a plain array, empty when absent, on every version. */
    public static long[] longArrayOrEmpty(final CompoundTag tag, final String key) {
        //? if >=1.21.5 {
        return tag.getLongArray(key).orElseGet(() -> new long[0]);
        //?} else {
        /*return tag.contains(key, Tag.TAG_LONG_ARRAY) ? tag.getLongArray(key) : new long[0];
        *///?}
    }
}
