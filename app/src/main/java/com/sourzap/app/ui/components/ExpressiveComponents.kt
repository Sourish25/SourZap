package com.sourzap.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.StackedLineChart
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material.icons.rounded.VpnLock
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sourzap.app.ui.theme.NumberDisplayLarge
import com.sourzap.app.ui.theme.NumberDisplayMedium
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Large Smartphone Hero Connect Control with Tactile Haptics and Vector Icon
 * Generous 174dp touch target with bold state text and spring physics.
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

    val heroShape = RoundedCornerShape(38.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(174.dp)
            .scale(scaleAnim.value)
            .shadow(
                elevation = if (isConnected) 12.dp else 2.dp,
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
                            scaleAnim.animateTo(0.93f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium))
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
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = if (isConnected) Icons.Rounded.Shield else Icons.Rounded.PowerSettingsNew,
                    contentDescription = null,
                    tint = textColor,
                    modifier = Modifier.size(30.dp)
                )

                Spacer(modifier = Modifier.width(10.dp))

                Text(
                    text = if (isConnected) "ACTIVE" else "DISCONNECTED",
                    fontWeight = FontWeight.Black,
                    fontSize = 30.sp,
                    letterSpacing = 1.sp,
                    color = textColor,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp)
                ) {
                    Icon(
                        imageVector = if (isConnected) Icons.Rounded.VpnLock else Icons.Rounded.VpnKey,
                        contentDescription = null,
                        tint = if (isConnected) MaterialTheme.colorScheme.onPrimary else subtextColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isConnected) "DPI BYPASS RUNNING" else "TAP TO ACTIVATE ZAPRET",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.5.sp,
                        letterSpacing = 0.4.sp,
                        color = if (isConnected) MaterialTheme.colorScheme.onPrimary else subtextColor
                    )
                }
            }
        }
    }
}

/**
 * Thick, Substantive Expressive Pill Chip with Optional Vector Icon (Zero-Clipping)
 */
