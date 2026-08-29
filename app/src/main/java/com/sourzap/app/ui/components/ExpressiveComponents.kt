package com.sourzap.app.ui.components

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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sourzap.app.ui.theme.CandyCoral
import com.sourzap.app.ui.theme.CyanSpark
import com.sourzap.app.ui.theme.DarkBackground
import com.sourzap.app.ui.theme.DarkSurface
import com.sourzap.app.ui.theme.DarkSurfaceContainer
import com.sourzap.app.ui.theme.DarkSurfaceContainerHigh
import com.sourzap.app.ui.theme.DarkSurfaceContainerHighest
import com.sourzap.app.ui.theme.ElectricViolet
import com.sourzap.app.ui.theme.ElectricVioletLight
import com.sourzap.app.ui.theme.ExpressiveShapes
import com.sourzap.app.ui.theme.NeonMint
import com.sourzap.app.ui.theme.NeonMintLight
import com.sourzap.app.ui.theme.NumberDisplayLarge
import com.sourzap.app.ui.theme.NumberDisplayMedium
import com.sourzap.app.ui.theme.NumberDisplaySmall
import com.sourzap.app.ui.theme.ScallopedShape
import com.sourzap.app.ui.theme.SunbeamYellow
import com.sourzap.app.ui.theme.TextPrimary
import com.sourzap.app.ui.theme.TextSecondary
import com.sourzap.app.ui.theme.TextTertiary
import com.sourzap.app.ui.theme.WavyCircularShape
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Massive Tactile Hero Connect Toggle (180dp x 180dp)
 * Features morphing geometry, organic spring breathing rings, and playful tactile feedback
 */
@Composable
fun HeroConnectButton(
    isConnected: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "HeroBreathing")

    // Breathing pulse animations when connected
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isConnected) 1.14f else 1.02f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isConnected) 1600 else 2400, easing = FastOutSlowInEasing),
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

    Box(
        modifier = modifier
            .size(230.dp),
        contentAlignment = Alignment.Center
    ) {
        // Outer Organic Glowing Rings
        if (isConnected) {
            // Concentric Wave Ring 1
            Box(
                modifier = Modifier
                    .size(220.dp)
                    .scale(pulseScale)
                    .rotate(waveRotation)
                    .clip(WavyCircularShape(numWaves = 16, waveAmplitudePx = 10f))
                    .background(
                        Brush.radialGradient(
                            listOf(
                                NeonMint.copy(alpha = 0.25f),
                                Color.Transparent
                            )
                        )
                    )
            )

            // Concentric Wave Ring 2
            Box(
                modifier = Modifier
                    .size(195.dp)
                    .rotate(-waveRotation * 1.5f)
                    .clip(WavyCircularShape(numWaves = 12, waveAmplitudePx = 8f))
                    .background(NeonMint.copy(alpha = 0.15f))
            )
        } else {
            // Soft Idle Aura
            Box(
                modifier = Modifier
                    .size(200.dp)
                    .scale(pulseScale)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(
                                ElectricViolet.copy(alpha = 0.15f),
                                Color.Transparent
                            )
                        )
                    )
            )
        }

        // Main Tactile Button
        val buttonShape = if (isConnected) {
            RoundedCornerShape(44.dp)
        } else {
            RoundedCornerShape(52.dp)
        }

        val buttonGradient = if (isConnected) {
            Brush.linearGradient(listOf(NeonMint, Color(0xFF00B377)))
        } else {
            Brush.linearGradient(listOf(ElectricViolet, Color(0xFF6314DE)))
        }

        Box(
            modifier = Modifier
                .size(160.dp)
                .scale(scaleAnim.value)
                .shadow(
                    elevation = if (isConnected) 24.dp else 16.dp,
                    shape = buttonShape,
                    spotColor = if (isConnected) NeonMint else ElectricViolet,
                    ambientColor = if (isConnected) NeonMint else ElectricViolet
                )
                .clip(buttonShape)
                .background(buttonGradient)
                .pointerInput(isConnected) {
                    detectTapGestures(
                        onPress = {
                            scope.launch {
                                scaleAnim.animateTo(0.88f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium))
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
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = if (isConnected) Icons.Rounded.PowerSettingsNew else Icons.Rounded.FlashOn,
                    contentDescription = if (isConnected) "Disconnect" else "Connect",
                    tint = if (isConnected) DarkBackground else TextPrimary,
                    modifier = Modifier.size(54.dp)
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = if (isConnected) "ACTIVE" else "ZAP DPI",
                    fontWeight = FontWeight.Black,
                    fontSize = 17.sp,
                    letterSpacing = 1.2.sp,
                    color = if (isConnected) DarkBackground else TextPrimary
                )
            }
        }
    }
}

