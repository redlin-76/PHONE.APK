package com.example.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ai.AiMode
import com.example.ui.theme.*
import kotlin.math.sin

@Composable
fun CyberCard(
    modifier: Modifier = Modifier,
    borderWidth: Dp = 1.dp,
    borderColor: Color = GridLine,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CyberCard)
            .border(borderWidth, borderColor, RoundedCornerShape(12.dp))
            .padding(16.dp),
        content = content
    )
}

@Composable
fun GlowBadge(
    text: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.15f))
            .border(1.dp, color, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(RoundedCornerShape(50))
                    .background(color)
            )
            Text(
                text = text,
                color = color,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
fun NeonButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = NeonCyan,
    enabled: Boolean = true,
    testTag: String = ""
) {
    val alpha = if (enabled) 1.0f else 0.4f
    Box(
        modifier = modifier
            .testTag(testTag)
            .clickable(enabled = enabled) { onClick() }
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.12f * alpha))
            .border(1.2.dp, color.copy(alpha = alpha), RoundedCornerShape(8.dp))
            .height(48.dp) // Touch targets must be 48dp+
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = color.copy(alpha = alpha),
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.sp
        )
    }
}

@Composable
fun AudioWavesVisualizer(
    isActive: Boolean,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "audio_ripple")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    val amplitudeMultiplier by animateFloatAsState(
        targetValue = if (isActive) 1.2f else 0.15f,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "amplitude"
    )

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(72.dp)
            .background(Color.Black.copy(alpha = 0.2f))
    ) {
        val width = size.width
        val height = size.height
        val centerY = height / 2

        // Draw multiple glowing lines representing overlapping sine waves (OpenAI / Siri style)
        val cyans = listOf(
            NeonCyan,
            NeonTeal,
            NeonMagenta.copy(alpha = 0.6f)
        )

        for (i in cyans.indices) {
            val path = Path()
            val color = cyans[i]
            val waveFreq = 0.015f + (i * 0.005f)
            val baseAmplitude = 25f * amplitudeMultiplier * (1f - (i * 0.2f))

            path.moveTo(0f, centerY)
            for (x in 0..width.toInt() step 5) {
                val sineValue = sin(x * waveFreq + phase + (i * 1.5))
                val y = centerY + (sineValue * baseAmplitude).toFloat()
                path.lineTo(x.toFloat(), y)
            }

            drawPath(
                path = path,
                color = color,
                style = Stroke(width = if (i == 0) 2.5f else 1.5f)
            )
        }
    }
}

@Composable
fun ModeTogglePanel(
    currentMode: AiMode,
    onModeSelected: (AiMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(GridLine)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        val modes = listOf(
            AiMode.OFF to "OFF (真人)",
            AiMode.AUTO to "AUTO (智慧)",
            AiMode.FULL_AI to "FULL AI"
        )

        modes.forEach { (mode, label) ->
            val isSelected = currentMode == mode
            val targetBg = if (isSelected) {
                when (mode) {
                    AiMode.OFF -> SilentGray
                    AiMode.AUTO -> NeonTeal
                    AiMode.FULL_AI -> NeonCyan
                }
            } else Color.Transparent

            val targetText = if (isSelected) Color.Black else Color.White

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(targetBg)
                    .clickable { onModeSelected(mode) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    color = targetText,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}
