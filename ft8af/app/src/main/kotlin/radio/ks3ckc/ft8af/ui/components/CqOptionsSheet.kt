package radio.ks3ckc.ft8af.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.k1af.ft8af.R
import com.k1af.ft8af.ft8signal.FT8Package
import radio.ks3ckc.ft8af.theme.*

// ---- Pure logic (extracted for unit testing) ----

internal val CQ_PRESETS = listOf("", "DX", "NA", "EU", "SA", "AS", "OC", "AF")

internal fun presetLabel(modifier: String): String =
    if (modifier.isEmpty()) "CQ" else "CQ $modifier"

// Letters-only (1–4) to match the custom-modifier sanitizer, which strips digits.
// pack_c28 also supports a 3-digit form, but the UI can never produce one, so
// validating digits here would only accept input the app can't generate.
internal fun isValidModifier(text: String): Boolean =
    text.isEmpty() || text.matches(Regex("[A-Z]{1,4}"))

/**
 * Field Day must encode a real ARRL/RAC section; an unknown or blank section is
 * packed as index 0 ("AB") by [FT8Package.generatePack77_fd], silently sending
 * the wrong exchange. Only allow enabling FD when the section is recognized.
 */
internal fun canEnableFieldDay(section: String): Boolean =
    FT8Package.sectionIndex(section) >= 0

/**
 * Persist a Field Day section only when the packer recognizes it, or when the
 * user explicitly clears it while FD is off. This stops a half-typed/unknown
 * value from being restored as "AB" on the next launch.
 */
internal fun shouldPersistSection(section: String, fieldDayEnabled: Boolean): Boolean =
    FT8Package.sectionIndex(section) >= 0 || (section.isEmpty() && !fieldDayEnabled)

internal fun isValidFreeText(text: String): Boolean {
    val allowed = " 0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ+-./?".toSet()
    return text.length <= 13 && text.all { it in allowed }
}

/**
 * Assemble the on-air directed-CQ message: `CQ <text> <call>`, trimmed with
 * runs of whitespace collapsed to single spaces (so an empty text yields a plain
 * `CQ <call>` rather than `CQ  <call>`).
 */
internal fun buildDirectedCq(freeText: String, callsign: String): String =
    "CQ ${freeText.trim()} ${callsign.trim()}".trim().replace(Regex("\\s+"), " ")

/**
 * Whether the assembled `CQ <text> <call>` fits one FT8 free-text frame. The raw
 * field only bounds the user's text; this bounds the whole transmitted string
 * (13 chars + FT8 charset), which is what actually has to fit on the air.
 */
internal fun directedCqFits(freeText: String, callsign: String): Boolean =
    isValidFreeText(buildDirectedCq(freeText, callsign))

internal fun sanitizeModifier(input: String): String =
    input.uppercase().filter { it.isLetter() }.take(4)

internal fun sanitizeFreeText(input: String): String {
    val allowed = " 0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ+-./?".toSet()
    return input.uppercase().filter { it in allowed }.take(13)
}

// ---- Composable ----

