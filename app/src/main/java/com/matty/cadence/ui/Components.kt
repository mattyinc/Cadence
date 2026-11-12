package com.matty.cadence.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.matty.cadence.data.Library

val LocalPalette = staticCompositionLocalOf<Palette> { error("no palette") }
val LocalPrefs = staticCompositionLocalOf<Prefs> { error("no prefs") }
val LocalLibrary = staticCompositionLocalOf<Library> { error("no library") }

/** Capsule button: current choice = filled, otherwise glass. */
@Composable
fun Pill(
    text: String,
    selected: Boolean = false,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val pal = LocalPalette.current
    val bg = if (selected) pal.text else pal.glass
    val fg = if (selected) pal.onFocus else pal.text
    Row(
        modifier
            .clip(CircleShape)
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
            Box(Modifier.size(8.dp))
        }
        Text(text, color = fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
    }
}

/** Round icon button used in the reader's control row. */
@Composable
fun RoundButton(
    icon: ImageVector,
    label: String,
    size: Dp = 48.dp,
    selected: Boolean = false,
    tint: androidx.compose.ui.graphics.Color? = null,
    onClick: () -> Unit,
) {
    val pal = LocalPalette.current
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (selected) pal.text else pal.glass)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = tint ?: (if (selected) pal.onFocus else pal.text),
            modifier = Modifier.size(size * 0.46f))
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    val pal = LocalPalette.current
    Text(text, color = pal.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = modifier)
}

@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val pal = LocalPalette.current
    Box(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(pal.glass)
            .border(1.dp, pal.text.copy(alpha = 0.12f), RoundedCornerShape(20.dp))
            .padding(20.dp)
    ) { content() }
}
