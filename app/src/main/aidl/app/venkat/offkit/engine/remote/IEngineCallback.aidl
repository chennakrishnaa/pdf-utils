package app.venkat.offkit.engine.remote;

import android.os.Bundle;

/**
 * Per-request callback from the engine process. One-way calls on one binder object are delivered
 * in the order they were sent, so every progress event arrives before the result.
 */
oneway interface IEngineCallback {
    void progress(long requestId, int done, int total);

    /** The final result: an EngineWire result or estimate bundle. Sent exactly once. */
    void result(long requestId, in Bundle result);
}
