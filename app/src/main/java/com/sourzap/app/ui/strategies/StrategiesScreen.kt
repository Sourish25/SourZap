package com.sourzap.app.ui.strategies

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sourzap.app.SourZapApp
import com.sourzap.app.data.model.BypassStrategy
import com.sourzap.app.data.model.DohProvider
import com.sourzap.app.ui.components.ExpressiveCard
import com.sourzap.app.ui.components.ScallopedBadge
import com.sourzap.app.ui.components.SegmentedPillSwitch
import com.sourzap.app.ui.theme.CandyCoral
import com.sourzap.app.ui.theme.CandyCoralContainer
import com.sourzap.app.ui.theme.ElectricVioletContainer
import com.sourzap.app.ui.theme.CyanSpark
import com.sourzap.app.ui.theme.DarkBackground
import com.sourzap.app.ui.theme.DarkSurfaceContainer
import com.sourzap.app.ui.theme.DarkSurfaceContainerHigh
import com.sourzap.app.ui.theme.DarkSurfaceContainerHighest
import com.sourzap.app.ui.theme.ElectricViolet
import com.sourzap.app.ui.theme.ElectricVioletLight
import com.sourzap.app.ui.theme.ExpressiveShapes
import com.sourzap.app.ui.theme.NeonMint
import com.sourzap.app.ui.theme.SunbeamYellow
import com.sourzap.app.ui.theme.TextPrimary
import com.sourzap.app.ui.theme.TextSecondary
import com.sourzap.app.ui.theme.TextTertiary

