package radio.ks3ckc.ft8us.ui.settings

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.bg7yoz.ft8cn.GeneralVariables
import com.bg7yoz.ft8cn.MainViewModel
import com.bg7yoz.ft8cn.R
import com.bg7yoz.ft8cn.bluetooth.BluetoothConstants
import com.bg7yoz.ft8cn.flex.FlexRadio
import com.bg7yoz.ft8cn.flex.FlexRadioFactory
import com.bg7yoz.ft8cn.icom.IComWifiRig
import com.bg7yoz.ft8cn.icom.XieGuWifiRig
import com.bg7yoz.ft8cn.rigs.InstructionSet
import com.bg7yoz.ft8cn.ui.ToastMessage
import com.bg7yoz.ft8cn.x6100.X6100Radio
import com.bg7yoz.ft8cn.x6100.XieguRadioFactory
import radio.ks3ckc.ft8us.theme.*

// ─────────────────────────────────────────────────────────────────────
// Shared helpers
// ─────────────────────────────────────────────────────────────────────

private val dialogShape = RoundedCornerShape(16.dp)
private val buttonShape = RoundedCornerShape(12.dp)

@Composable
private fun DialogAccentButton(
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val bg = if (enabled) Accent else BgSurface3
    val textColor = if (enabled) BgApp else TextFaint
    Box(
        modifier = modifier
            .height(46.dp)
            .clip(buttonShape)
            .background(bg)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = textColor,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
        )
    }
}

@Composable
private fun DialogFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    cursorColor = Accent,
    focusedBorderColor = Accent,
    unfocusedBorderColor = BorderStrong,
    focusedLabelColor = Accent,
    unfocusedLabelColor = TextMuted,
)

// ─────────────────────────────────────────────────────────────────────
// 1. Bluetooth Picker
// ─────────────────────────────────────────────────────────────────────

private data class BtDeviceInfo(
    val device: BluetoothDevice,
    val isSPP: Boolean,
    val isHeadSet: Boolean,
)

