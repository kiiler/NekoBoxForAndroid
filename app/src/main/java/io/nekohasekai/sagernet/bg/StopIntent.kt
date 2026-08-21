package io.nekohasekai.sagernet.bg

/** Serializes stop requests while allowing the latest caller to decide whether to restart. */
internal class StopIntent {

    private val lock = Any()
    private var stopping = false
    private var restartAfterStop = false

    /** Returns true only for the caller that must start the cleanup operation. */
    fun request(restart: Boolean): Boolean = synchronized(lock) {
        restartAfterStop = restart
        if (stopping) {
            false
        } else {
            stopping = true
            true
        }
    }

    /** Updates an active cleanup. Returns false when the caller must start a new cleanup. */
    fun updateActive(restart: Boolean): Boolean = synchronized(lock) {
        if (!stopping) {
            false
        } else {
            restartAfterStop = restart
            true
        }
    }

    /** Completes the active stop while preventing a request from changing the chosen action. */
    fun complete(action: (restart: Boolean) -> Unit) = synchronized(lock) {
        check(stopping) { "No stop operation is active" }
        try {
            action(restartAfterStop)
        } finally {
            restartAfterStop = false
            stopping = false
        }
    }
}
