package com.bluey.app

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Executors

/** Finds the Mac app on the local network (NSD = Bonjour) and keeps a connection open. All callbacks run on the main thread. */
class MacLink(private val context: Context) {
    var connected by mutableStateOf(false); private set
    var macName by mutableStateOf<String?>(null); private set
    /** Every Mac running Bluey on this Wi-Fi, by its Bonjour name. */
    var macs by mutableStateOf(listOf<String>()); private set
    var currentMac by mutableStateOf<String?>(null); private set
    private val prefs = context.getSharedPreferences("bluey", Context.MODE_PRIVATE)
    var preferredMac by mutableStateOf(prefs.getString("preferredMac", null)); private set

    /** Commands from the computer, like "wake", "sleep" or "replying". */
    var onCommand: ((String, Packet) -> Unit)? = null
    var onFace: ((FaceState) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val found = linkedMapOf<String, NsdServiceInfo>()
    private var discovery: NsdManager.DiscoveryListener? = null
    private var multicast: WifiManager.MulticastLock? = null
    private var link: Connection? = null
    private var resolving = false
    private val waiting = mutableMapOf<String, (Packet?) -> Unit>()
    private val retry = Runnable { if (discovery == null) start() else connectIfNeeded() }

    fun start() {
        if (discovery != null) return
        multicast = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createMulticastLock("bluey").apply { setReferenceCounted(false); acquire() }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = main.post { restart() }.let {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) = main.post {
                found[info.serviceName] = info
                changed()
            }.let {}
            override fun onServiceLost(info: NsdServiceInfo) = main.post {
                found.remove(info.serviceName)
                changed()
            }.let {}
        }
        discovery = listener
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun changed() {
        macs = found.keys.sorted()
        // Move over when the picked Mac shows up while linked to a different one.
        val preferred = preferredMac
        if (preferred != null && currentMac != preferred && macs.contains(preferred)) link?.close()
        connectIfNeeded()
    }

    fun stop() {
        main.removeCallbacks(retry)
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discovery = null
        multicast?.release()
        found.clear()
        link?.close()
        link = null
        connected = false
    }

    private fun restart() {
        stop()
        scheduleRetry()
    }

    private fun scheduleRetry() {
        main.removeCallbacks(retry)
        main.postDelayed(retry, 1500)
    }

    /** Switches to another Mac and remembers the choice. */
    fun choose(name: String) {
        preferredMac = name
        prefs.edit().putString("preferredMac", name).apply()
        if (currentMac != name) link?.close()
        connectIfNeeded()
    }

    private fun connectIfNeeded() {
        if (link != null || resolving || found.isEmpty()) return
        val info = found[preferredMac] ?: found.values.first()
        currentMac = info.serviceName
        resolving = true
        @Suppress("DEPRECATION")
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = main.post {
                resolving = false
                scheduleRetry()
            }.let {}
            override fun onServiceResolved(info: NsdServiceInfo) = main.post {
                resolving = false
                val host = info.host
                if (host == null || link != null) { scheduleRetry(); return@post }
                open(host.hostAddress ?: return@post, info.port)
            }.let {}
        })
    }

    private fun open(host: String, port: Int) {
        val conn = Connection(host, port)
        link = conn
        conn.onReady = {
            if (conn === link) {
                connected = true
                conn.send(Packet(hello = deviceName))
            }
        }
        conn.onClosed = {
            if (conn === link) {
                connected = false
                val pending = waiting.values.toList()
                waiting.clear()
                pending.forEach { it(null) }
                macName = null
                link = null
                scheduleRetry()
            }
        }
        conn.onPacket = { packet ->
            if (conn === link) {
                packet.hello?.let { macName = it }
                packet.face?.let { onFace?.invoke(it) }
                val command = packet.command
                if (command != null) {
                    val done = packet.callID?.let { waiting.remove(it) }
                    if (done != null) done(packet) else onCommand?.invoke(command, packet)
                }
            }
        }
        conn.start()
    }

    fun send(packet: Packet) {
        link?.send(packet)
    }

    /** Sends a request to the Mac and calls back with its reply (null if the Mac went away). */
    fun request(packet: Packet, done: (Packet?) -> Unit) {
        val link = link
        if (link == null || !connected) { done(null); return }
        val id = UUID.randomUUID().toString().uppercase()
        waiting[id] = done
        link.send(packet.copy(callID = id))
    }

    private val deviceName: String
        get() = Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
            ?: "${Build.MANUFACTURER} ${Build.MODEL}"

    /** Newline JSON over one TCP socket. Reads on its own thread, writes on another; callbacks on main. */
    private inner class Connection(val host: String, val port: Int) {
        var onReady: () -> Unit = {}
        var onClosed: () -> Unit = {}
        var onPacket: (Packet) -> Unit = {}
        private val socket = Socket()
        private val writer = Executors.newSingleThreadExecutor()
        private var out: OutputStreamWriter? = null
        @Volatile private var closed = false

        fun start() = Thread {
            try {
                socket.tcpNoDelay = true
                socket.keepAlive = true
                socket.connect(InetSocketAddress(host, port), 5000)
                out = OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)
                main.post(onReady)
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8), 1 shl 16)
                while (true) {
                    val line = reader.readLine() ?: break
                    Packet.decode(line)?.let { p -> main.post { onPacket(p) } }
                }
            } catch (_: Exception) {
            }
            close()
        }.start()

        fun send(packet: Packet) {
            if (closed) return
            val line = packet.encode() + "\n"
            writer.execute {
                try {
                    out?.apply { write(line); flush() }
                } catch (_: Exception) {
                    close()
                }
            }
        }

        fun close() {
            if (closed) return
            closed = true
            runCatching { socket.close() }
            writer.shutdown()
            main.post(onClosed)
        }
    }
}
