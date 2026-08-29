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
        // If the Node TCP server is already running (from a previous service
        // invocation in the same process lifetime) but the channel was reset
        // by onDestroy(), skip the node launch but still wire the TCP channel.
        val nodeAlreadyRunning = isNodeStarted

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
        val isUpToDate = versionMarker.exists() &&
                versionMarker.readText().trim() == NODEJS_ASSETS_VERSION

        if (!nodeModulesDir.exists()) {
            Log.d(TAG, "First boot: unpacking nodejs-project assets...")
        }

        // Full refresh runs on first boot and whenever NODEJS_ASSETS_VERSION is
        // bumped. The old tree is deleted first (not just overwritten) so files
        // removed from the APK — e.g. pruned node_modules junk — disappear from
        // internal storage too instead of accumulating forever.
        if (!isUpToDate) {
            Log.d(TAG, "Assets changed (want v$NODEJS_ASSETS_VERSION) — wiping + refreshing all JS/JSON...")
            destDir.deleteRecursively()
            copyAssetsToFilesIfNeeded("nodejs-project")
            versionMarker.writeText(NODEJS_ASSETS_VERSION)
        } else {
            Log.d(TAG, "nodejs-project up to date (v$NODEJS_ASSETS_VERSION)")
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

        if (!nodeAlreadyRunning) {
            synchronized(SchedulerService::class.java) {
                if (!isNodeStarted) {
                    isNodeStarted = true
                    Thread {
                        try {
                            startNodeWithArguments(arrayOf("node", entryPoint), cacheDir.absolutePath)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error starting node: ${e.message}")
                            isNodeStarted = false  // allow retry on next service start
                        }
                    }.start()
                    Log.d(TAG, "Node runtime started, entry=$entryPoint")
                }
            }
        } else {
            Log.d(TAG, "Node process already running — skipping launch, will reconnect TCP channel")
        }

        // Step 3: Wire NodeBridge channel over TCP.
        // Wait for node server to boot and start listening
        var socket: Socket? = null
        for (i in 0..60) {
            try {
                socket = Socket("127.0.0.1", 3000)
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
        // Reset isNodeStarted so the NEXT service invocation can relaunch the Node TCP
        // server. This is the critical fix for the Day-2 crash: without this reset,
        // isNodeStarted stays true forever, setupNodeRuntime() sees the running flag and
        // skips the launch, but the TCP server is dead (its thread exited with the
        // previous socket), so channel init times out and the app hangs/crashes.
        isNodeStarted = false
        serviceScope.cancel()
        Log.d(TAG, "SchedulerService destroyed — isNodeStarted reset for next invocation")
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

        /**
         * Bump whenever ANY file under assets/nodejs-project changes — especially
         * patches inside node_modules (Baileys tmpdir fix, crypto.js, etc.).
         * Gates the full JS/JSON refresh that ships those files to the device.
         * History: v2 = Baileys tmpdir patch (messages-media.js, business.js).
         *          v3 = single-session socket lifecycle (endCurrentSocket +
         *               stale-event guard + no-reconnect-on-intentional-close)
         *               + sender.js per-part send tracking (no duplicate voice
         *               notes on retry of voice+doc+text messages).
         *          v8 = size optimization release: stripped libnode.so, ABI
         *               splits, pruned node_modules (host sharp binaries,
         *               @types/node, test/docs junk). Full wipe+re-extract
         *               removes those files from existing installs.
         *          v9 = terser-minified Baileys/WAProto (-6 MB on device),
         *               engine runtime home moved to cacheDir.
         *          v10 = atomic-auth.js wired in (§6.1 fix): temp+rename session
         *                writes + corrupt-creds detection to prevent silent crash
         *                loop on torn session files. Day-2 crash fix: isNodeStarted
         *                reset in onDestroy(), startForegroundService removed from
         *                MainActivity.onCreate (§2.3 connect-on-demand).
         */
        private const val NODEJS_ASSETS_VERSION = "10"

        @Volatile
        private var isNodeStarted = false

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
