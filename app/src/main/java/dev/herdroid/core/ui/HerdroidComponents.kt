package dev.herdroid.core.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun ActionButton(label: String, onClick: () -> Unit) = ActionButton(label, label, onClick = onClick)

@Composable
fun ActionButton(
    label: String,
    description: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) = Button(
    onClick = onClick,
    enabled = enabled,
    modifier = modifier.heightIn(min = 48.dp).semantics { contentDescription = description },
    shape = MaterialTheme.shapes.small,
) { Text(label, fontWeight = FontWeight.Bold) }

@Composable
fun OutlinedActionButton(label: String, onClick: () -> Unit) =
    OutlinedActionButton(label, label, onClick = onClick)

@Composable
fun OutlinedActionButton(
    label: String,
    description: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) = OutlinedButton(
    onClick = onClick,
    modifier = modifier.heightIn(min = 48.dp).semantics { contentDescription = description },
    shape = MaterialTheme.shapes.small,
    colors = ButtonDefaults.outlinedButtonColors(
        containerColor = HerdrColors.Elevated,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ),
) { Text(label, fontWeight = FontWeight.Bold) }

@Composable
fun HerdrIconButton(
    label: String,
    description: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) = OutlinedButton(
    onClick = onClick,
    enabled = enabled,
    modifier = Modifier.size(48.dp).semantics { contentDescription = description },
    shape = MaterialTheme.shapes.small,
    contentPadding = PaddingValues(0.dp),
    colors = ButtonDefaults.outlinedButtonColors(
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ),
) { Text(label, fontWeight = FontWeight.Bold) }