@Composable
fun StrategiesScreen(
    modifier: Modifier = Modifier
) {
    val app = SourZapApp.instance
    val strategyRepo = app.strategyRepository

    val selectedStrategy by strategyRepo.currentStrategy.collectAsState()
    val customStrategy by strategyRepo.customStrategy.collectAsState()

    var editingCustom by remember { mutableStateOf(false) }

    // Custom configuration states
    var customName by remember(customStrategy) { mutableStateOf(customStrategy.name) }
    var splitOffset by remember(customStrategy) { mutableIntStateOf(customStrategy.tlsSplitOffset) }
    var useMultisplit by remember(customStrategy) { mutableStateOf(customStrategy.useMultisplit) }
    var fakeSni by remember(customStrategy) { mutableStateOf(customStrategy.fakeSni) }
    var fakeTtl by remember(customStrategy) { mutableFloatStateOf(customStrategy.fakeTtl.toFloat()) }
    var useDisorder by remember(customStrategy) { mutableStateOf(customStrategy.useDisorder) }
    var httpHostMod by remember(customStrategy) { mutableStateOf(customStrategy.httpHostMod) }
    var blockQuic by remember(customStrategy) { mutableStateOf(customStrategy.blockQuic) }
    var dohProvider by remember(customStrategy) { mutableStateOf(customStrategy.dohProvider) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(horizontal = 18.dp),
        contentPadding = PaddingValues(top = 28.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Expressive Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Strategies",
                            fontWeight = FontWeight.Black,
                            fontSize = 32.sp,
                            letterSpacing = (-1).sp,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        ScallopedBadge(
                            text = "🛡️ ZAPRET",
                            backgroundColor = ElectricViolet,
                            textColor = TextPrimary
                        )
                    }
                    Text(
                        text = "Tailored DPI evasion parameters & packet desync modes",
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp,
                        color = TextTertiary
                    )
                }
            }
        }

        // Preset Strategy Gallery
        item {
            Text(
                text = "PRESET STRATEGIES",
                fontWeight = FontWeight.Black,
                fontSize = 12.sp,
                letterSpacing = 0.8.sp,
                color = ElectricVioletLight
            )
        }

        items(BypassStrategy.DEFAULT_PRESETS) { preset ->
            val isSelected = selectedStrategy.id == preset.id

            ExpressiveCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { strategyRepo.selectStrategy(preset) },
                shape = if (isSelected) ExpressiveShapes.AsymmetricPillLarge else ExpressiveShapes.Squircle,
                backgroundColor = if (isSelected) DarkSurfaceContainerHigh else DarkSurfaceContainer,
                borderColor = if (isSelected) NeonMint else DarkSurfaceContainerHighest
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(ExpressiveShapes.Squircle)
                                .background(if (isSelected) NeonMint.copy(alpha = 0.2f) else DarkSurfaceContainerHighest),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = preset.iconEmoji,
                                fontSize = 22.sp
                            )
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = preset.name,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 16.sp,
                                    color = TextPrimary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                ScallopedBadge(
                                    text = preset.tag,
                                    backgroundColor = if (isSelected) NeonMint else ElectricViolet,
                                    textColor = if (isSelected) DarkBackground else TextPrimary,
                                    numPetals = 8
                                )
                            }
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = preset.description,
                                fontWeight = FontWeight.Normal,
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                        }
                    }

                    if (isSelected) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(NeonMint),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = "Selected",
                                tint = DarkBackground,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }

        // Custom Strategy Builder Header
        item {
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "CUSTOM STRATEGY BUILDER",
                    fontWeight = FontWeight.Black,
                    fontSize = 12.sp,
                    letterSpacing = 0.8.sp,
                    color = SunbeamYellow
                )

                Text(
                    text = if (editingCustom) "Editing Mode" else "Tap to Customize",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = if (editingCustom) SunbeamYellow else TextTertiary,
                    modifier = Modifier.clickable { editingCustom = !editingCustom }
                )
            }
        }

        // Custom Strategy Card & Controls
        item {
            val isCustomActive = selectedStrategy.isCustom

            ExpressiveCard(
                modifier = Modifier.fillMaxWidth(),
                shape = ExpressiveShapes.AsymmetricPillInverse,
                backgroundColor = DarkSurfaceContainer,
                borderColor = if (isCustomActive) SunbeamYellow else DarkSurfaceContainerHighest
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "⚙️",
                                fontSize = 24.sp
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = customStrategy.name,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 16.sp,
                                    color = TextPrimary
                                )
                                Text(
                                    text = "Fine-tuned packet manipulation parameters",
                                    fontWeight = FontWeight.Normal,
                                    fontSize = 12.sp,
                                    color = TextSecondary
                                )
                            }
                        }

                        if (!isCustomActive) {
                            Box(
                                modifier = Modifier
                                    .clip(ExpressiveShapes.SuperPill)
                                    .background(SunbeamYellow)
                                    .clickable { strategyRepo.selectStrategy(customStrategy) }
                                    .padding(horizontal = 14.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = "APPLY",
                                    fontWeight = FontWeight.Black,
                                    fontSize = 11.sp,
                                    color = DarkBackground
                                )
                            }
                        } else {
                            ScallopedBadge(
                                text = "ACTIVE",
                                backgroundColor = SunbeamYellow,
                                textColor = DarkBackground,
                                numPetals = 8
                            )
                        }
                    }

                    // TLS Split Offset selector
                    Column {
                        Text(
                            text = "TLS CLIENTHELLO SPLIT OFFSET",
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        SegmentedPillSwitch(
                            items = listOf(-1, 1, 2, 4),
                            selectedItem = splitOffset,
                            itemLabel = { if (it == -1) "SNI Start" else "Pos " },
                            onItemSelected = { splitOffset = it }
                        )
                    }

                    // Fake SNI selection
                    Column {
                        Text(
                            text = "FAKE SNI SPOOF INJECTION",
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        SegmentedPillSwitch(
                            items = listOf("www.google.com", "cloudflare.com", "microsoft.com", "discord.com"),
                            selectedItem = fakeSni,
                            itemLabel = { it.substringBefore(".") },
                            onItemSelected = { fakeSni = it }
                        )
                    }

                    // Fake TTL Slider
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "FAKE PACKET TTL",
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                color = TextSecondary
                            )
                            Text(
                                text = " hops",
                                fontWeight = FontWeight.Black,
                                fontSize = 12.sp,
                                color = CyanSpark
                            )
                        }
                        Slider(
                            value = fakeTtl,
                            onValueChange = { fakeTtl = it },
                            valueRange = 1f..12f,
                            steps = 10,
                            colors = SliderDefaults.colors(
                                thumbColor = CyanSpark,
                                activeTrackColor = CyanSpark,
                                inactiveTrackColor = DarkSurfaceContainerHighest
                            )
                        )
                    }

                    // Disorder & Multisplit Switches
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "TCP Disorder (Out-of-Order)",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = TextPrimary
                            )
                            Text(
                                text = "Sends segment 2 before segment 1 to confuse DPI",
                                fontWeight = FontWeight.Normal,
                                fontSize = 11.sp,
                                color = TextSecondary
                            )
                        }
                        Switch(
                            checked = useDisorder,
                            onCheckedChange = { useDisorder = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = ElectricViolet,
                                checkedTrackColor = ElectricVioletContainer
                            )
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Block QUIC (UDP 443)",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = TextPrimary
                            )
                            Text(
                                text = "Forces fast TCP fallback where DPI desync succeeds",
                                fontWeight = FontWeight.Normal,
                                fontSize = 11.sp,
                                color = TextSecondary
                            )
                        }
                        Switch(
                            checked = blockQuic,
                            onCheckedChange = { blockQuic = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = CandyCoral,
                                checkedTrackColor = CandyCoralContainer
                            )
                        )
                    }

                    // DoH Provider selection
                    Column {
                        Text(
                            text = "DNS OVER HTTPS (DOH) SECURE RESOLVER",
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        SegmentedPillSwitch(
                            items = DohProvider.values().toList(),
                            selectedItem = dohProvider,
                            itemLabel = { it.displayName.substringBefore(" ") },
                            onItemSelected = { dohProvider = it }
                        )
                    }

                    // Save Custom Settings Button
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .clip(ExpressiveShapes.SuperPill)
                            .background(Brush.linearGradient(listOf(ElectricViolet, Color(0xFF6714E2))))
                            .clickable {
                                val updated = customStrategy.copy(
                                    tlsSplitOffset = splitOffset,
                                    useMultisplit = useMultisplit,
                                    fakeSni = fakeSni,
                                    fakeTtl = fakeTtl.toInt(),
                                    useDisorder = useDisorder,
                                    httpHostMod = httpHostMod,
                                    blockQuic = blockQuic,
                                    dohProvider = dohProvider
                                )
                                strategyRepo.updateCustomStrategy(updated)
                                strategyRepo.selectStrategy(updated)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.Save,
                                contentDescription = null,
                                tint = TextPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "SAVE & ACTIVATE CUSTOM PRESET",
                                fontWeight = FontWeight.Black,
                                fontSize = 13.sp,
                                letterSpacing = 0.8.sp,
                                color = TextPrimary
                            )
                        }
                    }
                }
            }
        }
    }
}