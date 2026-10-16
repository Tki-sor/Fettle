package com.tkisor.fettle.observe;

/** Per-instance state of the BE-sleeping pilot, implemented by the block entity mixin. */
public interface BeSleepState {

    int fettle$observedTicks();

    void fettle$observedTicks(int value);

    boolean fettle$sleeping();

    void fettle$sleeping(boolean value);

    int fettle$skippedTicks();

    void fettle$skippedTicks(int value);

    boolean fettle$sawChange();

    void fettle$sawChange(boolean value);
}
