package su.grinev.myvpn.state;

import android.os.Handler;
import android.os.Looper;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import su.grinev.myvpn.DebugLog;
import su.grinev.myvpn.State;

/**
 * Manages VPN state and notifies observers.
 * Single Responsibility: State management and observer notification.
 *
 * Thread-safe implementation using:
 * - ConcurrentHashMap for observer storage with unique IDs
 * - Handler for main thread callbacks
 */
public class VpnStateManager {
    private static final VpnStateManager INSTANCE = new VpnStateManager();

    private final Object stateLock = new Object();
    private State currentState = State.DISCONNECTED;

    // Use ConcurrentHashMap with unique IDs instead of WeakReference
    private final AtomicInteger listenerIdGenerator = new AtomicInteger(0);
    private final Map<Integer, Consumer<State>> stateListeners = new ConcurrentHashMap<>();

    // Map to track listener -> id for unregistration
    private final Map<Consumer<State>, Integer> listenerIds = new ConcurrentHashMap<>();

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private VpnStateManager() {
        // Singleton
    }

    public static VpnStateManager getInstance() {
        return INSTANCE;
    }

    /**
     * Update the current state and notify all observers.
     * Thread-safe - can be called from any thread.
     */
    public void setState(State state) {
        State oldState;
        synchronized (stateLock) {
            oldState = currentState;
            currentState = state;
        }

        if (oldState != state) {
            DebugLog.log("State: " + oldState + " -> " + state);
            notifyListeners(state);
        }
    }

    /**
     * Get the current state.
     * Thread-safe.
     */
    public State getState() {
        synchronized (stateLock) {
            return currentState;
        }
    }

    /**
     * Register a state listener.
     * The listener will be called on the main thread.
     * Immediately notifies the listener with the current state.
     *
     * @param listener The state change listener
     */
    public void observeState(Consumer<State> listener) {
        if (listener == null) {
            return;
        }

        // Remove any existing registration for this listener
        unobserveState(listener);

        // Generate a unique ID for this listener
        int id = listenerIdGenerator.incrementAndGet();
        stateListeners.put(id, listener);
        listenerIds.put(listener, id);

        // Immediately notify with current state on main thread
        State state;
        synchronized (stateLock) {
            state = currentState;
        }

        mainHandler.post(() -> {
            // Check if listener is still registered before notifying
            if (listenerIds.containsKey(listener)) {
                listener.accept(state);
            }
        });
    }

    /**
     * Unregister a specific state listener.
     *
     * @param listener The listener to remove
     */
    public void unobserveState(Consumer<State> listener) {
        if (listener == null) {
            return;
        }

        Integer id = listenerIds.remove(listener);
        if (id != null) {
            stateListeners.remove(id);
        }
    }

    /**
     * Remove all state listeners.
     */
    public void clearAllObservers() {
        stateListeners.clear();
        listenerIds.clear();
    }

    /**
     * Reset state to DISCONNECTED and notify listeners.
     * Called when service is destroyed.
     */
    public void reset() {
        setState(State.DISCONNECTED);
    }

    private void notifyListeners(State state) {
        mainHandler.post(() -> {
            for (Consumer<State> listener : stateListeners.values()) {
                try {
                    listener.accept(state);
                } catch (Exception e) {
                    DebugLog.log("Error notifying listener: " + e.getMessage());
                }
            }
        });
    }
}
