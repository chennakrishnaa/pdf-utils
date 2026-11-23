package app.venkat.offkit.engine

/**
 * Runs engine operations on a dedicated thread with a large explicit stack (SA-11, defence in
 * depth: Compress and Add images no longer use the recursive `garbage` save on inputs, and Merge's
 * graft depth is bounded by [ObjectGraph.checkPages] and by MuPDF's own `fz_try` stack).
 *
 * Any remaining MuPDF recursion is native and uses the calling thread's stack; the engine is called on
 * whatever thread the caller uses (in tests a JUnit thread, in the app the single engine thread),
 * whose native stack may be small. [run] executes the block on a new thread created with
 * `Thread(group, runnable, name, STACK_BYTES)` and waits for it. On the host JVM (HotSpot) the
 * stackSize argument is honoured; ART documents it as platform-dependent and, in current releases,
 * passes it to `pthread_attr_setstacksize` for the new thread (not verified on a device). Stack
 * pages are reserved virtual memory and only touched as used.
 *
 * Each call gets a fresh thread, so the MuPDF thread-local context (dropped by the binding at
 * thread exit) and every MuPDF object of the operation live and die on that thread. Exceptions
 * thrown by [block] are rethrown to the caller unchanged; cancellation stays cooperative through
 * [JobControl]. If the waiting thread is interrupted it keeps waiting (the operation owns native
 * resources and must finish) and its interrupt flag is restored afterwards.
 */
internal object EngineStack {
    /** Stack size of the operation thread: 128 MB of reserved address space. */
    const val STACK_BYTES = 128L * 1024 * 1024

    fun <T> run(block: () -> T): T {
        var result: Result<T>? = null
        val t = Thread(null, { result = runCatching(block) }, "offkit-engine-op", STACK_BYTES)
        t.start()
        var interrupted = false
        while (true) {
            try {
                t.join()
                break
            } catch (e: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
        return result!!.getOrThrow()
    }
}
