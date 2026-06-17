package radio.ks3ckc.ft8af.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.k1af.ft8af.R
import radio.ks3ckc.ft8af.theme.*
import radio.ks3ckc.ft8af.ui.components.FT8AFIcons
import radio.ks3ckc.ft8af.ui.components.TopBar

// ---------------------------------------------------------------------------
// Shared layout helpers
// ---------------------------------------------------------------------------

/**
 * A settings section with an uppercase muted title and its content block.
 */
@Composable
internal fun SettingsSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            color = TextFaint,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.08.sp,
            modifier = Modifier.padding(start = 4.dp),
        )
        content()
    }
}

/**
 * Thin divider between rows inside a [radio.ks3ckc.ft8af.ui.components.GlassCard].
 */
@Composable
internal fun SectionDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        thickness = 0.5.dp,
        color = Border,
    )
}

/**
 * Scaffold for a settings detail (drill-down) screen: a top bar with a back
 * chevron and the category title, followed by a scrolling content column with
 * the standard 16dp horizontal padding and 20dp inter-section spacing.
 *
 * [TopBar] has no built-in back affordance, so we prepend a rotated [FT8AFIcons.Chevron]
 * rather than change TopBar's signature (it's shared by every top-level tab).
 */
@Composable
internal fun SettingsDetailScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                FT8AFIcons.Chevron(
                    color = TextPrimary,
                    size = 22.dp,
                    modifier = Modifier.rotate(180f),
                )
            }
            TopBar(title = title, modifier = Modifier.weight(1f))
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            content()
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// Reusable dialog composables (shared across category screens)
// ---------------------------------------------------------------------------

/**
 * Dialog for editing a comma/space-separated list of tokens (blocklist entries).
 * One multi-line text field; the caller parses + persists the saved string.
 */
@Composable
internal fun TextListDialog(
    title: String,
    description: String,
    initialValue: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var input by remember { mutableStateOf(TextFieldValue(initialValue)) }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = TextPrimary,
        unfocusedTextColor = TextPrimary,
        cursorColor = Accent,
        focusedBorderColor = Accent,
        unfocusedBorderColor = BorderStrong,
        focusedLabelColor = Accent,
        unfocusedLabelColor = TextMuted,
    )

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(BgSurface2)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = title,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
            )

            Text(
                text = description,
                color = TextMuted,
                fontSize = 13.sp,
            )

            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text(stringResource(R.string.settings_blocklist_hint), color = TextFaint) },
                singleLine = false,
                minLines = 2,
                colors = fieldColors,
                textStyle = TextStyle(
                    fontFamily = GeistMonoFamily,
                    fontSize = 15.sp,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_cancel), color = TextMuted)
                }
                TextButton(onClick = { onSave(input.text) }) {
                    Text(stringResource(R.string.action_save), color = Accent, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/**
 * Scrollable list picker dialog with highlighted current selection.
 */
@Composable
internal fun ListPickerDialog(
    title: String,
    items: List<String>,
    selectedIndex: Int,
    onDismiss: () -> Unit,
    onSelect: (index: Int) -> Unit,
) {
    val listState = rememberLazyListState()

    // Scroll to the selected item when the dialog opens
    LaunchedEffect(selectedIndex) {
        if (selectedIndex > 0) {
            listState.scrollToItem((selectedIndex - 2).coerceAtLeast(0))
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(BgSurface2)
                .padding(vertical = 24.dp),
        ) {
            Text(
                text = title,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 0.dp),
            )

            Spacer(modifier = Modifier.height(16.dp))

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp),
            ) {
                itemsIndexed(items) { index, item ->
                    val isSelected = index == selectedIndex
                    val bg = if (isSelected) AccentSoft else BgSurface2
                    val textColor = if (isSelected) Accent else TextPrimary

                    Text(
                        text = item,
                        color = textColor,
                        fontSize = 14.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(index) }
                            .background(bg)
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_cancel), color = TextMuted)
                }
            }
        }
    }
}

/**
 * Numeric text input dialog with min/max validation.
 */
@Composable
internal fun NumberInputDialog(
    title: String,
    suffix: String,
    initialValue: Int,
    min: Int,
    max: Int,
    onDismiss: () -> Unit,
    onSave: (value: Int) -> Unit,
) {
    var textInput by remember { mutableStateOf(TextFieldValue(initialValue.toString())) }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = TextPrimary,
        unfocusedTextColor = TextPrimary,
        cursorColor = Accent,
        focusedBorderColor = Accent,
        unfocusedBorderColor = BorderStrong,
        focusedLabelColor = Accent,
        unfocusedLabelColor = TextMuted,
    )

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(BgSurface2)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = title,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
            )

            OutlinedTextField(
                value = textInput,
                onValueChange = { newValue ->
                    // Only allow digits
                    if (newValue.text.all { it.isDigit() }) {
                        textInput = newValue
                    }
                },
                label = { Text(stringResource(R.string.settings_min_max_suffix, min, max, suffix)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = fieldColors,
                textStyle = TextStyle(
                    fontFamily = GeistMonoFamily,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_cancel), color = TextMuted)
                }
                TextButton(
                    onClick = {
                        val parsed = textInput.text.toIntOrNull() ?: initialValue
                        onSave(parsed.coerceIn(min, max))
                    },
                ) {
                    Text(stringResource(R.string.action_save), color = Accent, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/**
 * Generic informational dialog: title + body text + dismiss.
 */
@Composable
internal fun InfoDialog(
    title: String,
    body: String,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(BgSurface2)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = title,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
            )

            Text(
                text = body,
                color = TextMuted,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_ok), color = Accent, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
