package com.sourzap.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sourzap.app.ui.theme.ExpressiveShapes
import com.sourzap.app.ui.theme.NumberDisplayLarge
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Large Smartphone-Tailored Hero Connect Control with Tactile Haptics
 */
@Composable
fun HeroConnectButton(
    isConnected: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scaleAnim = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    val targetContainerColor = if (isConnected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest
    val targetBorderColor = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    val targetTextColor = if (isConnected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    val targetSubtextColor = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant

    val containerColor by animateColorAsState(
        targetValue = targetContainerColor,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "HeroBg"
    )

    val borderColor by animateColorAsState(
        targetValue = targetBorderColor,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "HeroBorder"
    )

    val textColor by animateColorAsState(
        targetValue = targetTextColor,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "HeroText"
    )

    val subtextColor by animateColorAsState(
        targetValue = targetSubtextColor,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "HeroSubtext"
    )

    val heroShape = RoundedCornerShape(36.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(160.dp)
            .scale(scaleAnim.value)
            .shadow(
                elevation = if (isConnected) 8.dp else 2.dp,
                shape = heroShape,
                spotColor = containerColor,
                ambientColor = containerColor
            )
            .clip(heroShape)
            .border(2.dp, borderColor, heroShape)
            .background(containerColor)
            .pointerInput(isConnected) {
                detectTapGestures(
                    onPress = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        scope.launch {
                            scaleAnim.animateTo(0.94f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium))
                        }
                        tryAwaitRelease()
                        scope.launch {
                            scaleAnim.animateTo(1f, spring(dampingRatio = 0.50f, stiffness = Spring.StiffnessLow))
                        }
                        onToggle()
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(24.dp)
        ) {
            Text(
                text = if (isConnected) "ACTIVE" else "DISCONNECTED",
                fontWeight = FontWeight.Black,
                fontSize = 32.sp,
                letterSpacing = 1.sp,
                color = textColor,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(6.dp))

            Surface(
                shape = ExpressiveShapes.SuperPill,
                color = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Text(
                    text = if (isConnected) "DPI BYPASS RUNNING" else "TAP TO ACTIVATE ZAPRET",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    letterSpacing = 0.5.sp,
                    color = if (isConnected) MaterialTheme.colorScheme.onPrimary else subtextColor,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }
        }
    }
}

/**
 * Large, Non-Clipping Expressive Pill Chip
 */
@Composable
fun ExpressiveChip(
    text: String,
    modifier: Modifier = Modifier,
    backgroundColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    textColor: Color = MaterialTheme.colorScheme.onSecondaryContainer
) {
    Surface(
        modifier = modifier.clip(ExpressiveShapes.SuperPill),
        shape = ExpressiveShapes.SuperPill,
        color = backgroundColor
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Material 3 Expressive Container Card
 */
@Composable
fun ExpressiveCard(
    modifier: Modifier = Modifier,
    backgroundColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    borderColor: Color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
    shape: Shape = RoundedCornerShape(28.dp),
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier
            .border(1.dp, borderColor, shape)
            .clip(shape),
        color = backgroundColor,
        shape = shape
    ) {
        content()
    }
}

/**
 * Large Speedometer Arc Gauge with Big Typography
 */
@Composable
fun ExpressiveSpeedGauge(
    speedMbps: Float,
    statusText: String,
    modifier: Modifier = Modifier
) {
    val animatedSpeed by animateFloatAsState(
        targetValue = speedMbps,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 250f),
        label = "SpeedNeedle"
    )

    val primaryColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariantColor = MaterialTheme.colorScheme.onSurfaceVariant

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(240.dp),
        contentAlignment = Alignment.Center
    ) {
        val sizePx = minOf(maxWidth.value, maxHeight.value).dp

        Canvas(
            modifier = Modifier.size(sizePx * 0.88f)
        ) {
            val strokeWidth = 20.dp.toPx()
            val diameter = size.minDimension - strokeWidth
            val radius = diameter / 2f
            val center = Offset(size.width / 2f, size.height / 2f + 10.dp.toPx())

            val startAngle = 150f
            val sweepTotal = 240f

            // Track Arc
            drawArc(
                color = trackColor,
                startAngle = startAngle,
                sweepAngle = sweepTotal,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(diameter, diameter),
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )

            // Active Progress Arc
            val currentFraction = (animatedSpeed / 150f).coerceIn(0.01f, 1f)
            val activeSweep = sweepTotal * currentFraction

            drawArc(
                color = primaryColor,
                startAngle = startAngle,
                sweepAngle = activeSweep,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(diameter, diameter),
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )

            // Needle Tip Indicator
            val needleAngleRad = (startAngle + activeSweep) * (PI / 180f)
            val needleTip = Offset(
                center.x + radius * cos(needleAngleRad).toFloat(),
                center.y + radius * sin(needleAngleRad).toFloat()
            )

            drawCircle(
                color = onSurfaceColor,
                radius = 9.dp.toPx(),
                center = needleTip
            )
        }

        // Center Digital Display
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(top = 16.dp)
        ) {
            Text(
                text = String.format("%.1f", animatedSpeed),
                style = NumberDisplayLarge.copy(fontSize = 56.sp),
                color = onSurfaceColor
            )

            Text(
                text = "Mbps",
                fontWeight = FontWeight.Black,
                fontSize = 18.sp,
                color = primaryColor
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = statusText,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                color = onSurfaceVariantColor
            )
        }
    }
}

/**
 * Large Telemetry Waveform
 */
@Composable
fun ExpressiveTrafficWave(
    speedHistory: List<Float>,
    modifier: Modifier = Modifier,
    lineColor: Color = MaterialTheme.colorScheme.primary,
    fillColor: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(75.dp)
    ) {
        if (speedHistory.size < 2) return@Canvas

        val maxVal = (speedHistory.maxOrNull() ?: 10f).coerceAtLeast(10f)
        val w = size.width
        val h = size.height
        val stepX = w / (speedHistory.size - 1)

        val path = Path()
        val fillPath = Path()

        fillPath.moveTo(0f, h)

        for (i in speedHistory.indices) {
            val normalizedY = h - (speedHistory[i] / maxVal) * (h * 0.85f)
            val x = i * stepX
            val y = normalizedY.coerceIn(2f, h - 2f)

            if (i == 0) {
                path.moveTo(x, y)
                fillPath.lineTo(x, y)
            } else {
                val prevX = (i - 1) * stepX
                val prevNormalizedY = h - (speedHistory[i - 1] / maxVal) * (h * 0.85f)
                val prevY = prevNormalizedY.coerceIn(2f, h - 2f)

                val cx = (prevX + x) / 2f
                path.cubicTo(cx, prevY, cx, y, x, y)
                fillPath.cubicTo(cx, prevY, cx, y, x, y)
            }
        }

        fillPath.lineTo(w, h)
        fillPath.close()

        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                listOf(fillColor, Color.Transparent)
            )
        )

        drawPath(
            path = path,
            color = lineColor,
            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
        )
    }
}

