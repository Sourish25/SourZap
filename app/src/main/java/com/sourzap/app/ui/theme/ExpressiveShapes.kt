package com.sourzap.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Material 3 Expressive Scalloped / Starburst Shape (12-petal or custom petals)
 * Used for prominent expressive badges, celebration indicators ("4x faster!"), and hero accents.
 */
class ScallopedShape(
    private val numPetals: Int = 12,
    private val petalDepthRatio: Float = 0.12f
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = Path()
        val centerX = size.width / 2f
        val centerY = size.height / 2f
        val maxRadius = minOf(centerX, centerY)
        val minRadius = maxRadius * (1f - petalDepthRatio)

        val totalPoints = numPetals * 2
        val angleStep = (2 * PI / totalPoints).toFloat()

        for (i in 0 until totalPoints) {
            val radius = if (i % 2 == 0) maxRadius else minRadius
            val angle = i * angleStep - (PI / 2).toFloat()
            val x = centerX + radius * cos(angle)
            val y = centerY + radius * sin(angle)

            if (i == 0) {
                path.moveTo(x, y)
            } else {
                // Bezier curved petal transition
                val prevAngle = (i - 1) * angleStep - (PI / 2).toFloat()
                val prevRadius = if ((i - 1) % 2 == 0) maxRadius else minRadius
                val prevX = centerX + prevRadius * cos(prevAngle)
                val prevY = centerY + prevRadius * sin(prevAngle)

                val midAngle = (prevAngle + angle) / 2f
                val midRadius = (prevRadius + radius) / 2f * 1.04f
                val cx = centerX + midRadius * cos(midAngle)
                val cy = centerY + midRadius * sin(midAngle)

                path.quadraticTo(cx, cy, x, y)
            }
        }
        path.close()
        return Outline.Generic(path)
    }
}

/**
 * Wavy Border Shape for Organic Breathing Rings & Audio/Traffic Dials
 */
class WavyCircularShape(
    private val numWaves: Int = 14,
    private val waveAmplitudePx: Float = 12f
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = Path()
        val cx = size.width / 2f
        val cy = size.height / 2f
        val baseRadius = minOf(cx, cy) - waveAmplitudePx

        val steps = numWaves * 12
        val stepAngle = (2 * PI / steps).toFloat()

        for (i in 0..steps) {
            val angle = i * stepAngle
            val wave = sin(i.toFloat() / steps * numWaves * 2 * PI).toFloat()
            val r = baseRadius + wave * waveAmplitudePx
            val x = cx + r * cos(angle)
            val y = cy + r * sin(angle)

            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return Outline.Generic(path)
    }
}

object ExpressiveShapes {
    // Asymmetric Playful Containers (from M3 Expressive research)
    val AsymmetricPillLarge = RoundedCornerShape(
        topStart = 38.dp,
        topEnd = 16.dp,
        bottomEnd = 38.dp,
        bottomStart = 16.dp
    )

    val AsymmetricPillInverse = RoundedCornerShape(
        topStart = 16.dp,
        topEnd = 38.dp,
        bottomEnd = 16.dp,
        bottomStart = 38.dp
    )

    val ChunkyCard = RoundedCornerShape(28.dp)
    val ChunkyCardLarge = RoundedCornerShape(36.dp)
    val SuperPill = RoundedCornerShape(percent = 50)
    val Squircle = RoundedCornerShape(24.dp)
    val SmallPill = RoundedCornerShape(12.dp)
}