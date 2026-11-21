package com.avatarmocap.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Paleta violeta de la app. */
object Ui {
    val Bg1 = Color(0xFF140C26)
    val Bg2 = Color(0xFF24163F)
    val Card = Color(0xFF231845)
    val CardBorder = Color(0xFF3E2D6B)
    val Accent = Color(0xFFA685FF)
    val AccentDark = Color(0xFF6C4BD6)
    val Text = Color(0xFFF1ECFF)
    val Muted = Color(0xFFB3A6D9)
    val Track = Color(0xFF3A2C5E)
    val Good = Color(0xFF5BE38F)
    val Bad = Color(0xFFFF6B8A)

    val background = Brush.verticalGradient(listOf(Bg1, Bg2, Bg1))
    val accentBrush = Brush.horizontalGradient(listOf(AccentDark, Accent))

    val colors = darkColorScheme(
        primary = Accent,
        onPrimary = Color(0xFF1A0F33),
        primaryContainer = Color(0xFF3A2670),
        onPrimaryContainer = Text,
        secondary = Muted,
        background = Bg1,
        surface = Card,
        onSurface = Text,
        onBackground = Text,
        surfaceVariant = Color(0xFF2E2156),
        onSurfaceVariant = Muted,
        outline = CardBorder,
    )
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Ui.colors, content = content)
}

/** Tarjeta con borde suave, estilo del diseño. */
@Composable
fun PurpleCard(title: String? = null, icon: String = "☆", modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Ui.Card)
            .border(BorderStroke(1.dp, Ui.CardBorder), RoundedCornerShape(18.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (title != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(icon, color = Ui.Accent, fontSize = 18.sp)
                Text(title, color = Ui.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        content()
    }
}

/** Barra de progreso violeta redondeada. */
@Composable
fun PurpleBar(value: Float, modifier: Modifier = Modifier) {
    Box(modifier.height(10.dp).clip(RoundedCornerShape(5.dp)).background(Ui.Track)) {
        Box(
            Modifier.fillMaxWidth(value.coerceIn(0f, 1f)).height(10.dp)
                .clip(RoundedCornerShape(5.dp)).background(Ui.accentBrush)
        )
    }
}
