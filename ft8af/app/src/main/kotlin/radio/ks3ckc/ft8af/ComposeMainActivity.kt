package radio.ks3ckc.ft8af

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.Observer
import com.k1af.ft8af.GeneralVariables
import com.k1af.ft8af.MainViewModel
import com.k1af.ft8af.R
import radio.ks3ckc.ft8af.crash.CrashReporting
import radio.ks3ckc.ft8af.rtota.RtotaTripManager
import radio.ks3ckc.ft8af.sync.QsoAutoSync
import radio.ks3ckc.ft8af.util.bluetoothAdapter
import com.k1af.ft8af.service.RxForegroundService
import com.k1af.ft8af.service.RxServiceController
import com.k1af.ft8af.bluetooth.BluetoothStateBroadcastReceive
import com.k1af.ft8af.bluetooth.ScoPolicy
import com.k1af.ft8af.connector.CableSerialPort
import com.k1af.ft8af.connector.ConnectMode
import com.k1af.ft8af.callsign.CallsignDatabase
import com.k1af.ft8af.database.DatabaseOpr
import com.k1af.ft8af.database.OnAfterQueryConfig
import com.k1af.ft8af.database.OperationBand
import com.k1af.ft8af.location.GpsClockUpdater
import com.k1af.ft8af.location.GridLocationUpdater
import com.k1af.ft8af.log.ImportSharedLogs
import com.k1af.ft8af.wave.UsbAudioNative
import com.k1af.ft8af.log.OnShareLogEvents
import com.k1af.ft8af.maidenhead.MaidenheadGrid
import com.k1af.ft8af.ui.ToastMessage
import radio.ks3ckc.ft8af.pota.PotaSessionManager
import radio.ks3ckc.ft8af.theme.FT8AFTheme
import radio.ks3ckc.ft8af.theme.applyTheme
import radio.ks3ckc.ft8af.theme.loadTheme
import radio.ks3ckc.ft8af.ui.components.ExitConfirmDialog
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ComposeMainActivity : AppCompatActivity() {

    private var bluetoothReceiver: BluetoothStateBroadcastReceive? = null
    private var usbDetachReceiver: BroadcastReceiver? = null
    private var qsoAutoSync: QsoAutoSync? = null
    private lateinit var mainViewModel: MainViewModel
    private val showExitConfirm: MutableState<Boolean> = mutableStateOf(false)

    companion object {
        private const val TAG = "ComposeMainActivity"
        private const val PERMISSION_REQUEST = 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply the saved theme synchronously before setContent: swap the live
        // Compose palette and set the matching night mode so neither the
        // pre-Compose native window background nor the first Compose frame
        // flashes the wrong shade. Defaults to dark when nothing is saved.
        applyTheme(loadTheme(this))

        // Build permissions list
        val permissions = buildPermissionsList()
        checkPermission(permissions)

        // Edge-to-edge has been mandatory since Android 15 (API 35) — and stays
        // enforced on our current target — while the old
        // FLAG_FULLSCREEN / window.statusBarColor APIs are deprecated. Opt into
        // edge-to-edge, then preserve the app's original full-screen look by
        // hiding the system bars immersively (transient reveal on swipe) instead
        // of the deprecated fullscreen flag. Keep the screen on as before.
        enableEdgeToEdge()
        // Screen-awake is now a setting (default on, matching the previous
        // hard-coded behaviour). Config has not hydrated yet at this point, so
        // this applies the default; onResume re-applies once the stored value is
        // loaded, and the settings toggle applies it live.
        ScreenWake.apply(window, GeneralVariables.keepScreenOn)

        super.onCreate(savedInstanceState)

        ImmersiveBars.apply(WindowCompat.getInsetsController(window, window.decorView))

        GeneralVariables.getInstance().setMainContext(applicationContext)
        mainViewModel = MainViewModel.getInstance(this)
        ToastMessage.getInstance()

        // The notification's Exit button routes here so it runs the same shutdown as the
        // in-app exit; cleared in onDestroy so a destroyed activity isn't leaked. Registered
        // BEFORE the service starts so there's no window where the notification can appear
        // and be tapped before the handler exists (which would fall back to a bare
        // stopSelf()/System.exit(0) that skips rig disconnect and RX teardown).
        RxForegroundService.setExitHandler { closeApp() }

        // Keep RX alive in the background (no-op until RECORD_AUDIO is granted; the
        // permission-result callback re-invokes this once the user grants it).
        startRxServiceIfPermitted()

        // Forward every TX-volume change to the native USB-direct write loop so a
        // slider move (or hardware-button / ALC auto-volume change) attenuates the
        // in-progress transmission live, protecting the rig from overdrive. Every
        // volume mutator posts to mutableVolumePercent, so this single wire covers
        // them all; the AudioTrack and CAT/UDP paths read volumePercent directly.
        // Seeded with the current value for the case where no change fires.
        // Lifecycle-bound (observe(this), not observeForever) so the observer is
        // removed automatically on destroy — otherwise every activity recreation
        // (rotation, theme/locale change, process restart) would stack another
        // observer and fire a redundant native setter per change. TX runs with the
        // activity foregrounded (STARTED), so STARTED-only delivery loses nothing.
        UsbAudioNative.setTxVolume(GeneralVariables.volumePercent)
        GeneralVariables.mutableVolumePercent.observe(this) { v ->
            if (v != null) UsbAudioNative.setTxVolume(v)
        }

        // Re-apply the screen-awake preference once config hydration finishes.
        // onCreate/onResume both run before initData()'s async config load
        // completes, so they apply the default (on); if the stored value is
        // actually false the screen would stay awake until the next resume or a
        // manual toggle. Observing mutableConfigLoaded catches the moment the real
        // value lands. Lifecycle-bound and delivered on the main thread, so the
        // window flag is set safely.
        mainViewModel.mutableConfigLoaded.observe(this) { loaded ->
            if (loaded == true) ScreenWake.apply(window, GeneralVariables.keepScreenOn)
        }

        // Register back press handler. Priority: dismiss the QSO sheet if
        // it's open, otherwise show exit confirm. Without this, back-from-
        // sheet tries to exit the whole app, which surprises users who
        // expect back to peel off the foreground overlay first.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val sheetCs = mainViewModel.qsoSheetCallsign.value
                val sheetMinimized = mainViewModel.qsoSheetMinimized.value == true
                if (sheetCs != null && !sheetMinimized) {
                    // Mirror DecodeScreen.onDismiss: minimize when a QSO is
                    // live so the panel header stays reopenable; fully clear
                    // otherwise.
                    if (mainViewModel.ft8TransmitSignal.isActivated) {
                        mainViewModel.qsoSheetMinimized.postValue(true)
                    } else {
                        mainViewModel.qsoSheetCallsign.postValue(null)
                        mainViewModel.qsoSheetMinimized.postValue(false)
                    }
                    return
                }
                showExitConfirm.value = true
            }
        })

        // Register Bluetooth state broadcast receiver. The launch-time
        // headset/SCO decision happens in initData's config-loaded callback:
        // it needs the persisted connectMode, which isn't in memory yet here.
        registerBluetoothReceiver()

        // Register USB detach receiver — without this, a cable yank leaves the
        // recorder waiting on a dead handle and TX still pointing at a closed
        // UsbDeviceConnection. We tear down those handles here so the next
        // ATTACH event can rebind cleanly.
        registerUsbDetachReceiver()

        // Auto-upload QSOs that failed to reach QRZ/Cloudlog while offline: listen for
        // connectivity returning and flush the unsynced rows, and flush once on start
        // (covers a QSO logged offline before the app was last closed). No-op unless a
        // service is enabled and rows are actually pending.
        qsoAutoSync = QsoAutoSync(applicationContext).apply {
            register()
            syncNow("app-start")
        }

        // RTOTA trip mode: restore an in-flight road trip (one the phone was killed or
        // rebooted in the middle of), resume GPS tracking, and push whatever queued up
        // offline. No-op unless trip mode is enabled and a trip is actually running.
        RtotaTripManager.init(applicationContext)

        // Set Compose UI — splash plays once per cold start, then crossfades into the app.
        setContent {
            FT8AFTheme {
                var showSplash by remember { mutableStateOf(true) }
                Crossfade(
                    targetState = showSplash,
                    animationSpec = tween(durationMillis = 360),
                    label = "splash-crossfade",
                ) { isSplash ->
                    if (isSplash) {
                        radio.ks3ckc.ft8af.ui.splash.FT8AFSplashScreen(
                            onSplashComplete = { showSplash = false }
                        )
                    } else {
                        FT8AFApp(mainViewModel)
                    }
                }

                ExitConfirmDialog(
                    visible = showExitConfirm.value,
                    onCancel = { showExitConfirm.value = false },
                    onConfirm = {
                        showExitConfirm.value = false
                        mainViewModel.ft8TransmitSignal.isActivated = false
                        closeApp()
                    },
                )
            }
        }

        // Initialize data
        fileLog("=== APP START ===")
        // Phase-1 native-library sanity log. Confirms libft8af_usb.so loaded
        // and JNI is reachable; Phase 2 will replace the sentinel with the
        // libusb version + capabilities string.
        if (UsbAudioNative.isAvailable()) {
            try {
                fileLog("UsbAudioNative: " + UsbAudioNative.nativeBuildString())
            } catch (e: Throwable) {
                fileLog("UsbAudioNative: call threw ${e.javaClass.simpleName}: ${e.message}")
            }
        } else {
            fileLog("UsbAudioNative: library NOT loaded")
        }
        initData()

        // Observe serial port changes for auto-connect (mirrors old MainActivity behavior)
        mainViewModel.mutableSerialPorts.observe(
            this,
            Observer { ports ->
                autoConnectUsbIfNeeded(ports)
            },
        )

        // Handle shared file import if needed
        if (mainViewModel.mutableImportShareRunning.value == true) {
            // Import is already running; UI will show progress
        } else {
            doReceiveShareFile(intent)
        }

        // Handle a tapped Needed-DX alert (cold start).
        handleAlertIntent(intent)
    }

    /**
     * If [intent] came from a tapped Needed-DX notification, publish the alerted callsign
     * so the Decode screen can switch to itself and scroll to + highlight that station.
     */
    private fun handleAlertIntent(intent: Intent?) {
        val callsign = intent?.getStringExtra(
            com.k1af.ft8af.alert.DxAlertNotifier.EXTRA_CALLSIGN,
        ) ?: return
        if (callsign.isBlank()) return
        // The full callsign goes to the local debug.log, but a callsign is PII in
        // this app, so the Sentry breadcrumb is redacted (see fileLog / the
        // "callsign is never sent" promise).
        fileLog(
            "handleAlertIntent: preselect $callsign",
            breadcrumbMsg = "handleAlertIntent: preselect <redacted>",
        )
        mainViewModel.mutablePreselectCallsign.postValue(callsign)
    }

    private fun buildPermissionsList(): Array<String> {
        val base = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.MODIFY_AUDIO_SETTINGS,
            Manifest.permission.WAKE_LOCK,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            base.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            base.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return base.toTypedArray()
    }

    private fun checkPermission(permissions: Array<String>) {
        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), PERMISSION_REQUEST)
        }
    }

    /**
     * Start the foreground RX service so decode keeps running when backgrounded / screen-off.
     * Gated on RECORD_AUDIO: starting a microphone-typed foreground service without it throws
     * on Android 14, so this is a no-op until the permission is granted (re-invoked from
     * onRequestPermissionsResult once the user grants it). RX is always-on, so rxActive=true.
     */
    private fun startRxServiceIfPermitted() {
        val micGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (RxServiceController.shouldRunService(true, micGranted)) {
            RxForegroundService.start(this)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Proceed regardless; same behavior as original.

        // RECORD_AUDIO just granted (first launch): start the background RX service now so
        // it begins in this session instead of only after the next app launch.
        val micIdx = permissions.indexOf(Manifest.permission.RECORD_AUDIO)
        if (micIdx >= 0 && grantResults.getOrNull(micIdx) == PackageManager.PERMISSION_GRANTED) {
            startRxServiceIfPermitted()
        }

        // GPS grid auto-update: the toggle in Settings (and the cold-start path)
        // requests ACCESS_FINE_LOCATION *asynchronously* and then immediately calls
        // GridLocationUpdater.refresh(). On the very first enable the permission is
        // still ungranted at that point, so start() bails and the subscription never
        // begins until the next app launch. Once the user grants location here, kick
        // refresh() again so updates start in the same session (issue #59).
        if (locationGrantedIn(permissions, grantResults)
            && GeneralVariables.autoUpdateGridFromGPS
        ) {
            fileLog("onRequestPermissionsResult: location granted, starting GPS grid updater")
            val grid = MaidenheadGrid.getMyMaidenheadGrid(applicationContext)
            if (grid.isNotEmpty()) {
                GeneralVariables.setMyMaidenheadGrid(grid)
                mainViewModel.databaseOpr.writeConfig("grid", grid, null)
            }
            GridLocationUpdater.refresh(applicationContext, mainViewModel)
        }

        // Same first-grant race for GPS clock discipline (issue #373): if the user just
        // granted location while the toggle is on, start disciplining in this session.
        if (locationGrantedIn(permissions, grantResults)
            && GeneralVariables.disciplineClockFromGPS
        ) {
            fileLog("onRequestPermissionsResult: location granted, starting GPS clock discipline")
            GpsClockUpdater.refresh(applicationContext)
        }

        // On Android 12+ the BT auto-connect at config-load time may have bailed with
        // NO_PERMISSION because BLUETOOTH_CONNECT was still pending. Once the user grants it,
        // retry the SPP/CAT auto-reconnect in the same session (issue #223). The rigConnected
        // guard inside makes this idempotent with the config-callback attempt.
        val btIdx = permissions.indexOf(Manifest.permission.BLUETOOTH_CONNECT)
        if (btIdx >= 0 && grantResults.getOrNull(btIdx) == PackageManager.PERMISSION_GRANTED
            && mainViewModel.configIsLoaded
        ) {
            fileLog("onRequestPermissionsResult: BLUETOOTH_CONNECT granted, retrying BT auto-connect")
            autoConnectBluetoothIfNeeded()
        }
    }

    private fun initData() {
        if (mainViewModel.configIsLoaded) return

        if (mainViewModel.operationBand == null) {
            mainViewModel.operationBand = OperationBand.getInstance(baseContext)
        }

        // Callsign database must be ready before getQslDxccToMap() — that
        // method's background thread needs it to resolve DXCC prefixes. If it
        // runs first, the null-check early-return leaves zoneMapReady false
        // permanently and DXCC/zone "new" flags never compute. (#251 review)
        if (GeneralVariables.callsignDatabase == null) {
            GeneralVariables.callsignDatabase = CallsignDatabase.getInstance(applicationContext, null, 1)
        }

        mainViewModel.databaseOpr.getQslDxccToMap()

        mainViewModel.databaseOpr.getAllConfigParameter(object : OnAfterQueryConfig {
            override fun doOnBeforeQueryConfig(keyName: String?) {}

            override fun doOnAfterQueryConfig(keyName: String?, value: String?) {
                mainViewModel.configIsLoaded = true
                mainViewModel.mutableConfigLoaded.postValue(true)
                fileLog("configLoaded: instructionSet=${GeneralVariables.instructionSet}, " +
                    "baudRate=${GeneralVariables.baudRate}, " +
                    "controlMode=${GeneralVariables.controlMode}, " +
                    "connectMode=${GeneralVariables.connectMode}")
                if (GeneralVariables.autoUpdateGridFromGPS) {
                    val grid = MaidenheadGrid.getMyMaidenheadGrid(applicationContext)
                    if (grid.isNotEmpty()) {
                        GeneralVariables.setMyMaidenheadGrid(grid)
                        mainViewModel.databaseOpr.writeConfig("grid", grid, null)
                    }
                    GridLocationUpdater.refresh(applicationContext, mainViewModel)
                }
                if (GeneralVariables.disciplineClockFromGPS) {
                    GpsClockUpdater.refresh(applicationContext)
                }
                mainViewModel.ft8TransmitSignal.setTimer_sec(GeneralVariables.transmitDelay)

                // The cycle timers were built (for FT8) before config loaded; now that the
                // persisted operating mode is known, rebuild them for it and sync the UI.
                mainViewModel.applyLoadedOperatingMode()

                // Resume any POTA activation that was interrupted by app close
                PotaSessionManager.resume()

                // Scan for USB devices AFTER config is loaded
                fileLog("initData: scanning USB devices")
                mainViewModel.getUsbDevice()
                val ports = mainViewModel.mutableSerialPorts.value
                fileLog("initData: found ${ports?.size ?: 0} serial port(s)")
                mainViewModel.reinitializeAudioInput()

                // Bring up headset/SCO for Bluetooth-rig users now that the persisted
                // connectMode is known. Gating this at onCreate time would read the
                // USB_CABLE default and skip SCO for every Bluetooth rig (PR #377
                // review); gating on connect mode at all keeps a car/headphones paired
                // for music from being yanked out of A2DP (the original bug).
                Handler(Looper.getMainLooper()).post {
                    if (ScoPolicy.shouldEnterHeadsetMode(
                            GeneralVariables.connectMode, mainViewModel.isBTConnected())
                    ) {
                        mainViewModel.setBlueToothOn()
                    }
                }

                // USB auto-connect is driven by the mutableSerialPorts observer; Bluetooth has
                // no such device-arrival event, so re-open the remembered SPP/CAT link here now
                // that connectMode + the saved address are loaded (issue #223).
                autoConnectBluetoothIfNeeded()

                // Delayed re-scan for slow USB enumeration
                Handler(Looper.getMainLooper()).postDelayed({
                    val connected = mainViewModel.isRigConnected()
                    fileLog("initData delayed: rigConnected=$connected")
                    if (!connected) {
                        mainViewModel.getUsbDevice()
                        val delayedPorts = mainViewModel.mutableSerialPorts.value
                        fileLog("initData delayed: found ${delayedPorts?.size ?: 0} serial port(s)")
                    }
                    mainViewModel.reinitializeAudioInput()
                }, 3000)
            }
        })

        DatabaseOpr.loadCallsignMapGridAsync(mainViewModel.databaseOpr.db)
        mainViewModel.getFollowCallsignsFromDataBase()
    }

    private fun doReceiveShareFile(intent: Intent) {
        val uri: Uri? = intent.data
        if (uri != null) {
            try {
                val inputStream = baseContext.contentResolver.openInputStream(uri)
                if (inputStream == null) {
                    Log.e(TAG, "Failed to open input stream for URI: $uri")
                    return
                }
                mainViewModel.mutableImportShareRunning.value = true
                val importSharedLogs = ImportSharedLogs(mainViewModel)
                Log.d(TAG, "Starting import...")
                importSharedLogs.doImport(
                    inputStream,
                    object : OnShareLogEvents {
                        override fun onPreparing(info: String?) {
                            mainViewModel.mutableShareInfo.postValue(info)
                        }

                        override fun onShareStart(count: Int, info: String?) {
                            mainViewModel.mutableSharePosition.postValue(0)
                            mainViewModel.mutableShareInfo.postValue(info)
                            mainViewModel.mutableImportShareRunning.postValue(true)
                            mainViewModel.mutableShareCount.postValue(count)
                        }

                        override fun onShareProgress(count: Int, position: Int, info: String?): Boolean {
                            mainViewModel.mutableSharePosition.postValue(position)
                            mainViewModel.mutableShareInfo.postValue(info)
                            mainViewModel.mutableShareCount.postValue(count)
                            return mainViewModel.mutableImportShareRunning.value == true
                        }

                        override fun afterGet(count: Int, info: String?) {
                            mainViewModel.mutableShareInfo.postValue(info)
                            mainViewModel.mutableImportShareRunning.postValue(false)
                        }

                        override fun onShareFailed(info: String?) {
                            mainViewModel.mutableShareInfo.postValue(info)
                        }
                    }
                )
            } catch (e: IOException) {
                mainViewModel.mutableImportShareRunning.postValue(false)
                Log.e(TAG, "Error: ${e.message}")
                ToastMessage.show(e.message)
            }
        }
    }

    // USB device attach events (singleTask launch mode)
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if ("android.hardware.usb.action.USB_DEVICE_ATTACHED" == intent.action) {
            fileLog("onNewIntent: USB_DEVICE_ATTACHED")
            // If the attached device is the one the user picked for direct USB
            // audio, make sure we hold permission before the delayed reinit
            // below tries to open it. USB permission can be dropped across an
            // unplug/replug; without this, the recorder reopens, finds no
            // permission, and silently falls back to the built-in mic. The
            // grant callback (MainViewModel.requestUsbPermissionIfNeeded) then
            // rebinds the input once the user allows it.
            val attached: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            if (attached != null
                && (GeneralVariables.isConfiguredUsbAudioInput(attached.vendorId, attached.productId)
                    || GeneralVariables.isConfiguredUsbAudioOutput(attached.vendorId, attached.productId))
            ) {
                fileLog("onNewIntent: attached device is configured USB audio; ensuring permission")
                mainViewModel.requestUsbPermissionIfNeeded(attached)
            }
            // Immediate scan
            mainViewModel.getUsbDevice()
            val ports = mainViewModel.mutableSerialPorts.value
            fileLog("onNewIntent: immediate scan found ${ports?.size ?: 0} port(s)")

            // Delayed re-scan and audio reinit (USB needs time to enumerate)
            Handler(Looper.getMainLooper()).postDelayed({
                val connected = mainViewModel.isRigConnected()
                fileLog("onNewIntent delayed: rigConnected=$connected")
                if (!connected) {
                    mainViewModel.getUsbDevice()
                    val delayedPorts = mainViewModel.mutableSerialPorts.value
                    fileLog("onNewIntent delayed: re-scan found ${delayedPorts?.size ?: 0} port(s)")
                }
                mainViewModel.reinitializeAudioInput()
            }, 2000)
        } else {
            setIntent(intent)
            doReceiveShareFile(intent)
            // Handle a tapped Needed-DX alert (app already running).
            handleAlertIntent(intent)
        }
    }

    private fun registerBluetoothReceiver() {
        if (bluetoothReceiver == null) {
            bluetoothReceiver = BluetoothStateBroadcastReceive(applicationContext, mainViewModel)
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(bluetoothReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(bluetoothReceiver, filter)
        }
    }

    private fun unregisterBluetoothReceiver() {
        bluetoothReceiver?.let {
            unregisterReceiver(it)
            bluetoothReceiver = null
        }
    }

    private fun registerUsbDetachReceiver() {
        if (usbDetachReceiver != null) return
        usbDetachReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != UsbManager.ACTION_USB_DEVICE_DETACHED) return
                val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }
                fileLog("usbDetach: vid=${device?.vendorId?.toString(16)} " +
                    "pid=${device?.productId?.toString(16)}")
                // Drop the recorder's USB audio handle. reinitialize() handles
                // both "device gone -> fall back to built-in mic" and
                // "device returned -> reopen". Idempotent and safe to call.
                try {
                    mainViewModel.reinitializeAudioInput()
                } catch (e: Exception) {
                    fileLog("usbDetach: reinitializeAudioInput threw: ${e.message}")
                }
            }
        }
        val filter = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbDetachReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbDetachReceiver, filter)
        }
    }

    private fun unregisterUsbDetachReceiver() {
        usbDetachReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
            usbDetachReceiver = null
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-apply the screen-awake preference. onCreate runs before config
        // hydration, so the stored value may differ from the default it applied;
        // this also covers returning from the settings screen.
        ScreenWake.apply(window, GeneralVariables.keepScreenOn)
    }

    override fun onStop() {
        // A tune carrier must never outlive the operator's attention (issue
        // #408): backgrounding or swiping the app away mid-tune force-stops the
        // tone and drops PTT. Normal FT8 TX is left alone — it is cycle-bounded
        // and self-terminates.
        try {
            mainViewModel.ft8TransmitSignal.stopTune()
        } catch (e: Exception) {
            fileLog("onStop: stopTune threw: ${e.message}")
        }
        super.onStop()
    }

    override fun onDestroy() {
        RxForegroundService.setExitHandler(null)
        unregisterBluetoothReceiver()
        unregisterUsbDetachReceiver()
        qsoAutoSync?.unregister()
        super.onDestroy()
    }

    /**
     * Auto-connect to USB serial rig when ports are detected, rig isn't already connected,
     * and connect mode is USB Cable with a non-VOX control mode.
     * Connects to the first detected port (most radios expose CAT as the first port).
     */
    private fun autoConnectUsbIfNeeded(ports: ArrayList<CableSerialPort.SerialPort>?) {
        if (ports.isNullOrEmpty()) {
            fileLog("autoConnect: no serial ports detected")
            return
        }
        if (mainViewModel.isRigConnected()) {
            fileLog("autoConnect: rig already connected, skipping")
            return
        }
        if (GeneralVariables.connectMode != ConnectMode.USB_CABLE) {
            fileLog("autoConnect: connectMode=${GeneralVariables.connectMode}, not USB_CABLE")
            return
        }
        if (!mainViewModel.configIsLoaded) {
            fileLog("autoConnect: config not loaded yet, skipping")
            return
        }

        fileLog("autoConnect: connecting to port 0 of ${ports.size} " +
            "(instructionSet=${GeneralVariables.instructionSet}, " +
            "baudRate=${GeneralVariables.baudRate}, " +
            "controlMode=${GeneralVariables.controlMode})")
        mainViewModel.connectCableRig(applicationContext, ports[0])
    }

    /**
     * Re-open the remembered Bluetooth (SPP/CAT) rig on startup when the user is in Bluetooth
     * connect mode. Without this the CAT link never re-opened on relaunch, which also left
     * HFP audio unrouted (issue #223). All branch logic lives in [decideBluetoothAutoConnect]
     * so it is unit-testable; this method only collects Android state and acts on CONNECT.
     */
    private fun autoConnectBluetoothIfNeeded() {
        val adapter = bluetoothAdapter(this)
        val addr = GeneralVariables.bluetoothDeviceAddress
        // A corrupted/legacy persisted value would make getRemoteDevice() throw
        // IllegalArgumentException and crash startup, so validate the MAC up front (PR #227 review).
        val validAddr = !addr.isNullOrBlank() && BluetoothAdapter.checkBluetoothAddress(addr)
        if (!addr.isNullOrBlank() && !validAddr) {
            fileLog("autoConnectBT: ignoring invalid saved address=${maskBluetoothAddress(addr)}")
        }
        val hasPerm = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
        val bonded = try {
            hasPerm && adapter != null && validAddr &&
                adapter.bondedDevices.any { it.address == addr }
        } catch (_: SecurityException) {
            false
        }
        val decision = decideBluetoothAutoConnect(
            connectMode = GeneralVariables.connectMode,
            savedAddress = if (validAddr) addr else null,
            rigConnected = mainViewModel.isRigConnected(),
            adapterOn = adapter?.isEnabled == true,
            hasConnectPermission = hasPerm,
            isBonded = bonded,
        )
        fileLog("autoConnectBT: decision=$decision addr=${maskBluetoothAddress(addr)} connectMode=${GeneralVariables.connectMode}")
        if (decision == BtAutoConnectDecision.CONNECT) {
            mainViewModel.connectBluetoothRig(applicationContext, adapter!!.getRemoteDevice(addr))
        }
    }

    /**
     * Write a line to /sdcard/Android/data/com.k1af.ft8af/files/debug.log.
     *
     * The full [msg] always goes to the on-device debug.log and logcat, but only
     * [breadcrumbMsg] is mirrored into Sentry — pass a redacted string (or null) for
     * lines carrying PII (callsign, grid, location) so it never rides along with a
     * crash report, keeping the "callsign/location are never sent" promise.
     */
    private fun fileLog(msg: String, breadcrumbMsg: String? = msg) {
        // Mirror the (possibly redacted) line into Sentry as a breadcrumb so a crash
        // report carries the same event trail (CAT sends, band changes, USB
        // attach…). No-op unless crash reporting is enabled. First, so it fires
        // even when the external dir is unavailable and the file write is skipped.
        breadcrumbMsg?.let { CrashReporting.breadcrumb(it) }
        try {
            val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            val dir = getExternalFilesDir(null) ?: return
            File(dir, "debug.log").appendText("$ts $msg\n")
        } catch (_: Exception) {}
        Log.d(TAG, msg)
    }

    private var volumeToast: android.widget.Toast? = null

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> {
                val delta = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) 0.05f else -0.05f
                val newVol = (GeneralVariables.volumePercent + delta).coerceIn(0.0f, 1.0f)
                GeneralVariables.volumePercent = newVol
                GeneralVariables.mutableVolumePercent.postValue(newVol)
                // Math.round via the shared helper (not toInt/floor) so this
                // producer agrees with the per-band restore/compare logic.
                val intVal = outputLevelFromVolumePercent(newVol)
                mainViewModel.databaseOpr.writeConfig("volumeValue", intVal.toString(), null)
                mainViewModel.baseRig?.connector?.setRFVolume(intVal)
                saveOutputLevelForCurrentBand(mainViewModel.databaseOpr, intVal)

                // Also adjust system music stream so audio is actually audible
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val systemVol = (newVol * maxVol).toInt().coerceIn(0, maxVol)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, systemVol, 0)

                // Show volume toast
                volumeToast?.cancel()
                volumeToast = android.widget.Toast.makeText(this, getString(R.string.main_tx_volume, intVal), android.widget.Toast.LENGTH_SHORT)
                volumeToast?.show()

                return true
            }
            else -> return super.onKeyDown(keyCode, event)
        }
    }

    private fun closeApp() {
        mainViewModel.ft8TransmitSignal.isActivated = false
        mainViewModel.baseRig?.connector?.disconnect()
        mainViewModel.ft8SignalListener.stopListen()
        mainViewModel.hamRecorder?.stopRecord()
        mainViewModel.utcTimer?.delete()
        RxForegroundService.stop(this)
        System.exit(0)
    }
}

/**
 * True if this permission result granted either fine or coarse location.
 * Top-level + [internal] so the GPS-grid restart decision (issue #59) can be
 * unit-tested without constructing the Activity.
 */
internal fun locationGrantedIn(
    permissions: Array<out String>,
    grantResults: IntArray,
): Boolean {
    for (i in permissions.indices) {
        if (i >= grantResults.size) break
        val p = permissions[i]
        if ((p == Manifest.permission.ACCESS_FINE_LOCATION ||
                p == Manifest.permission.ACCESS_COARSE_LOCATION) &&
            grantResults[i] == PackageManager.PERMISSION_GRANTED
        ) {
            return true
        }
    }
    return false
}
