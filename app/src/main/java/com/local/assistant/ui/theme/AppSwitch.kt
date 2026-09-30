package com.local.assistant.ui.theme

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The app's on/off switch. Material's defaults take the off state from colour roles this palette
 * leaves unset, which drew a pale lavender track with a near-white knob and border: on white it
 * hardly read as a control. Here off is a grey outline around a grey knob, and on is the app's
 * black with a white knob and a tick, so either state is plainly a switch.
 */
@Composable
fun AppSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        thumbContent = if (checked) {
            { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(SwitchDefaults.IconSize)) }
        } else {
            null
        },
        colors = SwitchDefaults.colors(
            checkedThumbColor = AppColors.OnAccent,
            checkedTrackColor = AppColors.Accent,
            checkedBorderColor = AppColors.Accent,
            checkedIconColor = AppColors.Accent,
            uncheckedThumbColor = AppColors.TextSecondary,
            uncheckedTrackColor = AppColors.SurfaceMuted,
            uncheckedBorderColor = AppColors.TextSecondary,
            uncheckedIconColor = AppColors.SurfaceMuted,
        ),
    )
}