@Composable
fun CqOptionsSheet(
    visible: Boolean,
    currentModifier: String,
    isFreeTextMode: Boolean,
    freeText: String,
    callsign: String,
    savedFreeText: String,
    fieldDayEnabled: Boolean,
    fieldDayClass: String,
    fieldDayNumTx: Int,
    fieldDaySection: String,
    onDismiss: () -> Unit,
    onSelectPreset: (String) -> Unit,
    onCustomModifier: (String) -> Unit,
    onFreeTextChange: (String) -> Unit,
    onArmSavedCq: () -> Unit,
    onRemoveSavedCq: () -> Unit,
    onFieldDayToggle: (Boolean) -> Unit,
    onFieldDayClassChange: (String) -> Unit,
    onFieldDayNumTxChange: (Int) -> Unit,
    onFieldDaySectionChange: (String) -> Unit,
    onCallCQ: () -> Unit,
) {
    FT8AFBottomSheet(
        visible = visible,
        onDismiss = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            // ---- Section: CQ MESSAGE ----
            SectionHeader(stringResource(R.string.cq_message_title))

            Spacer(modifier = Modifier.height(8.dp))

            // Preset chip row
            val scrollState = rememberScrollState()
            val chipShape = RoundedCornerShape(999.dp)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(scrollState),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (preset in CQ_PRESETS) {
                    val label = presetLabel(preset)
                    val isSelected = !isFreeTextMode && !fieldDayEnabled && currentModifier == preset
                    val bgColor = if (isSelected) AccentSoft else BgSurface2
                    val borderColor = if (isSelected) BorderAmber else Border
                    val textColor = if (isSelected) Accent else TextMuted

                    Row(
                        modifier = Modifier
                            .height(32.dp)
                            .clip(chipShape)
                            .background(bgColor, chipShape)
                            .border(1.dp, borderColor, chipShape)
                            .clickable {
                                onSelectPreset(preset)
                                onDismiss()
                            }
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = label,
                            color = textColor,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                            fontFamily = GeistMonoFamily,
                            letterSpacing = 0.02.sp,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Custom modifier text field
            OutlinedTextField(
                value = if (!isFreeTextMode && !fieldDayEnabled) currentModifier else "",
                onValueChange = { onCustomModifier(sanitizeModifier(it)) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(
                        text = stringResource(R.string.cq_custom_modifier_hint),
                        color = TextFaint,
                        fontSize = 13.sp,
                        fontFamily = GeistMonoFamily,
                    )
                },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontFamily = GeistMonoFamily,
                ),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent,
                    unfocusedBorderColor = Border,
                    cursorColor = Accent,
                ),
            )

            // ---- Divider: "or" ----
            OrDivider()

            // ---- Section: FIELD DAY ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                SectionHeader(stringResource(R.string.cq_field_day_title))
                Switch(
                    checked = fieldDayEnabled,
                    onCheckedChange = onFieldDayToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Accent,
                        checkedTrackColor = AccentSoft,
                        uncheckedThumbColor = TextMuted,
                        uncheckedTrackColor = BgSurface3,
                    ),
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Class chips
            Text(
                text = stringResource(R.string.cq_fd_class),
                color = TextMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = GeistMonoFamily,
                letterSpacing = 0.04.sp,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (cls in listOf("A", "B", "C", "D", "E", "F")) {
                    val isSelected = fieldDayClass == cls
                    val bgColor = if (isSelected) AccentSoft else BgSurface2
                    val borderColor = if (isSelected) BorderAmber else Border
                    val textColor = if (isSelected) Accent else TextMuted

                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(chipShape)
                            .background(bgColor, chipShape)
                            .border(1.dp, borderColor, chipShape)
                            .clickable { onFieldDayClassChange(cls) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = cls,
                            color = textColor,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            fontFamily = GeistMonoFamily,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Transmitters stepper
            Text(
                text = stringResource(R.string.cq_fd_transmitters),
                color = TextMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = GeistMonoFamily,
                letterSpacing = 0.04.sp,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(BgSurface3)
                        .clickable(enabled = fieldDayNumTx > 1) {
                            onFieldDayNumTxChange((fieldDayNumTx - 1).coerceAtLeast(1))
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "\u2212",
                        color = if (fieldDayNumTx > 1) TextMuted else TextFaint,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = GeistMonoFamily,
                    )
                }
                Text(
                    text = fieldDayNumTx.toString(),
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = GeistMonoFamily,
                    letterSpacing = 0.02.sp,
                )
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(BgSurface3)
                        .clickable(enabled = fieldDayNumTx < 16) {
                            onFieldDayNumTxChange((fieldDayNumTx + 1).coerceAtMost(16))
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "+",
                        color = if (fieldDayNumTx < 16) TextMuted else TextFaint,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = GeistMonoFamily,
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Section text field
            Text(
                text = stringResource(R.string.cq_fd_section),
                color = TextMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = GeistMonoFamily,
                letterSpacing = 0.04.sp,
            )
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedTextField(
                value = fieldDaySection,
                onValueChange = {
                    val cleaned = it.uppercase().filter { c -> c.isLetter() }.take(3)
                    onFieldDaySectionChange(cleaned)
                },
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(
                        text = stringResource(R.string.cq_fd_section_hint),
                        color = TextFaint,
                        fontSize = 13.sp,
                        fontFamily = GeistMonoFamily,
                    )
                },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontFamily = GeistMonoFamily,
                ),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent,
                    unfocusedBorderColor = Border,
                    cursorColor = Accent,
                ),
            )

            // ---- Divider: "or" ----
            OrDivider()

            // ---- Section: FREE TEXT ----
            SectionHeader(stringResource(R.string.cq_free_text_title))

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = freeText,
                onValueChange = { onFreeTextChange(sanitizeFreeText(it)) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(
                        text = stringResource(R.string.cq_free_text_hint),
                        color = TextFaint,
                        fontSize = 13.sp,
                        fontFamily = GeistMonoFamily,
                    )
                },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontFamily = GeistMonoFamily,
                ),
                supportingText = {
                    // Validate the *assembled* on-air string (CQ + text + call),
                    // not just the raw field — that's what has to fit 13 chars.
                    if (freeText.isNotBlank() && !directedCqFits(freeText, callsign)) {
                        Text(
                            text = stringResource(R.string.cq_directed_too_long),
                            color = StatusBad,
                            fontSize = 11.sp,
                            fontFamily = GeistMonoFamily,
                        )
                    } else {
                        Text(
                            text = "${freeText.length}/13",
                            color = if (freeText.length >= 13) StatusBad else TextMuted,
                            fontSize = 11.sp,
                            fontFamily = GeistMonoFamily,
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent,
                    unfocusedBorderColor = Border,
                    cursorColor = Accent,
                ),
            )

            // Saved custom-CQ quick-select chip (only when one is persisted).
            // Tapping the label re-arms it; the X clears the saved value.
            if (savedFreeText.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.cq_saved_label),
                        color = TextMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = GeistMonoFamily,
                        letterSpacing = 0.04.sp,
                    )
                    val savedSelected = isFreeTextMode && freeText == savedFreeText
                    val bgColor = if (savedSelected) AccentSoft else BgSurface2
                    val borderColor = if (savedSelected) BorderAmber else Border
                    val textColor = if (savedSelected) Accent else TextMuted

                    Row(
                        modifier = Modifier
                            .height(32.dp)
                            .clip(chipShape)
                            .background(bgColor, chipShape)
                            .border(1.dp, borderColor, chipShape)
                            .clickable { onArmSavedCq() }
                            .padding(start = 14.dp, end = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = savedFreeText,
                            color = textColor,
                            fontSize = 12.sp,
                            fontWeight = if (savedSelected) FontWeight.SemiBold else FontWeight.Medium,
                            fontFamily = GeistMonoFamily,
                            letterSpacing = 0.02.sp,
                        )
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(chipShape)
                                .clickable { onRemoveSavedCq() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "✕",
                                color = TextMuted,
                                fontSize = 12.sp,
                                fontFamily = GeistMonoFamily,
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ---- Action button ----
            val buttonLabel = when {
                isFreeTextMode && freeText.isNotBlank() -> stringResource(R.string.cq_send_free_text)
                fieldDayEnabled -> stringResource(R.string.cq_call_cq) + " FD"
                currentModifier.isNotEmpty() -> stringResource(R.string.cq_call_cq) + " " + currentModifier
                else -> stringResource(R.string.cq_call_cq)
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Accent)
                    .clickable {
                        onCallCQ()
                        onDismiss()
                    }
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FT8AFIcons.Transmit(size = 18.dp, color = BgApp, strokeWidth = 1.8f)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = buttonLabel,
                    color = BgApp,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = GeistMonoFamily,
                    letterSpacing = 0.04.sp,
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        color = TextMuted,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        fontFamily = GeistMonoFamily,
        letterSpacing = 0.08.sp,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun OrDivider() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = Border,
        )
        Text(
            text = stringResource(R.string.cq_or_divider),
            color = TextFaint,
            fontSize = 11.sp,
            fontFamily = GeistMonoFamily,
        )
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = Border,
        )
    }
}
