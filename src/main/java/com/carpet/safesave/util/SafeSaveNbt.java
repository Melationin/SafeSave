package com.carpet.safesave.util;


/**
 * Entry point for the mod's own NBT sub-tree ("safeSave").
 *
 * <p>The actual read/write surface lives in {@link NbtView}; this class is deliberately free of any
 * Minecraft type so it compiles identically on every supported version.
 */
public final class SafeSaveNbt {
    private SafeSaveNbt() {
    }

    public static final String KEY_SAFE_SAVE = "safeSave";

    public static boolean enabled() {
        return com.carpet.safesave.safesave.SafeSaveManager.shouldRun();
    }

    /** Child writer for the mod's data, reusing an existing child when the parent already has one. */
    public static NbtView.Writer child(final NbtView.Writer output) {
        return output.child(KEY_SAFE_SAVE);
    }

    /** Child reader for the mod's data, or {@code null} when the parent carries none. */
    public static NbtView.Reader childOrNull(final NbtView.Reader input) {
        return input.child(KEY_SAFE_SAVE).orElse(null);
    }
}