/**
 * Large Smartphone Segmented Pill Switch (56dp height) with Haptic Feedback
 */
@Composable
fun <T> SegmentedPillSwitch(
    items: List<T>,
    selectedItem: T,
    itemLabel: (T) -> String,
    onItemSelected: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = ExpressiveShapes.SuperPill,
        color = MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(5.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items.forEach { item ->
                val isSelected = item == selectedItem
                val bgColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
                val textColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .clip(ExpressiveShapes.SuperPill)
                        .background(bgColor)
                        .clickable {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onItemSelected(item)
                        }
                        .padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = itemLabel(item),
                        fontWeight = if (isSelected) FontWeight.Black else FontWeight.SemiBold,
                        fontSize = 14.sp,
                        color = textColor,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * Clean Floating Navigation Dock Tailored for Thumbs with Haptics
 */
@Composable
fun FloatingExpressiveDock(
    currentRoute: String,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current

    val items = listOf(
        DockItem("dashboard", "Home"),
        DockItem("speedtest", "Speed"),
        DockItem("strategies", "Bypass"),
        DockItem("traffic", "Traffic"),
        DockItem("settings", "Settings")
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = ExpressiveShapes.SuperPill,
            color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.98f),
            shadowElevation = 8.dp,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                items.forEach { item ->
                    val isSelected = currentRoute == item.route

                    val pillBg = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
                    val itemColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(ExpressiveShapes.SuperPill)
                            .background(pillBg)
                            .clickable {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onNavigate(item.route)
                            }
                            .padding(horizontal = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = item.label,
                            fontWeight = if (isSelected) FontWeight.Black else FontWeight.Bold,
                            fontSize = 13.sp,
                            color = itemColor,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

data class DockItem(val route: String, val label: String)