/**
 * Scalloped Starburst Badge (e.g. "⚡ 4x FASTER", "🛡️ DPI PROTECTED", "🚀 TURBO")
 * Direct tribute to Google M3 Expressive UX Research starburst components!
 */
@Composable
fun ScallopedBadge(
    text: String,
    modifier: Modifier = Modifier,
    backgroundColor: Color = CandyCoral,
    textColor: Color = TextPrimary,
    numPetals: Int = 12
) {
    Box(
        modifier = modifier
            .clip(ScallopedShape(numPetals = numPetals, petalDepthRatio = 0.16f))
            .background(backgroundColor)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontWeight = FontWeight.Black,
            fontSize = 11.sp,
            letterSpacing = 0.8.sp,
            color = textColor
        )
    }
}

/**
 * Expressive Chunky Card Container with Asymmetric Corner Radii & Neon Glow
 */
@Composable
fun ExpressiveCard(
    modifier: Modifier = Modifier,
    backgroundColor: Color = DarkSurfaceContainer,
    borderColor: Color = DarkSurfaceContainerHighest,
    shape: RoundedCornerShape = ExpressiveShapes.AsymmetricPillLarge,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier
            .border(1.5.dp, borderColor, shape)
            .clip(shape),
        color = backgroundColor,
        shape = shape
    ) {
        content()
    }
}

/**
 * Crazy Expressive Speedometer Arc Gauge with Spring Needle & Digital Readout
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
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f),
        label = "SpeedNeedle"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "GaugeWave")
    val waveOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "GaugeWaveRotation"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(280.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .size(240.dp)
        ) {
            val strokeWidth = 22.dp.toPx()
            val diameter = size.minDimension - strokeWidth
            val radius = diameter / 2f
            val center = Offset(size.width / 2f, size.height / 2f + 10.dp.toPx())

            // Background Track Arc (240 degrees from 150° to 390°)
            val startAngle = 150f
            val sweepTotal = 240f

            drawArc(
                color = Color(0xFF262238),
                startAngle = startAngle,
                sweepAngle = sweepTotal,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(diameter, diameter),
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )

            // Dynamic Gradient Sweep for Active Speed (max 300 Mbps scale)
            val currentFraction = (animatedSpeed / 150f).coerceIn(0.01f, 1f)
            val activeSweep = sweepTotal * currentFraction

            val arcGradient = Brush.sweepGradient(
                colors = listOf(CyanSpark, ElectricViolet, CandyCoral, SunbeamYellow, CyanSpark),
                center = center
            )

            drawArc(
                brush = arcGradient,
                startAngle = startAngle,
                sweepAngle = activeSweep,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(diameter, diameter),
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )

            // Neon Needle Indicator
            val needleAngleRad = (startAngle + activeSweep) * (PI / 180f)
            val needleDistance = radius
            val needleTip = Offset(
                center.x + needleDistance * cos(needleAngleRad).toFloat(),
                center.y + needleDistance * sin(needleAngleRad).toFloat()
            )

            drawCircle(
                color = Color.White,
                radius = 12.dp.toPx(),
                center = needleTip
            )
            drawCircle(
                color = if (animatedSpeed > 50f) CandyCoral else CyanSpark,
                radius = 7.dp.toPx(),
                center = needleTip
            )
        }

        // Center Digital Numeric Display
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.offset(y = 12.dp)
        ) {
            Text(
                text = String.format("%.1f", animatedSpeed),
                style = NumberDisplayLarge,
                color = TextPrimary
            )

            Text(
                text = "Mbps",
                fontWeight = FontWeight.ExtraBold,
                fontSize = 16.sp,
                letterSpacing = 1.sp,
                color = CyanSpark
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = statusText,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                color = if (isTesting) SunbeamYellow else TextSecondary
            )
        }
    }
}

/**
 * Real-Time Animated Smooth Traffic Waveform Canvas
 */
