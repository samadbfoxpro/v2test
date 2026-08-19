package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.ConnectionStatus
import com.example.data.model.RoutingMode
import com.example.data.model.ServerConfig
import com.example.data.model.SpeedStats
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.ElectricViolet
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.RoseError

@Composable
fun HeroConnectionCard(
    connectionStatus: ConnectionStatus,
    selectedServer: ServerConfig?,
    speedStats: SpeedStats,
    routingMode: RoutingMode,
    isSmartMode: Boolean,
    connectingStepMessage: String,
    onToggleConnection: () -> Unit,
    onToggleSmartMode: () -> Unit,
    onOpenRoutingSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Pulse animation when connected or connecting
    val infiniteTransition = rememberInfiniteTransition(label = "pulse_transition")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (connectionStatus == ConnectionStatus.CONNECTED || connectionStatus == ConnectionStatus.CONNECTING) 1.22f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = if (connectionStatus == ConnectionStatus.CONNECTED || connectionStatus == ConnectionStatus.CONNECTING) 0.05f else 0.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("hero_connection_card"),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = Brush.linearGradient(
                colors = when (connectionStatus) {
                    ConnectionStatus.CONNECTED -> listOf(EmeraldSuccess.copy(alpha = 0.6f), NeonCyan.copy(alpha = 0.4f))
                    ConnectionStatus.CONNECTING -> listOf(AmberWarning.copy(alpha = 0.7f), ElectricViolet.copy(alpha = 0.4f))
                    else -> listOf(MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), Color.Transparent)
                }
            )
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Mode Selector: Smart Auto-Connect vs Manual Selection
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (isSmartMode) NeonCyan.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, if (isSmartMode) NeonCyan.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .clickable { onToggleSmartMode() }
                    .testTag("smart_mode_toggle_pill")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isSmartMode) Icons.Default.AutoAwesome else Icons.Default.TouchApp,
                        contentDescription = null,
                        tint = if (isSmartMode) NeonCyan else CyanPrimary,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isSmartMode) "⚡ اتصال هوشمند (تست خودکار و سوییچ نود)" else "✋ حالت انتخاب دستی نود",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isSmartMode) NeonCyan else MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "• تغییر حالت",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Selected Server Header Pill
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = CardDefaults.outlinedCardBorder(),
                modifier = Modifier.fillMaxWidth(0.92f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = selectedServer?.getCountryFlag() ?: "🌐",
                        fontSize = 16.sp,
                        modifier = Modifier.padding(end = 6.dp)
                    )
                    Text(
                        text = selectedServer?.name ?: if (isSmartMode) "یافتن خودکار بهترین نود..." else "هیچ سروری انتخاب نشده",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (selectedServer != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = CyanPrimary.copy(alpha = 0.15f)
                        ) {
                            Text(
                                text = selectedServer.protocol,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = CyanPrimary,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Power Button with glowing halo
            Box(
                modifier = Modifier
                    .size(130.dp)
                    .padding(6.dp),
                contentAlignment = Alignment.Center
            ) {
                if (connectionStatus == ConnectionStatus.CONNECTED || connectionStatus == ConnectionStatus.CONNECTING) {
                    val pulseColor = if (connectionStatus == ConnectionStatus.CONNECTED) EmeraldSuccess else AmberWarning
                    Box(
                        modifier = Modifier
                            .size(120.dp)
                            .scale(pulseScale)
                            .clip(CircleShape)
                            .background(pulseColor.copy(alpha = pulseAlpha))
                    )
                }

                val buttonGradient = when (connectionStatus) {
                    ConnectionStatus.CONNECTED -> Brush.radialGradient(
                        colors = listOf(EmeraldSuccess, Color(0xFF059669))
                    )
                    ConnectionStatus.CONNECTING -> Brush.radialGradient(
                        colors = listOf(AmberWarning, Color(0xFFD97706))
                    )
                    ConnectionStatus.DISCONNECTING -> Brush.radialGradient(
                        colors = listOf(RoseError, Color(0xFFE11D48))
                    )
                    ConnectionStatus.DISCONNECTED -> Brush.radialGradient(
                        colors = listOf(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
                    )
                }

                val buttonBorderColor = when (connectionStatus) {
                    ConnectionStatus.CONNECTED -> EmeraldSuccess.copy(alpha = 0.8f)
                    ConnectionStatus.CONNECTING -> AmberWarning.copy(alpha = 0.8f)
                    else -> MaterialTheme.colorScheme.outline
                }

                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .shadow(
                            elevation = if (connectionStatus == ConnectionStatus.CONNECTED) 16.dp else 4.dp,
                            shape = CircleShape,
                            spotColor = if (connectionStatus == ConnectionStatus.CONNECTED) EmeraldSuccess else Color.Black
                        )
                        .clip(CircleShape)
                        .background(buttonGradient)
                        .border(3.dp, buttonBorderColor, CircleShape)
                        .clickable { onToggleConnection() }
                        .testTag("power_toggle_button"),
                    contentAlignment = Alignment.Center
                ) {
                    if (connectionStatus == ConnectionStatus.CONNECTING || connectionStatus == ConnectionStatus.DISCONNECTING) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(42.dp),
                            strokeWidth = 3.5.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.PowerSettingsNew,
                            contentDescription = "اتصال و قطع وی‌پی‌ان",
                            tint = if (connectionStatus == ConnectionStatus.CONNECTED) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(44.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Status Label
            val statusColor = when (connectionStatus) {
                ConnectionStatus.CONNECTED -> EmeraldSuccess
                ConnectionStatus.CONNECTING -> AmberWarning
                ConnectionStatus.DISCONNECTING -> RoseError
                ConnectionStatus.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            val statusText = when (connectionStatus) {
                ConnectionStatus.CONNECTED -> "متصل • اینترنت و گوگل باز است (کلید فعال 🔑)"
                ConnectionStatus.CONNECTING -> "در حال بررسی و ایجاد تونل..."
                ConnectionStatus.DISCONNECTING -> "در حال قطع اتصال..."
                ConnectionStatus.DISCONNECTED -> "قطع شده (آماده اتصال)"
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    ),
                    color = statusColor
                )
            }

            // Live connecting step status (Pre-connection Google test & Failover)
            AnimatedVisibility(
                visible = connectingStepMessage.isNotBlank(),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Text(
                    text = connectingStepMessage,
                    fontSize = 12.sp,
                    color = AmberWarning,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Real-time Speed & Stats Telemetry
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Download Speed
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = CircleShape,
                        color = CyanPrimary.copy(alpha = 0.2f),
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowDownward,
                            contentDescription = "دانلود",
                            tint = CyanPrimary,
                            modifier = Modifier
                                .padding(6.dp)
                                .size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Column {
                        Text(text = "دانلود", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            text = if (connectionStatus == ConnectionStatus.CONNECTED) speedStats.formatDownloadSpeed() else "0 B/s",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                // Upload Speed
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = CircleShape,
                        color = ElectricViolet.copy(alpha = 0.2f),
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowUpward,
                            contentDescription = "آپلود",
                            tint = ElectricViolet,
                            modifier = Modifier
                                .padding(6.dp)
                                .size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Column {
                        Text(text = "آپلود", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            text = if (connectionStatus == ConnectionStatus.CONNECTED) speedStats.formatUploadSpeed() else "0 B/s",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                // Duration
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = CircleShape,
                        color = EmeraldSuccess.copy(alpha = 0.2f),
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Timer,
                            contentDescription = "مدت زمان",
                            tint = EmeraldSuccess,
                            modifier = Modifier
                                .padding(6.dp)
                                .size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Column {
                        Text(text = "زمان اتصال", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            text = if (connectionStatus == ConnectionStatus.CONNECTED) speedStats.formatDuration() else "00:00",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Quick Routing Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onOpenRoutingSettings() }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Route,
                        contentDescription = "مسیریابی",
                        tint = CyanPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "مسیریابی:",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = routingMode.title,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = CyanPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.ElectricBolt,
                        contentDescription = "هسته",
                        tint = ElectricViolet,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Xray v1.8.24",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
