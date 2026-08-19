package com.example.ui.components

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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NetworkPing
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.ServerConfig
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.ElectricViolet
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.RoseError
import com.example.ui.theme.TextSlateMuted

@Composable
fun ServerCard(
    server: ServerConfig,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onTestPing: () -> Unit,
    onEdit: () -> Unit,
    onCopyUri: () -> Unit,
    onShowDetail: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onSelect() }
            .testTag("server_card_${server.id}"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        border = if (isSelected) {
            CardDefaults.outlinedCardBorder().copy(
                brush = androidx.compose.ui.graphics.SolidColor(CyanPrimary)
            )
        } else {
            CardDefaults.outlinedCardBorder()
        }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Radio button and Server Name
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                        contentDescription = if (isSelected) "Selected" else "Select",
                        tint = if (isSelected) CyanPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )

                    Spacer(modifier = Modifier.width(10.dp))

                    Text(
                        text = server.getCountryFlag(),
                        fontSize = 20.sp,
                        modifier = Modifier.padding(end = 6.dp)
                    )

                    Column {
                        Text(
                            text = server.name,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${server.address}:${server.port}",
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Interactive Ping Badge
                PingBadge(
                    latencyMs = server.latencyMs,
                    onClick = onTestPing
                )

                // 3-dot Menu
                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Options",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("تست پینگ و سلامت") },
                            leadingIcon = { Icon(Icons.Default.NetworkPing, contentDescription = null, tint = CyanPrimary) },
                            onClick = {
                                menuExpanded = false
                                onTestPing()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("مشاهده جزئیات و JSON هسته") },
                            leadingIcon = { Icon(Icons.Default.QrCode, contentDescription = null, tint = ElectricViolet) },
                            onClick = {
                                menuExpanded = false
                                onShowDetail()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("ویرایش اطلاعات کانفیگ") },
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onEdit()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("کپی لینک کانفیگ") },
                            leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onCopyUri()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("حذف این سرور", color = RoseError) },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = RoseError) },
                            onClick = {
                                menuExpanded = false
                                onDelete()
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Protocol & Transport Feature Badges
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Protocol Tag
                ProtocolTag(protocol = server.protocol)

                // Transport
                if (server.transportType.isNotBlank()) {
                    SmallPill(
                        text = server.transportType.uppercase(),
                        bgColor = MaterialTheme.colorScheme.surfaceVariant,
                        textColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Security / Reality
                if (server.isReality) {
                    SmallPill(
                        text = "REALITY",
                        bgColor = ElectricViolet.copy(alpha = 0.15f),
                        textColor = ElectricViolet,
                        hasIcon = true
                    )
                } else if (server.security.equals("tls", ignoreCase = true)) {
                    SmallPill(
                        text = "TLS",
                        bgColor = EmeraldSuccess.copy(alpha = 0.15f),
                        textColor = EmeraldSuccess
                    )
                }

                // Flow (Vision)
                if (server.flow.isNotBlank()) {
                    SmallPill(
                        text = "VISION",
                        bgColor = CyanPrimary.copy(alpha = 0.15f),
                        textColor = CyanPrimary
                    )
                }
            }
        }
    }
}

@Composable
fun PingBadge(
    latencyMs: Long,
    onClick: () -> Unit
) {
    val (badgeBg, badgeText, textColor) = when {
        latencyMs > 0 && latencyMs < 100 -> Triple(EmeraldSuccess.copy(alpha = 0.15f), "${latencyMs}ms", EmeraldSuccess)
        latencyMs in 100..250 -> Triple(AmberWarning.copy(alpha = 0.15f), "${latencyMs}ms", AmberWarning)
        latencyMs > 250 -> Triple(Color(0xFFEA580C).copy(alpha = 0.15f), "${latencyMs}ms", Color(0xFFEA580C))
        latencyMs == -2L -> Triple(RoseError.copy(alpha = 0.15f), "قطع / تایم‌اوت", RoseError)
        else -> Triple(MaterialTheme.colorScheme.surfaceVariant, "تست پینگ", MaterialTheme.colorScheme.onSurfaceVariant)
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = badgeBg,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "Test Ping",
                tint = textColor,
                modifier = Modifier.size(12.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = badgeText,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = textColor
            )
        }
    }
}

@Composable
fun ProtocolTag(protocol: String) {
    val (bgColor, textColor) = when (protocol.uppercase()) {
        "VLESS" -> Pair(CyanPrimary.copy(alpha = 0.18f), CyanPrimary)
        "VMESS" -> Pair(ElectricViolet.copy(alpha = 0.18f), ElectricViolet)
        "TROJAN" -> Pair(EmeraldSuccess.copy(alpha = 0.18f), EmeraldSuccess)
        "SHADOWSOCKS" -> Pair(AmberWarning.copy(alpha = 0.18f), AmberWarning)
        "HYSTERIA2" -> Pair(Color(0xFFE879F9).copy(alpha = 0.18f), Color(0xFFE879F9))
        else -> Pair(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurface)
    }

    Surface(
        shape = RoundedCornerShape(6.dp),
        color = bgColor
    ) {
        Text(
            text = protocol.uppercase(),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = textColor,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@Composable
fun SmallPill(
    text: String,
    bgColor: Color,
    textColor: Color,
    hasIcon: Boolean = false
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = bgColor
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (hasIcon) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = null,
                    tint = textColor,
                    modifier = Modifier
                        .size(11.dp)
                        .padding(end = 2.dp)
                )
            }
            Text(
                text = text,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = textColor
            )
        }
    }
}
