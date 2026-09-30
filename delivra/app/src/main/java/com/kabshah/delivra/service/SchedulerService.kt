package com.kabshah.delivra.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.kabshah.delivra.MainActivity
import com.kabshah.delivra.bridge.NodeBridge
import com.kabshah.delivra.bridge.NodeJsMobile
import com.kabshah.delivra.scheduling.Constants
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.*
import java.io.File
import java.net.Socket
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import kotlinx.coroutines.flow.first
import java.net.InetSocketAddress

/**
 * Foreground Service that:
 *  1. Copies nodejs-project assets to internal storage (required — Node.js can't
 *     read from the APK asset zip directly; must be real filesystem files) (§8)
 *  2. Starts the Node.js Engine using a C++ native bridge.
 *  3. Connects NodeBridge over a local TCP socket to Node.
 *  4. connect()s Baileys, sends all due messages as a batch, then disconnect()s
 *  5. Stops itself — START_NOT_STICKY, no idle socket/service (§2.3, §6.6)
 */
@AndroidEntryPoint
class SchedulerService : Service() {



    @Inject lateinit var nodeBridge: NodeBridge

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        startForeground(NOTIFICATION_ID, buildForegroundNotification())
        Log.d(TAG, "SchedulerService created — setting up Node runtime")
        serviceScope.launch(Dispatchers.IO) {
            setupNodeRuntime()
        }
    }

    private suspend fun setupNodeRuntime() {
        if (nodeBridge.isChannelInitialized) {
            Log.d(TAG, "Node channel already initialized — skipping setup.")
            return
        }

        // Probe whether the Node TCP server is ACTUALLY still running — don't rely
        // on the Kotlin flag, because the native Node thread outlives the service.
        // Bug 1 fix: `isNodeStarted` was reset in onDestroy(), but the Node process
        // was never killed, so Day-2 re-launch hit EADDRINUSE on port 3000 and crashed.
        val probeResult = probeNodePort()
        val nodeAlreadyRunning = probeResult.first
        activeNodePort = probeResult.second
        if (nodeAlreadyRunning) {
            Log.d(TAG, "TCP probe: Node is still alive on port $activeNodePort — skipping re-launch")
            isNodeStarted = true  // re-sync the flag to reality
        } else if (isNodeStarted) {
            // Flag said running but probe failed — Node died unexpectedly; reset flag
            // so we re-launch below.
            Log.w(TAG, "isNodeStarted=true but no port responding — Node crashed, will re-launch")
            isNodeStarted = false
            activeNodePort = DEFAULT_NODE_PORT
        }

        val destDir = File(cacheDir, "nodejs-project")

        // Size pass (v1.1): the engine's runtime copy moved from filesDir to
        // cacheDir — cache isn't counted toward the app's storage quota shown
        // in Settings and is reclaimable by the OS under pressure. Safety:
        // the version marker lives inside destDir, so if the OS ever wipes
        // the cache the very next service start re-extracts automatically.
        // The linked WhatsApp session (filesDir/wa_session) is NOT touched.
        // One-time migration: drop the pre-1.1 copy from filesDir.
        File(filesDir, "nodejs-project").deleteRecursively()

        val nodeModulesDir = File(destDir, "node_modules")
        val versionMarker = File(destDir, ".delivra-assets-version")
        val markerSaysUpToDate = versionMarker.exists() &&
                try { versionMarker.readText().trim() } catch (_: Exception) { "" } == NODEJS_ASSETS_VERSION

        // ── Sentinel integrity check (Day-2 crash fix v1.4) ──────────────────
        // Android CAN and DOES evict individual files from cacheDir at any time
        // to free storage. The version marker may survive while critical modules
        // inside node_modules get deleted. If we trust the marker alone, Node
        // starts on a broken file tree and crashes at the native level (SIGSEGV),
        // killing the entire app process — this was the remaining Day-2 crash.
        // Check a set of sentinel files that MUST exist for Node to boot.
        val sentinelsIntact = SENTINEL_FILES.all { relPath ->
            File(destDir, relPath).exists()
        }
        val isUpToDate = markerSaysUpToDate && sentinelsIntact

        if (!sentinelsIntact && markerSaysUpToDate) {
            Log.w(TAG, "Version marker OK but sentinel files MISSING — Android cache eviction detected! Forcing full re-extract.")
        }

        if (!nodeModulesDir.exists()) {
            Log.d(TAG, "First boot: unpacking nodejs-project assets...")
        }

        // Full refresh runs on first boot, whenever NODEJS_ASSETS_VERSION is
        // bumped, OR when cache eviction corrupted the tree. The old tree is
        // deleted first so stale files don't accumulate.
        if (!isUpToDate) {
            Log.d(TAG, "Assets changed or corrupted (want v$NODEJS_ASSETS_VERSION, sentinels=$sentinelsIntact) — wiping + refreshing...")
            destDir.deleteRecursively()
            copyAssetsToFilesIfNeeded("nodejs-project")
            versionMarker.writeText(NODEJS_ASSETS_VERSION)
        } else {
            Log.d(TAG, "nodejs-project up to date (v$NODEJS_ASSETS_VERSION, sentinels OK)")
        }

        // Entry scripts are cheap to re-copy every startup, so quick JS fixes
        // ship even if the version constant was forgotten.
        copySingleAsset("nodejs-project/index.js", File(destDir, "index.js"))
        copySingleAsset("nodejs-project/polyfill.js", File(destDir, "polyfill.js"))
        copySingleAsset("nodejs-project/whatsapp.js", File(destDir, "whatsapp.js"))
        copySingleAsset("nodejs-project/sender.js", File(destDir, "sender.js"))
        copySingleAsset("nodejs-project/atomic-auth.js", File(destDir, "atomic-auth.js"))
        copySingleAsset("nodejs-project/package.json", File(destDir, "package.json"))

        val entryPoint = File(destDir, "index.js").absolutePath

        // Final safety check: verify the entry point actually exists before handing
        // it to the native Node engine. A missing file here means cache eviction
        // happened BETWEEN the sentinel check and this line (very unlikely but
        // theoretically possible). A Java-level exception here is recoverable;
        // a native Node crash on a missing file is not.
        if (!File(entryPoint).exists()) {
            Log.e(TAG, "Entry point $entryPoint does not exist after extraction! Aborting Node startup.")
            return
        }

        if (!nodeAlreadyRunning) {
            // Find an available port before starting Node
            val port = findAvailablePort()
            activeNodePort = port
            Log.d(TAG, "Selected port $port for Node TCP server")

            synchronized(SchedulerService::class.java) {
                if (!isNodeStarted) {
                    isNodeStarted = true
                    Thread {
                        try {
                            // Pass port via env so index.js picks it up
                            startNodeWithArguments(
                                arrayOf("node", entryPoint, "--port=$port"),
                                cacheDir.absolutePath
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Error starting node: ${e.message}")
                            isNodeStarted = false  // allow retry on next service start
                        }
                    }.start()
                    Log.d(TAG, "Node runtime started, entry=$entryPoint, port=$port")
                }
            }
        } else {
            Log.d(TAG, "Node process already alive on port $activeNodePort — reconnecting TCP channel only")
        }

        // Step 3: Wire NodeBridge channel over TCP.
        // Wait for node server to boot and start listening
        val port = activeNodePort
        var socket: Socket? = null
        for (i in 0..60) {
            try {
                socket = Socket("127.0.0.1", port)
                break
            } catch (e: Exception) {
                delay(500)
            }
        }

        if (socket != null) {
            val input = BufferedReader(InputStreamReader(socket.getInputStream()))
            val output = PrintWriter(socket.getOutputStream(), true)
            
            val channel = object : NodeJsMobile.Channel {
                override fun sendMessage(message: String) {
                    serviceScope.launch(Dispatchers.IO) {
                        try {
                            output.print(message + "\n")
                            output.flush()
                        } catch(e: Exception) {
                            Log.e(TAG, "TCP send Error: ${e.message}")
                        }
                    }
                }
                override fun setMessageListener(listener: (String) -> Unit) {
                    // Background thread reading from socket
                    serviceScope.launch(Dispatchers.IO) {
                        try {
                            var line: String?
                            while (input.readLine().also { line = it } != null) {
                                line?.let { listener(it) }
                            }
                            Log.e(TAG, "Node TCP socket closed (EOF). Node process likely crashed.")
                            nodeBridge.handleTcpDisconnection()
                        } catch(e: Exception) {
                            Log.e(TAG, "TCP read Error: ${e.message}")
                            nodeBridge.handleTcpDisconnection()
                        }
                    }
                }
            }
            nodeBridge.initChannel(channel)
            Log.d(TAG, "TCP Channel Connected!")
        } else {
            Log.e(TAG, "Failed to connect to Node TCP server!")
        }
    }

    private fun copySingleAsset(srcPath: String, destFile: File) {
        try {
            destFile.parentFile?.mkdirs()
            assets.open(srcPath).use { input ->
                destFile.outputStream().use { out -> input.copyTo(out) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy single asset $srcPath: ${e.message}")
        }
    }

    /**
     * Recursively copies assets/[srcDir] into cacheDir/[srcDir] (the engine's
     * runtime home since the v1.1 size pass — see setupNodeRuntime), skipping
     * files that already exist with the same size (cheap dirty-check, avoids
     * full hash on every startup while still catching APK updates that change
     * a file).
     */
    private fun copyAssetsToFilesIfNeeded(srcDir: String) {
        val destDir = File(cacheDir, srcDir)
        if (!destDir.exists()) destDir.mkdirs()
        assets.list(srcDir)?.forEach { name ->
            val srcPath = "$srcDir/$name"
            val destFile = File(destDir, name)
            val subList = assets.list(srcPath)
            if (!subList.isNullOrEmpty()) {
                // It's a sub-directory — recurse
                copyAssetsToFilesIfNeeded(srcPath)
            } else {
                // Always overwrite JS/JSON files so APK updates take effect.
                // Only skip binary files (node_modules native .node/.so) that never change.
                val alwaysOverwrite = name.endsWith(".js") || name.endsWith(".json") || name.endsWith(".mjs")
                try {
                    assets.open(srcPath).use { input ->
                        if (alwaysOverwrite || !destFile.exists() || destFile.length() == 0L) {
                            destFile.outputStream().use { out -> input.copyTo(out) }
                            Log.d(TAG, "Copied asset $srcPath → ${destFile.absolutePath}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to copy asset $srcPath: ${e.message}")
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val messageIds = intent?.getStringArrayListExtra(EXTRA_MESSAGE_IDS) ?: emptyList<String>()
        Log.d(TAG, "onStartCommand: dispatching ${messageIds.size} message(s)")

        val keepAlive = intent?.getBooleanExtra("keep_alive", false) ?: false

        serviceScope.launch {
            var wakeLock: android.os.PowerManager.WakeLock? = null
            try {
                // Wait for bridge channel to be initialized by setupNodeRuntime
                val deadline = System.currentTimeMillis() + 30_000L
                while (!nodeBridge.isChannelInitialized && System.currentTimeMillis() < deadline) {
                    delay(200)
                }

                if (!nodeBridge.isChannelInitialized) {
                    throw IllegalStateException("Timed out waiting for Node TCP server to initialize channel")
                }

                if (keepAlive || messageIds.isEmpty()) {
                    Log.d(TAG, "onStartCommand: keep_alive mode or no messages to send, leaving runtime up for UI.")
                    return@launch
                }

                // Actual dispatch ahead — hold the CPU through Node-boot/Baileys-
                // connect/send. Acquired ONLY here (never for idle warm-ups) and
                // hard-capped, so normal app usage draws zero extra battery.
                val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "Delivra:dispatch").apply {
                    setReferenceCounted(false)
                    acquire(DISPATCH_WAKELOCK_TIMEOUT_MS)
                }

                // Connect once — Baileys will auto-restore the saved session (§2.3)
                nodeBridge.connect()

                // Wait until Baileys confirms CONNECTED before sending
                waitForConnected()

                // Send all due messages in one connection session (batch, §2.3)
                messageIds.forEach { id ->
                    nodeBridge.sendMessage(id)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Dispatch error: ${e.message}", e)
            } finally {
                try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
                if (!keepAlive) {
                    nodeBridge.disconnect()
                    stopSelf(startId)
                }
            }
        }

        return START_NOT_STICKY  // No auto-restart — AlarmManager handles next wakeup (§6.6)
    }

    /** Suspend until connection reaches CONNECTED, or throw on terminal state / timeout. */
    private suspend fun waitForConnected() {
        // Bug 4 fix: StateFlow.first{} only catches the NEXT emission after subscription.
        // If CONNECTED was already emitted between connect() and this call (possible on
        // fast networks / Day-2 session restore), we'd hang for the full 60s timeout.
        // Pre-check the current replay value to close this race window.
        when (nodeBridge.connectionState.value) {
            NodeBridge.ConnectionState.CONNECTED -> return  // already live — nothing to wait for
            NodeBridge.ConnectionState.LOGGED_OUT,
            NodeBridge.ConnectionState.ERROR ->
                throw IllegalStateException("WhatsApp session invalid — re-link required")
            else -> { /* fall through to flow-based wait below */ }
        }
        withTimeout(60_000L) {  // 60s — gives Baileys enough time for session restore on slow networks
            nodeBridge.connectionState.first { state ->
                when (state) {
                    NodeBridge.ConnectionState.CONNECTED -> true
                    NodeBridge.ConnectionState.LOGGED_OUT,
                    NodeBridge.ConnectionState.ERROR ->
                        throw IllegalStateException("WhatsApp session invalid — re-link required")
                    else -> false
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Reset the bridge channel so the next SchedulerService instance re-establishes
        // the TCP socket fresh. Without this, isChannelInitialized stays true but the
        // read coroutine (tied to serviceScope) is already dead → connection events never arrive.
        nodeBridge.resetChannel()
        // NOTE: We do NOT reset isNodeStarted here. The native Node.js thread (started
        // via startNodeWithArguments) outlives this service instance — it keeps running
        // after onDestroy(). Resetting the flag here was the original Day-2 crash cause:
        // on next service start, the code thought Node was dead and tried to start it
        // again, hitting EADDRINUSE on port 3000. Liveness is now determined by a live
        // TCP probe in setupNodeRuntime() instead of this flag.
        serviceScope.cancel()
        Log.d(TAG, "SchedulerService destroyed — bridge channel reset, Node still alive")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Notifications ──────────────────────────────────────────────────────────
    private fun createNotificationChannels() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        NotificationChannel(Constants.CHANNEL_FOREGROUND, "Delivra — Active Send", NotificationManager.IMPORTANCE_LOW).also {
            it.description = "Shown briefly while a scheduled message is being sent"
            it.setSound(null, null)
            it.enableVibration(false)
            nm.createNotificationChannel(it)
        }
        NotificationChannel(Constants.CHANNEL_FAILURES, "Delivra — Send Failures", NotificationManager.IMPORTANCE_DEFAULT).also {
            it.description = "Notifies when a scheduled message fails or needs your review"
            nm.createNotificationChannel(it)
        }
    }

    private fun buildForegroundNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, Constants.CHANNEL_FOREGROUND)
            .setContentTitle("Delivra")
            .setContentText("Sending scheduled message…")
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "SchedulerService"
        private const val NOTIFICATION_ID = 1001
        private const val EXTRA_MESSAGE_IDS = "message_ids"

        /** Hard cap for the dispatch wakelock: Node boot + connect + batch send. */
        private const val DISPATCH_WAKELOCK_TIMEOUT_MS = 3 * 60 * 1000L

        /** Default TCP port. Falls back to 3001..3009 if busy. */
        private const val DEFAULT_NODE_PORT = 3000
        private const val MAX_PORT_SCAN = 10

        /**
         * Bump whenever ANY file under assets/nodejs-project changes — especially
         * patches inside node_modules (Baileys tmpdir fix, crypto.js, etc.).
         * Gates the full JS/JSON refresh that ships those files to the device.
         * History: v2 = Baileys tmpdir patch (messages-media.js, business.js).
         *          v3 = single-session socket lifecycle.
         *          v8 = size optimization release.
         *          v9 = terser-minified Baileys/WAProto, cacheDir home.
         *          v10 = atomic-auth.js wired in.
         *          v11 = Day-2 crash fix: TCP port probe, intentionalClose guard.
         *          v12 = Day-2 crash COMPLETE fix: sentinel integrity check against
         *                partial cache eviction, dynamic port selection to avoid
         *                EADDRINUSE from TIME_WAIT sockets, server error handler in
         *                index.js, entry-point existence check before native start.
         *          v13 = Media send fix: fetchAgent for uploads, better error
         *                classification, ENOENT/zero_byte non-retryable.
         *          v14 = Voice note streaming fix (url: instead of readFileSync),
         *                Day-2 stale socket cleanup in whatsapp.js, rich rose UI.
         */
        private const val NODEJS_ASSETS_VERSION = "14"

        /**
         * Sentinel files that MUST exist for Node to boot successfully.
         * If any sentinel is missing (cache eviction!), we force full re-extract
         * even if the version marker file survives. These represent the minimum
         * set of files Node needs: the entry point, polyfill, whatsapp engine,
         * and critical node_modules dependencies.
         */
        private val SENTINEL_FILES = listOf(
            "index.js",
            "polyfill.js",
            "whatsapp.js",
            "sender.js",
            "atomic-auth.js",
            "node_modules/@whiskeysockets/baileys/lib/index.js",
            "node_modules/@hapi/boom/lib/index.js",
            "node_modules/pino/pino.js"
        )

        @Volatile
        private var isNodeStarted = false

        /** The port the currently-running Node TCP server is listening on. */
        @Volatile
        private var activeNodePort = DEFAULT_NODE_PORT

        /**
         * Probes ports [3000..3009] for a running Node TCP server.
         * Returns (isAlive, port). If no port responds, returns (false, DEFAULT_NODE_PORT).
         */
        private fun probeNodePort(): Pair<Boolean, Int> {
            for (port in DEFAULT_NODE_PORT until DEFAULT_NODE_PORT + MAX_PORT_SCAN) {
                try {
                    Socket().use { s ->
                        s.connect(InetSocketAddress("127.0.0.1", port), 300)
                    }
                    Log.d(TAG, "TCP probe: Node alive on port $port")
                    return true to port
                } catch (_: Exception) { /* port not listening, try next */ }
            }
            return false to DEFAULT_NODE_PORT
        }

        /**
         * Finds an available port starting from DEFAULT_NODE_PORT.
         * Avoids EADDRINUSE from TIME_WAIT sockets left over after force-stop.
         */
        private fun findAvailablePort(): Int {
            for (port in DEFAULT_NODE_PORT until DEFAULT_NODE_PORT + MAX_PORT_SCAN) {
                try {
                    // Try binding briefly to confirm port is free
                    java.net.ServerSocket().use { ss ->
                        ss.reuseAddress = true
                        ss.bind(InetSocketAddress("127.0.0.1", port))
                    }
                    return port
                } catch (_: Exception) { /* port busy, try next */ }
            }
            // Fallback: let OS pick (very unlikely to reach here)
            return DEFAULT_NODE_PORT
        }

        init {
            try {
                System.loadLibrary("node")
                System.loadLibrary("native-lib")
                Log.d(TAG, "Native libraries loaded successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load native libraries", e)
            }
        }
        
        @JvmStatic
        external fun startNodeWithArguments(arguments: Array<String>, tmpDir: String): Int

        fun dispatchDueMessages(context: Context, messageIds: List<String>) {
            val intent = Intent(context, SchedulerService::class.java).apply {
                putStringArrayListExtra(EXTRA_MESSAGE_IDS, ArrayList(messageIds))
            }
            context.startForegroundService(intent)
        }
    }
}
