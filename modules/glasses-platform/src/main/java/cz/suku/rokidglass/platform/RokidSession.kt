package cz.suku.rokidglass.platform

import com.rokid.cxr.CXRServiceBridge
import com.rokid.cxr.Caps

object RokidSession {
    interface Listener {
        fun onConnectionChanged(connected: Boolean)
        fun onDisplayCommand(action: String, payload: String) = Unit
    }

    private val bridge = CXRServiceBridge()

    @Volatile
    private var connected = false

    @Volatile
    private var listener: Listener? = null

    init {
        bridge.setStatusListener(object : CXRServiceBridge.StatusListener {
            override fun onConnected(id: String?, name: String?, deviceType: Int) {
                connected = true
                listener?.onConnectionChanged(true)
            }

            override fun onDisconnected() {
                connected = false
                listener?.onConnectionChanged(false)
            }

            override fun onConnecting(id: String?, name: String?, deviceType: Int) = Unit
            override fun onARTCStatus(value: Float, available: Boolean) = Unit
            override fun onRokidAccountChanged(account: String?) = Unit
            override fun onAudioNoise(value: Float) = Unit
        })
        bridge.subscribe(RokidContract.DISPLAY_COMMAND) { _, caps, binary ->
            if (caps.size() < 1 || caps.at(0).type() != Caps.Value.TYPE_STRING) return@subscribe
            val action = caps.at(0).string
            val payload = binary?.toString(Charsets.UTF_8).orEmpty()
            listener?.onDisplayCommand(action, payload)
        }
    }

    fun attach(listener: Listener) {
        this.listener = listener
        listener.onConnectionChanged(connected)
    }

    fun detach(listener: Listener) {
        if (this.listener === listener) this.listener = null
    }

    fun sendEvent(event: String): Int = bridge.sendMessage(
        RokidContract.EVENT_COMMAND,
        Caps().apply { write(event) },
    )
}
