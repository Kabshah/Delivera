package com.kabshah.delivra.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
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
    @Inject lateinit var repository: com.kabshah.delivra.data.ScheduleRepository

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        // Android 14+ requires explicitly specifying the foreground service type.
        // Use ServiceCompat.startForeground so older Android versions still work.
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildForegroundNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            else
                0
        )
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

        val destDir = File(filesDir, "nodejs-project")

        // v20 — the engine tree moved BACK to filesDir, reversing the v1.1
        // size pass. cacheDir is subject to random PER-FILE eviction by
        // Samsung Device Care / storage pressure, live-confirmed twice
        // (2026-11-13 and 2026-11-15): Baileys' lib/Socket/index.js vanished
        // while the version marker AND all sentinels survived, Node's ESM
        // import graph failed at bootstrap and the whole app process died
        // with it. filesDir is not touched by cache cleaners; at ~20MB the
        // quota impact is negligible. Only the TMPDIR handed to the native
        // engine stays in cacheDir — temp media-enc files are short-lived
        // and belong in reclaimable storage. The linked WhatsApp session
        // (filesDir/wa_session, passed over TCP as an absolute path) was
        // never part of this tree and is NOT touched.
        // One-time migration: drop the evictable cacheDir copy.
        File(cacheDir, "nodejs-project").deleteRecursively()

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

        // ── .boot-ok integrity marker (v20, pairs with crash-guard.js) ──────────
        // Sentinels are a fixed list — they can never cover every file in the
        // import graph (live case: lib/Socket/index.js evicted, sentinels OK).
        // crash-guard.js invalidates .boot-ok at every Node start and index.js
        // re-writes it only after the ENTIRE ESM import graph executed. A
        // missing marker = the last boot (or this one's predecessor) hit a
        // file the sentinel list doesn't know about. Trust it as a third gate.
        val bootOkIntact = File(destDir, ".boot-ok").exists()
        val isUpToDate = markerSaysUpToDate && sentinelsIntact && bootOkIntact

        if (!sentinelsIntact && markerSaysUpToDate) {
            Log.w(TAG, "Version marker OK but sentinel files MISSING — Android cache eviction detected! Forcing full re-extract.")
        }
        if (!bootOkIntact && markerSaysUpToDate && sentinelsIntact) {
            Log.w(TAG, "Version marker + sentinels OK but .boot-ok MISSING — last Node boot failed inside the import graph. Forcing full re-extract.")
        }

        if (!nodeModulesDir.exists()) {
            Log.d(TAG, "First boot: unpacking nodejs-project assets...")
        }

        // Full refresh runs on first boot, whenever NODEJS_ASSETS_VERSION is
        // bumped, OR when eviction/corruption is detected (sentinels, .boot-ok).
        // The old tree is deleted first so stale files don't accumulate.
        // Never refresh while the Node process is live — version bumps only
        // land via APK update (= new process), so a stale verdict beside a
        // live runtime is a bookkeeping anomaly, not a real refresh request.
        if (!nodeAlreadyRunning && !isUpToDate) {
            Log.d(TAG, "Assets changed or corrupted (want v$NODEJS_ASSETS_VERSION, sentinels=$sentinelsIntact, bootOk=$bootOkIntact) — wiping + refreshing...")
            destDir.deleteRecursively()
            copyAssetsToFilesIfNeeded("nodejs-project")
            versionMarker.writeText(NODEJS_ASSETS_VERSION)
        } else if (nodeAlreadyRunning && !isUpToDate) {
            Log.w(TAG, "Tree verdict stale but Node is live — skipping refresh to avoid pulling files out from under it")
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
        copySingleAsset("nodejs-project/crash-guard.js", File(destDir, "crash-guard.js"))
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
                            // Port via CLI arg (--port=), TMPDIR via the native arg —
                            // deliberately still cacheDir: the ENGINE tree moved to
                            // filesDir in v20, but media-enc temp files are
                            // short-lived and belong in reclaimable storage. Nothing
                            // evictable under cacheDir anymore — only attach_* and
                            // in-flight tmp files.
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
                    // Capture the generation at listener-registration time (inside
                    // initChannel). If this reader later outlives its channel —
                    // serviceScope.cancel() cannot interrupt a blocking readLine(),
                    // so the coroutine stays blocked until the socket dies — its
                    // EOF must be ignored, or it poisons the next dispatch with a
                    // bogus connection ERROR (see NodeBridge.handleTcpDisconnection).
                    val readerGeneration = nodeBridge.currentChannelGeneration()
                    // Background thread reading from socket
                    serviceScope.launch(Dispatchers.IO) {
                        try {
                            var line: String?
                            while (input.readLine().also { line = it } != null) {
                                line?.let { listener(it) }
                            }
                            Log.e(TAG, "Node TCP socket closed (EOF). Node process likely crashed.")
                            nodeBridge.handleTcpDisconnection(readerGeneration)
                        } catch(e: Exception) {
                            Log.e(TAG, "TCP read Error: ${e.message}")
                            nodeBridge.handleTcpDisconnection(readerGeneration)
                        }
                    }
                }
                override fun close() {
                    // Unblocks the reader's blocking readLine() immediately.
                    try { socket.close() } catch (_: Exception) { /* best effort */ }
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
     * Recursively copies assets/[srcDir] into filesDir/[srcDir] (the engine's
     * runtime home since v20 — see setupNodeRuntime for why cacheDir was
     * abandoned), skipping files that already exist with the same size
     * (cheap dirty-check, avoids full hash on every startup while still
     * catching APK updates that change a file).
     */
    private fun copyAssetsToFilesIfNeeded(srcDir: String) {
        val destDir = File(filesDir, srcDir)
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

    // ── Dispatch lifecycle refcount ──────────────────────────────────────────
    // Two dispatches can race into the same service instance (periodic backstop
    // tick + alarm-triggered once, 2026-11-15 19:13 live case: both ran
    // concurrently). The DB claim guard stops a double-SEND, but the LOSING
    // coroutine's finally used to disconnect() + stopSelf() unconditionally —
    // destroying the service and cancelling the WINNER's job mid-send
    // ("Dispatch error: Job was cancelled"), leaving the row stuck in SENDING
    // forever. Count active dispatch coroutines; only the LAST one out may
    // tear the runtime down.
    private val activeDispatchCount = java.util.concurrent.atomic.AtomicInteger(0)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val messageIds = intent?.getStringArrayListExtra(EXTRA_MESSAGE_IDS) ?: emptyList<String>()
        Log.d(TAG, "onStartCommand: dispatching ${messageIds.size} message(s)")

        val keepAlive = intent?.getBooleanExtra("keep_alive", false) ?: false

        val isDispatch = !keepAlive && messageIds.isNotEmpty()
        if (isDispatch) activeDispatchCount.incrementAndGet()

        serviceScope.launch {
            var wakeLock: android.os.PowerManager.WakeLock? = null
            try {
                // ── Tombstone + attempt pre-count (2026-11 crash-loop fix) ────
                // Both MUST happen before any engine work. If this process dies
                // during Node boot / Baileys connect — before sendMessage ever
                // claims the rows — the row stays PENDING and the next backstop
                // re-dispatches it: one process-killing send then crashed the
                // app on every open for a whole day (until the 6h grace window
                // failed it). Now: (a) the tombstone file survives as the only
                // evidence of a hard death (DelivraApp surfaces it on next
                // launch), and (b) the attempts are pre-counted so the due
                // path's MAX_RETRY_ATTEMPTS cap stops the loop.
                if (!keepAlive && messageIds.isNotEmpty()) {
                    writeDispatchTombstone(messageIds)
                    messageIds.forEach { id -> repository.recordDispatchAttempt(id) }
                }

                // Wait for bridge channel to be initialized by setupNodeRuntime
                val deadline = System.currentTimeMillis() + 90_000L
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
                // Dispatch ended while this process was alive (success, timeout,
                // or handled error) — the tombstone has served its purpose. If
                // the process had died hard mid-dispatch, this line never runs
                // and the file survives for DelivraApp to report.
                clearDispatchTombstone()
                try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
                if (isDispatch && activeDispatchCount.decrementAndGet() == 0) {
                    // Last dispatch out — safe to tear down. (disconnect() is
                    // non-suspend, so this cleanup also runs when the coroutine
                    // itself was cancelled mid-flight.)
                    nodeBridge.disconnect()
                    stopSelf()
                }
            }
        }

        return START_NOT_STICKY  // No auto-restart — AlarmManager handles next wakeup (§6.6)
    }

    // ── Dispatch tombstone ─────────────────────────────────────────────────────
    // A hard process death mid-dispatch (native crash, WASM abort, LMK) bypasses
    // every try/catch in the dispatch path — this tiny file is the ONLY evidence
    // that it happened, and what it was dispatching. See DelivraApp recovery.

    private fun writeDispatchTombstone(ids: List<String>) {
        try {
            val json = StringBuilder("{\"ids\":[")
            ids.forEachIndexed { i, id ->
                if (i > 0) json.append(',')
                json.append('"').append(id.replace("\"", "")).append('"')
            }
            json.append("],\"at\":").append(System.currentTimeMillis()).append('}')
            java.io.File(filesDir, Constants.DISPATCH_TOMBSTONE).writeText(json.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Dispatch tombstone write failed: ${e.message}")
        }
    }

    private fun clearDispatchTombstone() {
        try { java.io.File(filesDir, Constants.DISPATCH_TOMBSTONE).delete() } catch (_: Exception) {}
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
         *          v15/v16 = CI patch automation, foregroundServiceType dataSync.
         *          v17 = readFileSync media sends, minify-engine --patch-only CI gate.
         *          v18 = MEDIA FIX: Baileys 6.7.24 mediaConn poison guard (Patch 4 in
         *                minify-engine.mjs — a failed media_conn iq query used to stay
         *                cached as a rejected promise, failing EVERY media send for the
         *                socket session while text worked), and whatsapp.js now falls
         *                back to the bundled Baileys protocol version instead of a
         *                stale hardcoded triple that WhatsApp servers can reject.
         *          v19 = IMAGE SEND FIX (2026-11 all-day crash loop): the first VN+image
         *                send aborted the whole process inside sharp → @img/sharp-wasm32
         *                (8.9MB WASM libvips — the only image backend on this runtime;
         *                jimp isn't shipped). A WASM abort is uncatchable, the row stayed
         *                PENDING, and every app open re-dispatched → all-day crash loop
         *                until the 6h grace window failed it. Thumbnails now come from
         *                Kotlin platform codecs (sender.js jpegThumbnail passthrough;
         *                Patch 6 neutralizes getImageProcessingLibrary), plus dispatch
         *                tombstone + attempt pre-count in SchedulerService.
         *          v20 = ENGINE TREE MOVED TO filesDir (root-cause fix for the
         *                eviction class): cacheDir suffers random per-file eviction
         *                (Samsung Device Care — live-confirmed twice), which killed
         *                Baileys' lib/Socket/index.js while the version marker AND
         *                all sentinels survived. Plus the .boot-ok integrity marker
         *                (crash-guard.js arms handlers before ANY engine import,
         *                index.js re-marks after the full ESM graph executes; a
         *                missing marker forces re-extract) and lib/Socket/index.js
         *                added to the sentinel list.
         */
        private const val NODEJS_ASSETS_VERSION = "20"

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
            // lib/Socket/index.js is THE twice-confirmed eviction victim
            // (2026-11-13 and 2026-11-15 crashes) — its absence failed the
            // whole ESM graph while every other sentinel survived.
            "node_modules/@whiskeysockets/baileys/lib/Socket/index.js",
            "node_modules/@hapi/boom/lib/index.js",
            "node_modules/pino/pino.js",
            // The three DELIVRA-patched Baileys files. Their patch markers exempt
            // them from minification, but NOTHING exempted them from cache
            // eviction: if Android evicts one of these while the version marker
            // survives, Node's import chain fails BEFORE index.js registers its
            // uncaughtException handler → default Node behavior = process.exit →
            // whole app dies at boot → day-2-style crash loop (the 2026-11 class).
            "node_modules/@whiskeysockets/baileys/lib/Utils/messages-media.js",
            "node_modules/@whiskeysockets/baileys/lib/Utils/messages.js",
            "node_modules/@whiskeysockets/baileys/lib/Socket/messages-send.js"
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
