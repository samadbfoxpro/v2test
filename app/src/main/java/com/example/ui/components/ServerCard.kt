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
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
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
import com.example.ui.theme.NeonCyan
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
    onShowQrCode: () -> Unit = {},
    isHideConfigSharingEnabled: Boolean = false,
    onMoveToSub: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onSelect() }
            .testTag("server_card_${server.id}"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)
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
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Radio button, Flag, and Server Name
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                        contentDescription = if (isSelected) "Selected" else "Select",
                        tint = if (isSelected) CyanPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )

                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = server.getCountryFlag(),
                                fontSize = 18.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = server.name,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                ),
                                color = if (isSelected) CyanPrimary else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                        }

                        if (server.isProxyChain || server.protocol.equals("CHAIN", ignoreCase = true)) {
                            Text(
                                text = "${server.chainRelayName.ifBlank { "نود میانی" }} ➔ ${server.chainExitName.ifBlank { "نود خروجی" }}",
                                fontSize = 11.sp,
                                color = ElectricViolet,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        } else {
                            Text(
                                text = "${server.address}:${server.port}",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))

                PingBadge(
                    latencyMs = server.latencyMs,
                    onClick = onTestPing
                )

                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Options",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
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
                        if (!isHideConfigSharingEnabled) {
                            DropdownMenuItem(
                                text = { Text("اشتراک‌گذاری با QR Code") },
                                leadingIcon = { Icon(Icons.Default.QrCode, contentDescription = null, tint = CyanPrimary) },
                                onClick = {
                                    menuExpanded = false
                                    onShowQrCode()
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("مشاهده جزئیات و JSON هسته") },
                            leadingIcon = { Icon(Icons.Default.Info, contentDescription = null, tint = ElectricViolet) },
                            onClick = {
                                menuExpanded = false
                                onShowDetail()
                            }
                        )
                        val isChain = server.isProxyChain || server.protocol.equals("CHAIN", ignoreCase = true)
                        DropdownMenuItem(
                            text = { Text(if (isChain) "ویرایش زنجیره پروکسی" else "ویرایش اطلاعات کانفیگ") },
                            leadingIcon = { 
                                Icon(
                                    imageVector = if (isChain) Icons.Default.Link else Icons.Default.Edit,
                                    contentDescription = null,
                                    tint = if (isChain) ElectricViolet else MaterialTheme.colorScheme.onSurface
                                ) 
                            },
                            onClick = {
                                menuExpanded = false
                                onEdit()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("انتقال به سابسکریپشن دیگر") },
                            leadingIcon = { Icon(Icons.Default.DriveFileMove, contentDescription = null, tint = NeonCyan) },
                            onClick = {
                                menuExpanded = false
                                onMoveToSub()
                            }
                        )
                        if (!isHideConfigSharingEnabled) {
                            DropdownMenuItem(
                                text = { Text("کپی لینک کانفیگ") },
                                leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onCopyUri()
                                }
                            )
                        }
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

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Protocol Tag
                ProtocolTag(protocol = server.protocol)

                if (server.isProxyChain || server.protocol.equals("CHAIN", ignoreCase = true)) {
                    SmallPill(
                        text = "2-HOP RELAY",
                        bgColor = ElectricViolet.copy(alpha = 0.15f),
                        textColor = ElectricViolet,
                        hasIcon = true
                    )
                }

                // Transport
                if (server.transportType.isNotBlank() && !server.isProxyChain) {
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
                } else if (server.security.equals("tls", ignoreCase = true) && !server.isProxyChain) {
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
        shape = RoundedCornerShape(10.dp),
        color = badgeBg,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "Test Ping",
                tint = textColor,
                modifier = Modifier.size(11.dp)
            )
            Spacer(modifier = Modifier.width(3.dp))
            Text(
                text = badgeText,
                fontSize = 10.5.sp,
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
        "CHAIN", "PROXYCHAIN" -> Pair(NeonCyan.copy(alpha = 0.2f), NeonCyan)
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
            text = if (protocol.equals("CHAIN", ignoreCase = true)) "🔗 PROXY CHAIN" else protocol.uppercase(),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = textColor,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
        )
    }
}

@Composable
private fun SmallPill(
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
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (hasIcon) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = null,
                    tint = textColor,
                    modifier = Modifier.size(10.dp)
                )
                Spacer(modifier = Modifier.width(3.dp))
            }
            Text(
                text = text,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = textColor
            )
        }
    }
}
