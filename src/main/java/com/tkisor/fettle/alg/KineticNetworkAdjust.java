package com.tkisor.fettle.alg;

/** Implemented (via mixin) by {@code KineticNetwork} so speed changes can adjust the running sums. */
public interface KineticNetworkAdjust {

    void fettle$adjustStress(float delta);

    void fettle$adjustCapacity(float delta);
}
