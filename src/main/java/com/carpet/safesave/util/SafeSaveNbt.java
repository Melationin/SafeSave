package com.carpet.safesave.util;


public final class SafeSaveNbt {
    private SafeSaveNbt() {
    }

    public static final String KEY_SAFE_SAVE = "safeSave";

    public static boolean enabled(final net.minecraft.world.level.Level level) {
        return level instanceof net.minecraft.server.level.ServerLevel serverLevel
                && com.carpet.safesave.safesave.SafeSaveManager.shouldRun(serverLevel.getServer());
    }

    public static NbtView.Writer child(final NbtView.Writer output) {
        return output.child(KEY_SAFE_SAVE);
    }

    public static NbtView.Reader childOrNull(final NbtView.Reader input) {
        return input.child(KEY_SAFE_SAVE).orElse(null);
    }
}
