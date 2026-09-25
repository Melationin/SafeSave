package com.carpet.safesave.util;


public final class ResumeTime {
    private ResumeTime() {}

    public static long rebase(long time, long savedAt, long resumedAt) {
        return savedAt == Long.MIN_VALUE ? time : resumedAt + (time - savedAt);
    }
}
