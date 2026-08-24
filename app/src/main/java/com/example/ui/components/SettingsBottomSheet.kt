package com.example.ui.components

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Speed
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import com.example.ui.theme.RoseError
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.AppBypassManager
import com.example.data.model.RoutingMode
import com.example.data.model.SmartConnectMode
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.ElectricViolet
import com.example.ui.theme.NeonCyan

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsBottomSheet(
    currentSmartMode: SmartConnectMode,
    onSmartModeChange: (SmartConnectMode) -> Unit,
    currentRoutingMode: RoutingMode,
    onRoutingModeChange: (RoutingMode) -> Unit,
    currentDns: String,
    onDnsChange: (String) -> Unit,
    currentTestUrl: String,
    onTestUrlChange: (String) -> Unit,
    muxEnabled: Boolean,
    onToggleMux: () -> Unit,
    fragmentEnabled: Boolean,
    onToggleFragment: () -> Unit,
    isLanSharingActive: Boolean = false,
    isLanHttpRunning: Boolean = false,
    isLanSocksRunning: Boolean = false,
    currentLanIp: String? = null,
    lanHttpEnabled: Boolean = true,
    lanHttpPort: Int = 8881,
    lanSocksEnabled: Boolean = true,
    lanSocksPort: Int = 10808,
    lanLastError: String? = null,
    onToggleLanSharing: () -> Unit = {},
    onSetLanHttpEnabled: (Boolean) -> Unit = {},
    onSetLanHttpPort: (Int) -> Unit = {},
    onSetLanSocksEnabled: (Boolean) -> Unit = {},
    onSetLanSocksPort: (Int) -> Unit = {},
    onRefreshLanIp: () -> Unit = {},
    onToggleServerSection: () -> Unit = {},
    isHideConfigSharingEnabled: Boolean = false,
    onSetHideConfigSharing: (Boolean) -> Unit = {},
    isLogRecording: Boolean = false,
    recordedLogCount: Int = 0,
    recordingDurationSec: Long = 0L,
    onStartLogRecording: () -> Unit = {},
    onStopLogRecording: () -> Unit = {},
    onSaveLogToDownloads: () -> Unit = {},
    onShareLogFile: () -> Unit = {},
    fakeDnsEnabled: Boolean = false,
    onToggleFakeDns: () -> Unit = {},
    onAddSecretSubscription: (onDone: (Boolean, Int) -> Unit) -> Unit = {},
    onOpenDnsDiagnostics: () -> Unit = {},
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var showAppBypassSheet by remember { mutableStateOf(false) }
    var showLanSharingSheet by remember { mutableStateOf(false) }
    var bypassCount by remember { mutableIntStateOf(AppBypassManager.getBypassedPackages(context).size) }
    var isBypassActive by remember { mutableStateOf(AppBypassManager.isBypassEnabled(context)) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = null,
                        tint = CyanPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "تنظیمات اتصال و هسته Xray",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "بستن")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Smart Connect Selection
            Text(
                text = "⚡ نحوه عملکرد دکمه اتصال هوشمند",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = NeonCyan
            )
            Spacer(modifier = Modifier.height(8.dp))

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    SmartConnectMode.values().forEach { mode ->
                        val isSelected = currentSmartMode == mode
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSmartModeChange(mode) }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isSelected) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                                contentDescription = null,
                                tint = if (isSelected) NeonCyan else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = mode.title,
                                    fontSize = 13.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = mode.description,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    lineHeight = 15.sp
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // App Bypass / Split Tunneling (Per-App Proxy)
            Text(
                text = "📱 استثنای برنامه‌ها از فیلترشکن (Split Tunneling)",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = CyanPrimary
            )
            Spacer(modifier = Modifier.height(8.dp))

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "فعال‌سازی عبور مستقیم برنامه‌ها",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "برنامه‌های انتخاب‌شده بدون افت سرعت و مستقیم به اینترنت وصل می‌شوند",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = isBypassActive,
                            onCheckedChange = {
                                isBypassActive = it
                                AppBypassManager.setBypassEnabled(context, it)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = CyanPrimary,
                                checkedTrackColor = CyanPrimary.copy(alpha = 0.3f)
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = CyanPrimary.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, CyanPrimary.copy(alpha = 0.3f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { showAppBypassSheet = true }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Apps,
                                    contentDescription = null,
                                    tint = CyanPrimary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "انتخاب برنامه‌های استثنا ($bypassCount برنامه)",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = CyanPrimary
                                )
                            }

                            Icon(
                                imageVector = Icons.Default.ArrowForwardIos,
                                contentDescription = null,
                                tint = CyanPrimary,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // LAN Sharing Entry Card
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, if (isLanSharingActive) CyanPrimary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { showLanSharingSheet = true }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(if (isLanSharingActive) CyanPrimary.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surface),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lan,
                                contentDescription = null,
                                tint = if (isLanSharingActive) CyanPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "اشتراک‌گذاری در شبکه محلی (LAN)",
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (isLanSharingActive) CyanPrimary.copy(alpha = 0.2f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f)
                                ) {
                                    Text(
                                        text = if (isLanSharingActive) (currentLanIp ?: "آنلاین") else "خاموش",
                                        fontSize = 9.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isLanSharingActive) CyanPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Text(
                                text = if (isLanSharingActive) "پروکسی فعال روی پورت‌های HTTP ($lanHttpPort) و SOCKS ($lanSocksPort)" else "اشتراک پروکسی برای کامپیوتر و سایر دستگاه‌ها",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Icon(
                        imageVector = Icons.Default.ArrowForwardIos,
                        contentDescription = null,
                        tint = CyanPrimary,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Routing Mode Selection
            Text(
                text = "قوانین مسیریابی ترافیک",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = CyanPrimary
            )
            Spacer(modifier = Modifier.height(8.dp))

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    RoutingMode.values().forEach { mode ->
                        val isSelected = currentRoutingMode == mode
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onRoutingModeChange(mode) }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isSelected) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                                contentDescription = null,
                                tint = if (isSelected) CyanPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = mode.title,
                                    fontSize = 13.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = mode.description,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // DNS Selection & Smart DNS Engine
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "موتور هوشمند و سرور DNS",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = ElectricViolet
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = CyanPrimary.copy(alpha = 0.15f),
                    modifier = Modifier.clickable { onOpenDnsDiagnostics() }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Speed,
                            contentDescription = null,
                            tint = CyanPrimary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "مرکز عیب‌یابی DNS",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = CyanPrimary
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            // Smart DNS Dashboard Quick Access Card
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                border = BorderStroke(1.dp, CyanPrimary.copy(alpha = 0.3f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenDnsDiagnostics() }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(CyanPrimary.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Dns,
                                contentDescription = null,
                                tint = CyanPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "موتور Smart DNS خودکار",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "DoH • DoT • FakeDNS • تست عدم نشت",
                                fontSize = 10.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            val dnsList = listOf(
                "Cloudflare (1.1.1.1)",
                "Google (8.8.8.8)",
                "AdGuard Anti-Ad (94.140.14.14)",
                "Quad9 Secure (9.9.9.9)"
            )

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    dnsList.forEach { dns ->
                        val isSelected = currentDns == dns
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onDnsChange(dns) }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isSelected) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                                contentDescription = null,
                                tint = if (isSelected) ElectricViolet else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = dns,
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Test URL Selector (Real Delay Test URL)
            Text(
                text = "مسیر تست پینگ واقعی (Real Delay Test URL)",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = CyanPrimary
            )
            Spacer(modifier = Modifier.height(8.dp))

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    val testUrls = listOf(
                        "https://www.google.com/generate_204" to "Google 204 (پیش‌فرض)",
                        "https://www.gstatic.com/generate_204" to "Gstatic 204",
                        "https://www.apple.com/library/test/success.html" to "Apple Test",
                        "http://www.msftconnecttest.com/connecttest.txt" to "Microsoft Test"
                    )

                    testUrls.forEach { (url, label) ->
                        val isSelected = currentTestUrl == url
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onTestUrlChange(url) }
                                .padding(horizontal = 6.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isSelected) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                                contentDescription = null,
                                tint = if (isSelected) CyanPrimary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = label,
                                    fontSize = 13.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) CyanPrimary else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = url,
                                    fontSize = 10.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Anti-Censorship & Performance Options
            Text(
                text = "ویژگی‌های ضد فیلترینگ و بهینه‌سازی",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = CyanPrimary
            )
            Spacer(modifier = Modifier.height(8.dp))

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    // TLS Fragment
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "تکه‌تکه‌سازی TLS Fragment (ضد فیلتر SNI)",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "بسته‌های Client Hello را تکه‌تکه می‌کند تا فیلتر SNI و DPI را دور بزند",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = fragmentEnabled,
                            onCheckedChange = { onToggleFragment() },
                            colors = SwitchDefaults.colors(checkedThumbColor = CyanPrimary, checkedTrackColor = CyanPrimary.copy(alpha = 0.3f))
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Mux (Multiplexing)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "تجمیع ارتباطات Mux.Cool (Multiplexing)",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "چندین ارتباط TCP را در یک کانال تجمیع می‌کند تا تاخیر و پینگ به حداقل برسد",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = muxEnabled,
                            onCheckedChange = { onToggleMux() },
                            colors = SwitchDefaults.colors(checkedThumbColor = CyanPrimary, checkedTrackColor = CyanPrimary.copy(alpha = 0.3f))
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // FakeDNS (Virtual IP Mapping)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "موتور نگاشت FakeDNS (IP مجازی)",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "دامنه‌ها را به IP فرضی ۱۹۸.۱۸ نگاشت می‌کند تا تاخیر DNS حذف شود (پیش‌فرض: خاموش)",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = fakeDnsEnabled,
                            onCheckedChange = { onToggleFakeDns() },
                            colors = SwitchDefaults.colors(checkedThumbColor = CyanPrimary, checkedTrackColor = CyanPrimary.copy(alpha = 0.3f))
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Network Flight Recorder & Diagnostic Tool
            Text(
                text = "ضبط رویدادها و عیب‌یابی شبکه (Flight Recorder)",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = ElectricViolet
            )
            Spacer(modifier = Modifier.height(8.dp))

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                border = if (isLogRecording) BorderStroke(1.dp, RoseError.copy(alpha = 0.8f)) else CardDefaults.outlinedCardBorder()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(if (isLogRecording) RoseError.copy(alpha = 0.15f) else ElectricViolet.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (isLogRecording) Icons.Default.FiberManualRecord else Icons.Default.BugReport,
                                    contentDescription = null,
                                    tint = if (isLogRecording) RoseError else ElectricViolet,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = if (isLogRecording) "در حال ضبط وقایع اتصال..." else "ضبط لاگ‌های عیب‌یابی",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isLogRecording) RoseError else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = if (isLogRecording) {
                                        val min = recordingDurationSec / 60
                                        val sec = recordingDurationSec % 60
                                        "زمان: ${String.format("%02d:%02d", min, sec)} • ${recordedLogCount} رویداد"
                                    } else {
                                        "ثبت جزئیات نشست‌ها برای تحلیل علت قطعی"
                                    },
                                    fontSize = 10.5.sp,
                                    fontFamily = if (isLogRecording) FontFamily.Monospace else FontFamily.Default,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Button(
                            onClick = {
                                if (isLogRecording) onStopLogRecording() else onStartLogRecording()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isLogRecording) RoseError else ElectricViolet
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(
                                imageVector = if (isLogRecording) Icons.Default.Stop else Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = Color.White
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isLogRecording) "توقف" else "شروع ضبط",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }

                    if (recordedLogCount > 0 && !isLogRecording) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = onSaveLogToDownloads,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp), tint = CyanPrimary)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("ذخیره در دانلودها", fontSize = 11.sp, color = CyanPrimary)
                            }

                            Button(
                                onClick = onShareLogFile,
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.surface)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("اشتراک‌گذاری فایل", fontSize = 11.sp, color = MaterialTheme.colorScheme.surface, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Secret Trigger: درباره من
            var tapCount by remember { mutableIntStateOf(0) }
            var lastTapTime by remember { mutableLongStateOf(0L) }
            var showPasswordDialog by remember { mutableStateOf(false) }
            var passwordInput by remember { mutableStateOf("") }
            var passwordError by remember { mutableStateOf(false) }
            var showAdvancedPanel by remember { mutableStateOf(false) }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "درباره من",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                    modifier = Modifier
                        .clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null
                        ) {
                            val now = System.currentTimeMillis()
                            if (now - lastTapTime < 800L) {
                                tapCount++
                            } else {
                                tapCount = 1
                            }
                            lastTapTime = now

                            if (tapCount >= 5) {
                                tapCount = 0
                                showPasswordDialog = true
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Secret Password Dialog
            if (showPasswordDialog) {
                AlertDialog(
                    onDismissRequest = {
                        showPasswordDialog = false
                        passwordInput = ""
                        passwordError = false
                    },
                    icon = {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(ElectricViolet.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                tint = ElectricViolet,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    },
                    title = {
                        Text(
                            text = "ورود به بخش پیشرفته",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    text = {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "لطفاً رمز ورود را وارد کنید:",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                            OutlinedTextField(
                                value = passwordInput,
                                onValueChange = {
                                    passwordInput = it
                                    passwordError = false
                                },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                isError = passwordError,
                                placeholder = { Text("رمز ورود...") },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            )
                            if (passwordError) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "رمز عبور اشتباه است ❌",
                                    color = MaterialTheme.colorScheme.error,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                if (passwordInput.trim() == "1372") {
                                    showPasswordDialog = false
                                    passwordInput = ""
                                    passwordError = false
                                    showAdvancedPanel = true
                                } else {
                                    passwordError = true
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("ورود", color = MaterialTheme.colorScheme.surface, fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = {
                                showPasswordDialog = false
                                passwordInput = ""
                                passwordError = false
                            }
                        ) {
                            Text("انصراف", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    shape = RoundedCornerShape(22.dp),
                    containerColor = MaterialTheme.colorScheme.surface
                )
            }

            // Advanced Settings Bottom Sheet
            if (showAdvancedPanel) {
                AdvancedSettingsBottomSheet(
                    isHideConfigSharingEnabled = isHideConfigSharingEnabled,
                    onSetHideConfigSharing = onSetHideConfigSharing,
                    onAddSecretSubscription = onAddSecretSubscription,
                    onDismiss = { showAdvancedPanel = false }
                )
            }
        }
    }

    // App Bypass Selection Bottom Sheet
    if (showAppBypassSheet) {
        AppBypassBottomSheet(
            onDismiss = { showAppBypassSheet = false },
            onSaveSuccess = { count ->
                bypassCount = count
                isBypassActive = AppBypassManager.isBypassEnabled(context)
            }
        )
    }

    // LAN Sharing Bottom Sheet
    if (showLanSharingSheet) {
        LanSharingBottomSheet(
            isLanSharingActive = isLanSharingActive,
            isHttpRunning = isLanHttpRunning,
            isSocksRunning = isLanSocksRunning,
            currentLanIp = currentLanIp,
            httpEnabled = lanHttpEnabled,
            httpPort = lanHttpPort,
            socksEnabled = lanSocksEnabled,
            socksPort = lanSocksPort,
            lastError = lanLastError,
            onToggleLanSharing = onToggleLanSharing,
            onSetHttpEnabled = onSetLanHttpEnabled,
            onSetHttpPort = onSetLanHttpPort,
            onSetSocksEnabled = onSetLanSocksEnabled,
            onSetSocksPort = onSetLanSocksPort,
            onRefreshIp = onRefreshLanIp,
            onDismiss = { showLanSharingSheet = false }
        )
    }
}