@SuppressLint("MissingPermission")
@Composable
fun BluetoothPickerDialog(
    mainViewModel: MainViewModel,
    onDismiss: () -> Unit,
) {
    val devices = remember {
        val list = mutableListOf<BtDeviceInfo>()
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter != null) {
            for (device in adapter.bondedDevices) {
                val spp = BluetoothConstants.checkIsSpp(device)
                val headset = BluetoothConstants.checkIsHeadSet(device)
                if (spp) {
                    list.add(0, BtDeviceInfo(device, isSPP = true, isHeadSet = headset))
                } else if (headset) {
                    list.add(BtDeviceInfo(device, isSPP = false, isHeadSet = true))
                }
            }
        }
        list
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(dialogShape)
                .background(BgSurface2)
                .padding(vertical = 24.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_conn_bluetooth),
                color = TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 24.dp),
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (devices.isEmpty()) {
                Text(
                    text = stringResource(R.string.settings_no_bluetooth_devices),
                    color = TextMuted,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                ) {
                    itemsIndexed(devices) { _, info ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    ToastMessage.show(
                                        String.format(
                                            GeneralVariables.getStringFromResource(R.string.select_bluetooth_device),
                                            info.device.name,
                                        ),
                                    )
                                    // Remember this device so we can auto-reconnect the
                                    // SPP/CAT link on the next app launch (issue #223).
                                    GeneralVariables.bluetoothDeviceAddress = info.device.address
                                    mainViewModel.databaseOpr.writeConfig(
                                        "bluetoothDeviceAddress",
                                        info.device.address,
                                        null,
                                    )
                                    mainViewModel.connectBluetoothRig(
                                        GeneralVariables.getMainContext(),
                                        info.device,
                                    )
                                    onDismiss()
                                }
                                .padding(horizontal = 24.dp, vertical = 10.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = info.device.name ?: "—",
                                    color = if (info.isSPP) TextPrimary else TextMuted,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                                if (info.isSPP) {
                                    Text(
                                        text = "SPP",
                                        color = Accent,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(AccentSoft)
                                            .padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                            }
                            Text(
                                text = info.device.address ?: "",
                                color = TextFaint,
                                fontSize = 12.sp,
                            )
                        }
                        HorizontalDivider(color = Border)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_cancel), color = TextMuted)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────
// 2. FlexRadio Picker
// ─────────────────────────────────────────────────────────────────────

@Composable
fun FlexRadioPickerDialog(
    mainViewModel: MainViewModel,
    onDismiss: () -> Unit,
) {
    var ipText by remember { mutableStateOf("") }
    val discoveredRadios = remember { mutableStateListOf<FlexRadio>() }

    // Subscribe to FlexRadioFactory discovery events
    DisposableEffect(Unit) {
        val factory = FlexRadioFactory.getInstance()
        // Seed with any already-discovered radios
        discoveredRadios.clear()
        discoveredRadios.addAll(factory.flexRadios)

        val listener = object : FlexRadioFactory.OnFlexRadioEvents {
            override fun OnFlexRadioAdded(flexRadio: FlexRadio) {
                discoveredRadios.clear()
                discoveredRadios.addAll(factory.flexRadios)
            }
            override fun OnFlexRadioInvalid(flexRadio: FlexRadio) {
                discoveredRadios.clear()
                discoveredRadios.addAll(factory.flexRadios)
            }
        }
        factory.setOnFlexRadioEvents(listener)
        onDispose { factory.setOnFlexRadioEvents(null) }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(dialogShape)
                .background(BgSurface2)
                .padding(vertical = 24.dp),
        ) {
            Text(
                text = "FlexRadio",
                color = TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 24.dp),
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Manual IP entry
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = ipText,
                    onValueChange = { ipText = it },
                    label = { Text("IP Address") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    colors = DialogFieldColors(),
                    modifier = Modifier.weight(1f),
                )
                DialogAccentButton(
                    label = stringResource(R.string.icom_login_button),
                    enabled = ipText.isNotBlank(),
                    modifier = Modifier.width(80.dp),
                ) {
                    ToastMessage.show(
                        String.format(
                            GeneralVariables.getStringFromResource(R.string.connect_flex_ip),
                            ipText,
                        ),
                    )
                    val flexRadio = FlexRadio()
                    flexRadio.ip = ipText.trim()
                    flexRadio.model = "FlexRadio"
                    mainViewModel.connectFlexRadioRig(GeneralVariables.getMainContext(), flexRadio)
                    onDismiss()
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Discovered radios
            if (discoveredRadios.isNotEmpty()) {
                HorizontalDivider(color = Border, modifier = Modifier.padding(horizontal = 24.dp))

                Spacer(modifier = Modifier.height(8.dp))

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp),
                ) {
                    itemsIndexed(discoveredRadios.toList()) { _, radio ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    ToastMessage.show(
                                        String.format(
                                            GeneralVariables.getStringFromResource(R.string.select_flex_device),
                                            radio.model,
                                        ),
                                    )
                                    mainViewModel.connectFlexRadioRig(
                                        GeneralVariables.getMainContext(),
                                        radio,
                                    )
                                    onDismiss()
                                }
                                .padding(horizontal = 24.dp, vertical = 10.dp),
                        ) {
                            Text(
                                text = radio.model ?: "FlexRadio",
                                color = TextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(text = radio.ip ?: "", color = Accent, fontSize = 12.sp)
                                Text(text = radio.serial ?: "", color = TextFaint, fontSize = 12.sp)
                            }
                        }
                        HorizontalDivider(color = Border)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_cancel), color = TextMuted)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────
// 3. Xiegu Radio Picker
// ─────────────────────────────────────────────────────────────────────

@Composable
fun XieguRadioPickerDialog(
    mainViewModel: MainViewModel,
    onDismiss: () -> Unit,
) {
    var ipText by remember { mutableStateOf("") }
    val discoveredRadios = remember { mutableStateListOf<X6100Radio>() }

    // Subscribe to XieguRadioFactory discovery events
    DisposableEffect(Unit) {
        val factory = XieguRadioFactory.getInstance()
        discoveredRadios.clear()
        discoveredRadios.addAll(factory.xieguRadios)

        val listener = object : XieguRadioFactory.OnXieguRadioEvents {
            override fun onXieguRadioAdded(radio: X6100Radio) {
                discoveredRadios.clear()
                discoveredRadios.addAll(factory.xieguRadios)
            }
            override fun onXieguRadioInvalid(radio: X6100Radio) {
                discoveredRadios.clear()
                discoveredRadios.addAll(factory.xieguRadios)
            }
        }
        factory.setOnXieguRadioEvents(listener)
        onDispose { factory.setOnXieguRadioEvents(null) }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(dialogShape)
                .background(BgSurface2)
                .padding(vertical = 24.dp),
        ) {
            Text(
                text = "Xiegu",
                color = TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 24.dp),
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Manual IP entry
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = ipText,
                    onValueChange = { ipText = it },
                    label = { Text("IP Address") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    colors = DialogFieldColors(),
                    modifier = Modifier.weight(1f),
                )
                DialogAccentButton(
                    label = stringResource(R.string.icom_login_button),
                    enabled = ipText.isNotBlank(),
                    modifier = Modifier.width(80.dp),
                ) {
                    val radio = X6100Radio()
                    radio.rig_ip = ipText.trim()
                    radio.modelName = "Xiegu Rig"
                    ToastMessage.show(radio.rig_ip)
                    mainViewModel.connectXieguRadioRig(GeneralVariables.getMainContext(), radio)
                    onDismiss()
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Discovered radios
            if (discoveredRadios.isNotEmpty()) {
                HorizontalDivider(color = Border, modifier = Modifier.padding(horizontal = 24.dp))

                Spacer(modifier = Modifier.height(8.dp))

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp),
                ) {
                    itemsIndexed(discoveredRadios.toList()) { _, radio ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    ToastMessage.show(
                                        String.format(
                                            GeneralVariables.getStringFromResource(R.string.select_xiegu_device),
                                            radio.modelName,
                                        ),
                                    )
                                    mainViewModel.connectXieguRadioRig(
                                        GeneralVariables.getMainContext(),
                                        radio,
                                    )
                                    onDismiss()
                                }
                                .padding(horizontal = 24.dp, vertical = 10.dp),
                        ) {
                            Text(
                                text = radio.modelName ?: "Xiegu",
                                color = TextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(text = radio.rig_ip ?: "", color = Accent, fontSize = 12.sp)
                                Text(text = radio.mac ?: "", color = TextFaint, fontSize = 12.sp)
                            }
                        }
                        HorizontalDivider(color = Border)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_cancel), color = TextMuted)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────
// 4. ICOM / Xiegu-X6100 WiFi Login
// ─────────────────────────────────────────────────────────────────────

@Composable
fun IcomLoginDialog(
    mainViewModel: MainViewModel,
    onDismiss: () -> Unit,
) {
    var ip by remember { mutableStateOf(GeneralVariables.icomIp.orEmpty()) }
    var port by remember { mutableStateOf(GeneralVariables.icomUdpPort.toString()) }
    var username by remember { mutableStateOf(GeneralVariables.icomUserName.orEmpty()) }
    var password by remember { mutableStateOf(GeneralVariables.icomPassword.orEmpty()) }
    var passwordVisible by remember { mutableStateOf(false) }

    val portValid = port.toIntOrNull() != null
    val allFilled = ip.isNotBlank() && port.isNotBlank() && username.isNotBlank() &&
        password.isNotEmpty() && portValid

    val fieldColors = DialogFieldColors()

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(dialogShape)
                .background(BgSurface2)
                .padding(horizontal = 24.dp, vertical = 24.dp),
        ) {
            Text(
                text = if (GeneralVariables.instructionSet == InstructionSet.ICOM) "ICOM" else "Xiegu X6100",
                color = TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )

            Spacer(modifier = Modifier.height(16.dp))

            // IP Address
            OutlinedTextField(
                value = ip,
                onValueChange = {
                    ip = it
                    GeneralVariables.icomIp = it.trim()
                    mainViewModel.databaseOpr.writeConfig("icomIp", it.trim(), null)
                },
                label = { Text("IP Address") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Port
            OutlinedTextField(
                value = port,
                onValueChange = {
                    port = it
                    val trimmed = it.trim()
                    if (GeneralVariables.isInteger(trimmed)) {
                        GeneralVariables.icomUdpPort = trimmed.toInt()
                        mainViewModel.databaseOpr.writeConfig("icomPort", trimmed, null)
                    }
                },
                label = { Text(stringResource(R.string.icom_port)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = port.isNotBlank() && !portValid,
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Username
            OutlinedTextField(
                value = username,
                onValueChange = {
                    username = it
                    GeneralVariables.icomUserName = it.trim()
                    mainViewModel.databaseOpr.writeConfig("icomUserName", it.trim(), null)
                },
                label = { Text(stringResource(R.string.icom_user_name)) },
                singleLine = true,
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Password
            OutlinedTextField(
                value = password,
                onValueChange = {
                    password = it
                    GeneralVariables.icomPassword = it
                    mainViewModel.databaseOpr.writeConfig("icomPassword", it, null)
                },
                label = { Text(stringResource(R.string.icom_password)) },
                singleLine = true,
                visualTransformation = if (passwordVisible) VisualTransformation.None
                    else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            painter = painterResource(
                                if (passwordVisible) R.drawable.ic_baseline_visibility_24
                                else R.drawable.ic_visibility_off,
                            ),
                            contentDescription = stringResource(R.string.show_password),
                            tint = TextMuted,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                },
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(20.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.action_cancel), color = TextMuted)
                }
                DialogAccentButton(
                    label = stringResource(R.string.icom_login_button),
                    enabled = allFilled,
                    modifier = Modifier.weight(1f),
                ) {
                    if (GeneralVariables.instructionSet == InstructionSet.ICOM) {
                        ToastMessage.show(
                            String.format(
                                GeneralVariables.getStringFromResource(R.string.connect_icom_ip),
                                ip,
                            ),
                        )
                        mainViewModel.connectWifiRig(
                            IComWifiRig(
                                GeneralVariables.icomIp,
                                GeneralVariables.icomUdpPort,
                                GeneralVariables.icomUserName,
                                GeneralVariables.icomPassword,
                            ),
                        )
                    } else if (GeneralVariables.instructionSet == InstructionSet.XIEGU_6100) {
                        ToastMessage.show(
                            String.format(
                                GeneralVariables.getStringFromResource(R.string.connect_xiegu_ip),
                                ip,
                            ),
                        )
                        mainViewModel.connectWifiRig(
                            XieGuWifiRig(
                                GeneralVariables.icomIp,
                                GeneralVariables.icomUdpPort,
                                GeneralVariables.icomUserName,
                                GeneralVariables.icomPassword,
                            ),
                        )
                    }
                    onDismiss()
                }
            }
        }
    }
}