@Composable
fun ExpressiveTrafficWave(
    speedHistory: List<Float>,
    modifier: Modifier = Modifier,
    lineColor: Color = NeonMint,
    fillColor: Color = NeonMint.copy(alpha = 0.2f)
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(70.dp)
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
            val y = normalizedY.coerceIn(4f, h - 4f)

            if (i == 0) {
                path.moveTo(x, y)
                fillPath.lineTo(x, y)
            } else {
                val prevX = (i - 1) * stepX
                val prevNormalizedY = h - (speedHistory[i - 1] / maxVal) * (h * 0.85f)
                val prevY = prevNormalizedY.coerceIn(4f, h - 4f)

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
 * Chunky Segmented Pill Switch (Like the photo editor and audio controls in Google Expressive UI)
 */
@Composable
fun <T> SegmentedPillSwitch(
    items: List<T>,
    selectedItem: T,
    itemLabel: (T) -> String,
    onItemSelected: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(ExpressiveShapes.SuperPill)
            .background(DarkSurfaceContainerHigh)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items.forEach { item ->
            val isSelected = item == selectedItem
            val bgGradient = if (isSelected) {
                Brush.linearGradient(listOf(ElectricViolet, Color(0xFF6B17EB)))
            } else {
                Brush.linearGradient(listOf(Color.Transparent, Color.Transparent))
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(ExpressiveShapes.SuperPill)
                    .background(bgGradient)
                    .clickable { onItemSelected(item) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = itemLabel(item),
                    fontWeight = if (isSelected) FontWeight.Black else FontWeight.Bold,
                    fontSize = 13.sp,
                    color = if (isSelected) TextPrimary else TextSecondary,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/**
 * Floating Expressive Bottom Navigation Dock with Bouncy Active Indicators
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
            .padding(horizontal = 20.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = ExpressiveShapes.AsymmetricPillLarge,
            color = DarkSurfaceContainerHigh.copy(alpha = 0.96f),
            shadowElevation = 18.dp,
            border = androidx.compose.foundation.BorderStroke(1.5.dp, DarkSurfaceContainerHighest),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                items.forEach { item ->
                    val isSelected = currentRoute == item.route
                    val animatedScale by animateFloatAsState(
                        targetValue = if (isSelected) 1.08f else 1f,
                        animationSpec = spring(dampingRatio = 0.5f, stiffness = 400f),
                        label = "DockScale"
                    )

                    val pillBg = if (isSelected) ElectricViolet else Color.Transparent

                    Box(
                        modifier = Modifier
                            .scale(animatedScale)
                            .clip(ExpressiveShapes.SuperPill)
                            .background(pillBg)
                            .clickable { onNavigate(item.route) }
                            .padding(horizontal = if (isSelected) 16.dp else 12.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = item.label,
                                tint = if (isSelected) TextPrimary else TextTertiary,
                                modifier = Modifier.size(22.dp)
                            )

                            if (isSelected) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = item.label,
                                    fontWeight = FontWeight.Black,
                                    fontSize = 12.sp,
                                    color = TextPrimary
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