package com.munitter.android.notification

/** No payload or count crosses this process-local foreground hint. */
internal object NotificationRefreshHints {
    private var owner: Any? = null
    private var callback: (() -> Unit)? = null

    @Synchronized
    fun attach(owner: Any, callback: () -> Unit) {
        this.owner = owner
        this.callback = callback
    }

    @Synchronized
    fun detach(owner: Any) {
        if (this.owner !== owner) return
        this.owner = null
        callback = null
    }

    fun requestForegroundRefresh(): Boolean {
        val current = synchronized(this) { callback } ?: return false
        current()
        return true
    }
}
