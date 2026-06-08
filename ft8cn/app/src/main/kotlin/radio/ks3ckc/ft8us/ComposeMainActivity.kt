package radio.ks3ckc.ft8us

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
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
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
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.Observer
import com.bg7yoz.ft8cn.GeneralVariables
import com.bg7yoz.ft8cn.MainViewModel
import com.bg7yoz.ft8cn.R
import com.bg7yoz.ft8cn.bluetooth.BluetoothStateBroadcastReceive
import com.bg7yoz.ft8cn.connector.CableSerialPort
import com.bg7yoz.ft8cn.connector.ConnectMode
import com.bg7yoz.ft8cn.callsign.CallsignDatabase
import com.bg7yoz.ft8cn.database.DatabaseOpr
import com.bg7yoz.ft8cn.database.OnAfterQueryConfig
import com.bg7yoz.ft8cn.database.OperationBand
import com.bg7yoz.ft8cn.location.GridLocationUpdater
import com.bg7yoz.ft8cn.log.ImportSharedLogs
import com.bg7yoz.ft8cn.wave.UsbAudioNative
import com.bg7yoz.ft8cn.log.OnShareLogEvents
import com.bg7yoz.ft8cn.maidenhead.MaidenheadGrid
import com.bg7yoz.ft8cn.ui.ToastMessage
import radio.ks3ckc.ft8us.pota.PotaSessionManager
import radio.ks3ckc.ft8us.theme.FT8USTheme
import radio.ks3ckc.ft8us.ui.components.ExitConfirmDialog
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ComposeMainActivity : AppCompatActivity() {

    private var bluetoothReceiver: BluetoothStateBroadcastReceive? = null
    private var usbDetachReceiver: BroadcastReceiver? = null
    private lateinit var mainViewModel: MainViewModel
    private val showExitConfirm: MutableState<Boolean> = mutableStateOf(false)

    companion object {
        private const val TAG = "ComposeMainActivity"
        private const val PERMISSION_REQUEST = 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Force night mode so the DayNight theme resolves to dark immediately,
        // preventing any light-mode surface colors from flashing before Compose loads.
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)

        // Build permissions list
        val permissions = buildPermissionsList()
        checkPermission(permissions)

        // Fullscreen and keep screen on
        @Suppress("DEPRECATION")
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        super.onCreate(savedInstanceState)

        GeneralVariables.getInstance().setMainContext(applicationContext)
        mainViewModel = MainViewModel.getInstance(this)
        ToastMessage.getInstance()

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

        // Register Bluetooth state broadcast receiver
        registerBluetoothReceiver()
        if (mainViewModel.isBTConnected()) {
            mainViewModel.setBlueToothOn()
        }

        // Register USB detach receiver — without this, a cable yank leaves the
        // recorder waiting on a dead handle and TX still pointing at a closed
        // UsbDeviceConnection. We tear down those handles here so the next
        // ATTACH event can rebind cleanly.
        registerUsbDetachReceiver()

        // Set Compose UI — splash plays once per cold start, then crossfades into the app.
        setContent {
            FT8USTheme {
                var showSplash by remember { mutableStateOf(true) }
                Crossfade(
                    targetState = showSplash,
                    animationSpec = tween(durationMillis = 360),
                    label = "splash-crossfade",
                ) { isSplash ->
                    if (isSplash) {
                        radio.ks3ckc.ft8us.ui.splash.FT8USplashScreen(
                            onSplashComplete = { showSplash = false }
                        )
                    } else {
                        FT8USApp(mainViewModel)
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
            com.bg7yoz.ft8cn.alert.DxAlertNotifier.EXTRA_CALLSIGN,
        ) ?: return
        if (callsign.isBlank()) return
        fileLog("handleAlertIntent: preselect $callsign")
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

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Proceed regardless; same behavior as original
    }

    private fun initData() {
        if (mainViewModel.configIsLoaded) return

        if (mainViewModel.operationBand == null) {
            mainViewModel.operationBand = OperationBand.getInstance(baseContext)
        }

        mainViewModel.databaseOpr.getQslDxccToMap()

        mainViewModel.databaseOpr.getAllConfigParameter(object : OnAfterQueryConfig {
            override fun doOnBeforeQueryConfig(keyName: String?) {}

            override fun doOnAfterQueryConfig(keyName: String?, value: String?) {
                mainViewModel.configIsLoaded = true
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

        DatabaseOpr.GetCallsignMapGrid(mainViewModel.databaseOpr.db).execute()
        mainViewModel.getFollowCallsignsFromDataBase()

        if (GeneralVariables.callsignDatabase == null) {
            GeneralVariables.callsignDatabase = CallsignDatabase.getInstance(baseContext, null, 1)
        }
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

    override fun onDestroy() {
        unregisterBluetoothReceiver()
        unregisterUsbDetachReceiver()
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

    /** Write a line to /sdcard/Android/data/com.bg7yoz.ft8cn/files/debug.log */
    private fun fileLog(msg: String) {
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
                val intVal = (newVol * 100).toInt()
                mainViewModel.databaseOpr.writeConfig("volumeValue", intVal.toString(), null)
                mainViewModel.baseRig?.connector?.setRFVolume(intVal)

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
        System.exit(0)
    }
}
