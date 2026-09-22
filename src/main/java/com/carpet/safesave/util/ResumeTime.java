package com.carpet.safesave.util;

/** Translate an absolute time while retaining its offset from the saved simulation boundary. */
public final class ResumeTime {
    private ResumeTime() {}

    public static long rebase(long time, long savedAt, long resumedAt) {
        return savedAt == Long.MIN_VALUE ? time : resumedAt + (time - savedAt);
    }
}