@Composable
fun ExpressiveChip(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    backgroundColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    textColor: Color = MaterialTheme.colorScheme.onSecondaryContainer
) {
    val pillShape = RoundedCornerShape(20.dp)
    Surface(
        modifier = modifier.clip(pillShape),
        shape = pillShape,
        color = backgroundColor
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = textColor,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(5.dp))
            }
            Text(
                text = text,
                fontWeight = FontWeight.Black,
                fontSize = 12.sp,
                letterSpacing = 0.3.sp,
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
 * Diagnostic Metric Tile with Material Vector Icon Badge, high-contrast typography, and spring physics
 */
@Composable
fun ExpressiveMetricTile(
    title: String,
    value: String,
    unit: String? = null,
    icon: ImageVector,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    backgroundColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    borderColor: Color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
    onClick: (() -> Unit)? = null
) {
    val haptics = LocalHapticFeedback.current
    val tileShape = RoundedCornerShape(24.dp)

    val clickableModifier = if (onClick != null) {
        Modifier.clickable {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        }
    } else Modifier

    Surface(
        modifier = modifier
            .border(1.dp, borderColor, tileShape)
            .clip(tileShape)
            .then(clickableModifier),
        shape = tileShape,
        color = backgroundColor
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.5.sp,
                    letterSpacing = 0.6.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.width(6.dp))

                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(iconTint.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = value,
                    style = NumberDisplayMedium.copy(fontSize = 24.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (unit != null) {
                    Text(
                        text = unit,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 3.dp)
                    )
                }
            }

            if (subtitle != null) {
                Text(
                    text = subtitle,
                    fontWeight = FontWeight.Medium,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Large Speedometer Arc Gauge with Material Vector Icon and Big Typography
 */
@Composable
fun ExpressiveSpeedGauge(
    speedMbps: Float,
    statusText: String,
    modifier: Modifier = Modifier
) {
    val animatedSpeed by animateFloatAsState(
        targetValue = speedMbps,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 220f),
        label = "SpeedNeedle"
    )

    val primaryColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariantColor = MaterialTheme.colorScheme.onSurfaceVariant

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(245.dp),
        contentAlignment = Alignment.Center
    ) {
        val sizePx = minOf(maxWidth.value, maxHeight.value).dp

        Canvas(
            modifier = Modifier.size(sizePx * 0.88f)
        ) {
            val strokeWidth = 20.dp.toPx()
            val diameter = size.minDimension - strokeWidth
            val radius = diameter / 2f
            val center = Offset(size.width / 2f, size.height / 2f + 12.dp.toPx())

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

            // Tick Marks along circumference
            val totalTicks = 9
            val tickStepAngle = sweepTotal / (totalTicks - 1)
            for (t in 0 until totalTicks) {
                val tickAngleDeg = startAngle + t * tickStepAngle
                val tickAngleRad = tickAngleDeg * (PI / 180f)
                val innerR = radius - 14.dp.toPx()
                val outerR = radius - 20.dp.toPx()
                val p1 = Offset(center.x + innerR * cos(tickAngleRad).toFloat(), center.y + innerR * sin(tickAngleRad).toFloat())
                val p2 = Offset(center.x + outerR * cos(tickAngleRad).toFloat(), center.y + outerR * sin(tickAngleRad).toFloat())
                drawLine(
                    color = onSurfaceVariantColor.copy(alpha = 0.35f),
                    start = p1,
                    end = p2,
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }

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
                radius = 8.5.dp.toPx(),
                center = needleTip
            )
            drawCircle(
                color = primaryColor,
                radius = 5.dp.toPx(),
                center = needleTip
            )
        }

        // Center Digital Display
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(top = 18.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Speed,
                    contentDescription = null,
                    tint = primaryColor,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Mbps",
                    fontWeight = FontWeight.Black,
                    fontSize = 17.sp,
                    color = primaryColor
                )
            }

            Text(
                text = String.format("%.1f", animatedSpeed),
                style = NumberDisplayLarge.copy(fontSize = 54.sp),
                color = onSurfaceColor
            )

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = statusText,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                color = onSurfaceVariantColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
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
 * Thick, Ergonomic Smartphone Segmented Pill Switch (62dp height)
 * Generous touch targets, thick rounded pill shape, and zero text cutoff.
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
    val outerShape = RoundedCornerShape(32.dp)
    val innerShape = RoundedCornerShape(26.dp)

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = outerShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEach { item ->
                val isSelected = item == selectedItem
                val bgColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
                val textColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                        .clip(innerShape)
                        .background(bgColor)
                        .clickable {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onItemSelected(item)
                        }
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = itemLabel(item),
                        fontWeight = if (isSelected) FontWeight.Black else FontWeight.Bold,
                        fontSize = 13.5.sp,
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
 * Material You Expressive Wavy Progress Indicator.
 * Renders an organic, undulating sine-wave progress bar with animated phase shifting.
 */
@Composable
fun ExpressiveWavyProgressIndicator(
    progress: Float? = null,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    strokeWidth: Dp = 4.dp,
    waveAmplitude: Dp = 3.dp,
    wavePeriod: Dp = 22.dp
) {
    val density = LocalDensity.current
    val strokePx = with(density) { strokeWidth.toPx() }
    val ampPx = with(density) { waveAmplitude.toPx() }
    val periodPx = with(density) { wavePeriod.toPx() }

    val infiniteTransition = rememberInfiniteTransition(label = "WavyProgressTransition")
    val animatedPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 6.2831855f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing)
        ),
        label = "WavyPhase"
    )

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(waveAmplitude * 2 + strokeWidth + 4.dp)
    ) {
        val w = size.width
        val h = size.height
        val centerY = h * 0.5f

        val isDeterminate = (progress != null)
        val clampedProgress = if (progress != null) progress.coerceIn(0f, 1f) else 1f
        val activeWidth = if (isDeterminate) w * clampedProgress else w

        // 1. Background Track
        if (isDeterminate && activeWidth < w) {
            drawLine(
                color = trackColor,
                start = Offset(activeWidth, centerY),
                end = Offset(w, centerY),
                strokeWidth = strokePx,
                cap = StrokeCap.Round
            )
        }

        // 2. Active Animated Sine Wave Path
        if (activeWidth > 0f) {
            val wavePath = Path()
            val totalSteps = (activeWidth / 3f).toInt().coerceAtLeast(1)

            for (i in 0..totalSteps) {
                val currentX = (i.toFloat() * 3f).coerceAtMost(activeWidth)
                val phaseRatio = if (periodPx > 0.01f) (currentX / periodPx).toDouble() else 0.0
                val rad = (phaseRatio * 2.0 * Math.PI) - animatedPhase.toDouble()
                val waveOffset = (ampPx.toDouble() * Math.sin(rad)).toFloat()
                val currentY = centerY + waveOffset

                if (i == 0) {
                    wavePath.moveTo(currentX, currentY)
                } else {
                    wavePath.lineTo(currentX, currentY)
                }
            }

            drawPath(
                path = wavePath,
                color = color,
                style = Stroke(
                    width = strokePx,
                    cap = StrokeCap.Round
                )
            )
        }
    }
}

/**
 * Thick, Floating Navigation Dock Tailored for Smartphone Thumbs with Material Vector Icons
 */
@Composable
fun FloatingExpressiveDock(
    currentRoute: String,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current
    val dockShape = RoundedCornerShape(34.dp)
    val itemShape = RoundedCornerShape(26.dp)

    val items = listOf(
        DockItem("dashboard", "Home", Icons.Rounded.Home),
        DockItem("speedtest", "Speed", Icons.Rounded.Speed),
        DockItem("traffic", "Traffic", Icons.Rounded.StackedLineChart),
        DockItem("settings", "Settings", Icons.Rounded.Settings)
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = dockShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.98f),
            shadowElevation = 10.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
            modifier = Modifier
                .fillMaxWidth()
                .height(68.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
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
                            .height(52.dp)
                            .clip(itemShape)
                            .background(pillBg)
                            .clickable {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onNavigate(item.route)
                            }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = item.label,
                                tint = itemColor,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = item.label,
                                fontWeight = if (isSelected) FontWeight.Black else FontWeight.Bold,
                                fontSize = 11.sp,
                                letterSpacing = (-0.1).sp,
                                color = itemColor,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Material 3 Expressive Confirmation Dialog with tactile haptic buttons and vector icon
 */
@Composable
fun ExpressiveConfirmationDialog(
    title: String,
    message: String,
    confirmText: String = "Confirm",
    dismissText: String = "Cancel",
    icon: ImageVector = Icons.Rounded.WarningAmber,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val haptics = LocalHapticFeedback.current

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        icon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
        },
        title = {
            Text(
                text = title,
                fontWeight = FontWeight.Black,
                fontSize = 20.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Text(
                text = message,
                fontWeight = FontWeight.Normal,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onConfirm()
                },
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            ) {
                Text(confirmText, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onDismiss()
                },
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(dismissText, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    )
}

data class DockItem(val route: String, val label: String, val icon: ImageVector)