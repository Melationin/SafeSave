package com.carpet.safesave.safesave.scheduled;

import java.util.List;


public interface SafeTickContainer {


    boolean SS$hasPendingTicks();

    void SS$replaceAll(List<?> scheduledTicks);

    List<?> SS$snapshotQueue();
}
