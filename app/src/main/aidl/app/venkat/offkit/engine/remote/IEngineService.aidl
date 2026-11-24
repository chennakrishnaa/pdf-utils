package app.venkat.offkit.engine.remote;

import android.os.Bundle;
import app.venkat.offkit.engine.remote.IEngineCallback;

/** The `:engine` process's PDF engine. Not exported; same-app callers only. See EngineService. */
interface IEngineService {
    /** Process id of the engine process (used by the client to kill a stuck engine). */
    int pid();

    /**
     * Starts request requestId (an EngineWire request bundle). Progress and exactly one result,
     * all tagged with requestId, are sent to callback, in order, from one worker thread.
     */
    oneway void run(long requestId, in Bundle request, IEngineCallback callback);

    /** Asks request requestId to stop at its next cancellation check. No-op for other ids. */
    oneway void cancel(long requestId);
}
