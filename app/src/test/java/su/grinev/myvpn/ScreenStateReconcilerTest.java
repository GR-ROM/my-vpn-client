package su.grinev.myvpn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Unit tests for {@link ScreenStateReconciler} — the level-triggered self-heal for a missed
 * {@code ACTION_SCREEN_ON} (Doze dropping the screen-on broadcast after a long screen-off, leaving the
 * downlink FLOW_CONTROL STOP'd → "connected but no internet").
 */
public class ScreenStateReconcilerTest {

    @Test
    public void needsResume_onlyWhenInteractiveAndSleeping() {
        assertTrue(ScreenStateReconciler.needsResume(true, true));    // the bug: screen on, we think we sleep
        assertFalse(ScreenStateReconciler.needsResume(true, false));  // already awake
        assertFalse(ScreenStateReconciler.needsResume(false, true));  // genuinely asleep
        assertFalse(ScreenStateReconciler.needsResume(false, false));
    }

    @Test
    public void tick_resumesWhenScreenOnButServiceSleeping() {
        AtomicInteger resumes = new AtomicInteger();
        ScreenStateReconciler r = new ScreenStateReconciler(() -> true, () -> true, resumes::incrementAndGet);

        r.tick();

        assertEquals("missed screen-on must be healed", 1, resumes.get());
    }

    @Test
    public void tick_noResumeWhenScreenOff() {
        AtomicInteger resumes = new AtomicInteger();
        ScreenStateReconciler r = new ScreenStateReconciler(() -> false, () -> true, resumes::incrementAndGet);

        r.tick();

        assertEquals("genuinely asleep → do not resume", 0, resumes.get());
    }

    @Test
    public void tick_noResumeWhenNotSleeping() {
        AtomicInteger resumes = new AtomicInteger();
        ScreenStateReconciler r = new ScreenStateReconciler(() -> true, () -> false, resumes::incrementAndGet);

        r.tick();

        assertEquals("already awake → nothing to heal", 0, resumes.get());
    }

    @Test
    public void tick_isIdempotent_onceResumeClearsSleeping() {
        // Model the real service: the resume flips the "sleeping" belief to false (onScreenOn sets
        // isSleeping=false), so subsequent ticks must not fire again while the screen stays on.
        AtomicBoolean sleeping = new AtomicBoolean(true);
        AtomicInteger resumes = new AtomicInteger();
        ScreenStateReconciler r = new ScreenStateReconciler(
                () -> true,
                sleeping::get,
                () -> { resumes.incrementAndGet(); sleeping.set(false); });

        r.tick();   // heals: fires once, clears sleeping
        r.tick();   // no longer sleeping → no-op
        r.tick();

        assertEquals("resume fires exactly once per missed edge", 1, resumes.get());
    }

    @Test
    public void tick_refiresAfterANewSleepCycle() {
        AtomicBoolean sleeping = new AtomicBoolean(true);
        AtomicInteger resumes = new AtomicInteger();
        ScreenStateReconciler r = new ScreenStateReconciler(
                () -> true,
                sleeping::get,
                () -> { resumes.incrementAndGet(); sleeping.set(false); });

        r.tick();               // heal #1
        sleeping.set(true);     // a new screen-off → sleep again, and a new SCREEN_ON is missed again
        r.tick();               // heal #2

        assertEquals(2, resumes.get());
    }
}
