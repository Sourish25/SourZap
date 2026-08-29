package com.sourzap.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.rotate
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sourzap.app.ui.theme.ExpressiveShapes
import com.sourzap.app.ui.theme.NumberDisplayLarge
import com.sourzap.app.ui.theme.ScallopedShape
import com.sourzap.app.ui.theme.WavyCircularShape
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Tactile Material 3 Expressive Hero Connect Button
 * Features smooth spring bouncing, organic breathing rings, and clean dynamic theming.
 */
@Composable
fun HeroConnectButton(
    isConnected: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "HeroBreathing")

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isConnected) 1.12f else 1.03f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isConnected) 1800 else 2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "PulseScale"
    )

    val waveRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(12000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "WaveRotation"
    )

    val scaleAnim = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()

    val primaryColor = MaterialTheme.colorScheme.primary
    val activeColor = MaterialTheme.colorScheme.tertiary
    val onPrimaryColor = MaterialTheme.colorScheme.onPrimary
    val onActiveColor = MaterialTheme.colorScheme.onTertiary

    val targetButtonColor = if (isConnected) activeColor else primaryColor
    val targetTextColor = if (isConnected) onActiveColor else onPrimaryColor

    val buttonBgColor by animateColorAsState(
        targetValue = targetButtonColor,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "ButtonColor"
    )

    val textColor by animateColorAsState(
        targetValue = targetTextColor,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "TextColor"
    )

    Box(
        modifier = modifier.size(210.dp),
        contentAlignment = Alignment.Center
    ) {
        // Breathing Concentric Aura Rings
        if (isConnected) {
            Box(
                modifier = Modifier
                    .size(200.dp)
                    .scale(pulseScale)
                    .rotate(waveRotation)
                    .clip(WavyCircularShape(numWaves = 12, waveAmplitudePx = 8f))
                    .background(activeColor.copy(alpha = 0.18f))
            )

            Box(
                modifier = Modifier
                    .size(176.dp)
                    .rotate(-waveRotation * 1.4f)
                    .clip(WavyCircularShape(numWaves = 10, waveAmplitudePx = 6f))
                    .background(activeColor.copy(alpha = 0.12f))
            )
        } else {
            Box(
                modifier = Modifier
                    .size(185.dp)
                    .scale(pulseScale)
                    .clip(CircleShape)
                    .background(primaryColor.copy(alpha = 0.10f))
            )
        }

        // Tactile Squircle Button
        val buttonShape = if (isConnected) RoundedCornerShape(44.dp) else RoundedCornerShape(52.dp)

        Box(
            modifier = Modifier
                .size(150.dp)
                .scale(scaleAnim.value)
                .shadow(
                    elevation = if (isConnected) 12.dp else 8.dp,
                    shape = buttonShape,
                    spotColor = buttonBgColor,
                    ambientColor = buttonBgColor
                )
                .clip(buttonShape)
                .background(buttonBgColor)
                .pointerInput(isConnected) {
                    detectTapGestures(
                        onPress = {
                            scope.launch {
                                scaleAnim.animateTo(0.90f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium))
                            }
                            tryAwaitRelease()
                            scope.launch {
                                scaleAnim.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessLow))
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
                modifier = Modifier.padding(12.dp)
            ) {
                Icon(
                    imageVector = if (isConnected) Icons.Rounded.PowerSettingsNew else Icons.Rounded.Bolt,
                    contentDescription = if (isConnected) "Disconnect" else "Connect",
                    tint = textColor,
                    modifier = Modifier.size(48.dp)
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = if (isConnected) "ACTIVE" else "ZAP DPI",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    letterSpacing = 1.sp,
                    color = textColor,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/**
 * Flexible Material 3 Expressive Pill Chip
 * Auto-sizes to fit any technique or status string with clean tonal contrast.
 */
@Composable
fun ExpressiveChip(
    text: String,
    modifier: Modifier = Modifier,
    backgroundColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    textColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    icon: ImageVector? = null
) {
    Surface(
        modifier = modifier.clip(ExpressiveShapes.SuperPill),
        shape = ExpressiveShapes.SuperPill,
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
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(
                text = text,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Scalloped Starburst Badge
 * Used strictly for short 2-5 character highlights (e.g. "4x", "LIVE", "TURBO").
 */
@Composable
fun ScallopedBadge(
    text: String,
    modifier: Modifier = Modifier,
    backgroundColor: Color = MaterialTheme.colorScheme.tertiaryContainer,
    textColor: Color = MaterialTheme.colorScheme.onTertiaryContainer,
    numPetals: Int = 12
) {
    Box(
        modifier = modifier
            .clip(ScallopedShape(numPetals = numPetals, petalDepthRatio = 0.14f))
            .background(backgroundColor)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontWeight = FontWeight.Black,
            fontSize = 11.sp,
            letterSpacing = 0.6.sp,
            color = textColor,
            maxLines = 1
        )
    }
}

/**
 * Material 3 Expressive Container Card
 */
@Composable
fun ExpressiveCard(
    modifier: Modifier = Modifier,
    backgroundColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    borderColor: Color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    shape: Shape = ExpressiveShapes.AsymmetricPillLarge,
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
 * Speedometer Arc Gauge
 */
@Composable
fun ExpressiveSpeedGauge(
    speedMbps: Float,
    pingMs: Float,
    jitterMs: Float,
    isTesting: Boolean,
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
            .height(250.dp),
        contentAlignment = Alignment.Center
    ) {
        val sizePx = minOf(maxWidth.value, maxHeight.value).dp

        Canvas(
            modifier = Modifier.size(sizePx * 0.85f)
        ) {
            val strokeWidth = 18.dp.toPx()
            val diameter = size.minDimension - strokeWidth
            val radius = diameter / 2f
            val center = Offset(size.width / 2f, size.height / 2f + 8.dp.toPx())

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
                radius = 8.dp.toPx(),
                center = needleTip
            )
        }

        // Center Digital Display
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.offset(y = 8.dp)
        ) {
            Text(
                text = String.format("%.1f", animatedSpeed),
                style = NumberDisplayLarge,
                color = onSurfaceColor
            )

            Text(
                text = "Mbps",
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = primaryColor
            )

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = statusText,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
                color = onSurfaceVariantColor
            )
        }
    }
}

/**
 * Animated Traffic Waveform
 */
@Composable
fun ExpressiveTrafficWave(
    speedHistory: List<Float>,
    modifier: Modifier = Modifier,
    lineColor: Color = MaterialTheme.colorScheme.primary,
    fillColor: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(65.dp)
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
            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
        )
    }
}

/**
 * Segmented Pill Switch
 */
@Composable
fun <T> SegmentedPillSwitch(
    items: List<T>,
    selectedItem: T,
    itemLabel: (T) -> String,
    onItemSelected: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = ExpressiveShapes.SuperPill,
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items.forEach { item ->
                val isSelected = item == selectedItem
                val bgColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                val textColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(ExpressiveShapes.SuperPill)
                        .background(bgColor)
                        .clickable { onItemSelected(item) }
                        .padding(vertical = 10.dp, horizontal = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = itemLabel(item),
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 13.sp,
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
 * Floating Navigation Dock
 */
@Composable
fun FloatingExpressiveDock(
    currentRoute: String,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val items = listOf(
        DockItem("dashboard", "Home", Icons.Rounded.Home),
        DockItem("speedtest", "Speed", Icons.Rounded.Speed),
        DockItem("strategies", "Bypass", Icons.Rounded.Tune),
        DockItem("traffic", "Traffic", Icons.Rounded.BarChart),
        DockItem("settings", "Settings", Icons.Rounded.Settings)
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = ExpressiveShapes.SuperPill,
            color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.96f),
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
                    val animatedScale by animateFloatAsState(
                        targetValue = if (isSelected) 1.04f else 1f,
                        animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
                        label = "DockScale"
                    )

                    val pillBg = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
                    val itemColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant

                    Box(
                        modifier = Modifier
                            .scale(animatedScale)
                            .clip(ExpressiveShapes.SuperPill)
                            .background(pillBg)
                            .clickable { onNavigate(item.route) }
                            .padding(horizontal = if (isSelected) 14.dp else 10.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = item.label,
                                tint = itemColor,
                                modifier = Modifier.size(20.dp)
                            )

                            if (isSelected) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = item.label,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = itemColor
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

data class DockItem(val route: String, val label: String, val icon: ImageVector)