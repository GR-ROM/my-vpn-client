package su.grinev.myvpn;

import java.util.function.BooleanSupplier;

/**
 * Level-triggered self-heal for a missed {@code ACTION_SCREEN_ON}.
 *
 * <p>{@code MyVpnService} pauses/resumes the server→client downlink on the screen-off/on broadcasts
 * (edge-triggered). Android Doze can delay or drop {@code ACTION_SCREEN_ON} after a long screen-off,
 * so the resume edge never arrives: the service stays parked in {@code SLEEPING} with the downlink
 * {@code FLOW_CONTROL STOP}'d on the server, and the phone shows "connected but no internet" until a
 * manual screen off/on toggle. This reconciler is polled on a timer; each {@link #tick()} compares the
 * <em>real</em> screen state against what the service believes and, on a mismatch (screen interactive
 * but service still sleeping), fires the resume.
 *
 * <p>Pure of Android APIs (screen state and the resume action are injected), so it is unit-testable on
 * the JVM. Not thread-safe by itself — the service polls it on a single thread (the main looper), the
 * same thread the screen broadcasts run on, so it never races them.
 */
public final class ScreenStateReconciler {

    private final BooleanSupplier screenInteractive;
    private final BooleanSupplier serviceSleeping;
    private final Runnable resume;

    public ScreenStateReconciler(BooleanSupplier screenInteractive, BooleanSupplier serviceSleeping, Runnable resume) {
        this.screenInteractive = screenInteractive;
        this.serviceSleeping = serviceSleeping;
        this.resume = resume;
    }

    /** True when the screen is really on but the service still thinks it is sleeping — a missed resume edge. */
    static boolean needsResume(boolean screenInteractive, boolean serviceSleeping) {
        return screenInteractive && serviceSleeping;
    }

    /** One poll tick: synthesize the resume iff a screen-on edge was missed. */
    public void tick() {
        if (needsResume(screenInteractive.getAsBoolean(), serviceSleeping.getAsBoolean())) {
            resume.run();
        }
    }
}
