/**
 * Reusable building blocks for the settings dialog's option rows.
 *
 * Every toggle in the settings UI must carry both a title and a description
 * so its effect is understandable without external docs; [SettingToggleRow]
 * is the single implementation of that convention.
 *
 * @see SettingsDialog
 */
package se.soderbjorn.lunamux.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A settings toggle row: checkbox + bold title + explanatory description.
 * The whole row is clickable (not just the checkbox) and dims when disabled.
 *
 * Called by [SettingsDialog]'s sections for every boolean setting.
 *
 * @param title short imperative label, e.g. "Allow connections from other devices".
 * @param description one or two sentences explaining what the toggle does and
 *   when a user would want it — required, never blank.
 * @param checked current value of the setting.
 * @param enabled whether the row accepts input; `false` renders it dimmed.
 * @param onCheckedChange invoked with the new value when the user toggles.
 */
@Composable
internal fun SettingToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = { onCheckedChange(!checked) }),
    ) {
        Checkbox(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
        )
        Column(modifier = Modifier.padding(end = 8.dp)) {
            Text(title, fontSize = 14.sp)
            Text(
                description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                lineHeight = 15.sp,
            )
        }
    }
}
