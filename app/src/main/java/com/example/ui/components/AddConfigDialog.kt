package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.model.ServerConfig
import com.example.data.parser.ConfigParser
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.ElectricViolet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddConfigDialog(
    initialClipboardText: String = "",
    onDismiss: () -> Unit,
    onImportText: (String) -> Unit,
    onImportSubscription: (String) -> Unit,
    onSaveManualServer: (ServerConfig) -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var clipboardInput by remember { mutableStateOf(initialClipboardText) }
    var subscriptionUrl by remember { mutableStateOf("") }
    var showQrScanner by remember { mutableStateOf(false) }

    val detectedConfigs: List<ServerConfig> = remember(clipboardInput) {
        if (clipboardInput.isNotBlank()) {
            ConfigParser.parseInput(clipboardInput)
        } else {
            emptyList()
        }
    }
    val detectedCount = detectedConfigs.size

    // Manual Form States
    var manualName by remember { mutableStateOf("") }
    var manualProtocol by remember { mutableStateOf("VLESS") }
    var manualAddress by remember { mutableStateOf("") }
    var manualPort by remember { mutableStateOf("443") }
    var manualUuid by remember { mutableStateOf("") }
    var manualTransport by remember { mutableStateOf("tcp") }
    var manualSecurity by remember { mutableStateOf("reality") }
    var manualSni by remember { mutableStateOf("") }
    var manualPublicKey by remember { mutableStateOf("") }
    var manualShortId by remember { mutableStateOf("") }
    var manualFlow by remember { mutableStateOf("xtls-rprx-vision") }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .padding(16.dp)
                .testTag("add_config_dialog"),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            border = CardDefaults.outlinedCardBorder()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "افزودن کانفیگ جدید",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "واردسازی از کلیپ‌بورد، اسکن QR، لینک یا ساخت دستی",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // QR Code Scanner Action Button
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = CyanPrimary.copy(alpha = 0.15f),
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { showQrScanner = true }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.QrCodeScanner,
                                contentDescription = "اسکن QR",
                                tint = CyanPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "اسکن QR",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = CyanPrimary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Tabs
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("کلیپ‌بورد / متن", fontSize = 11.sp) },
                        icon = { Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("لینک سابسکریپشن", fontSize = 11.sp) },
                        icon = { Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    )
                    Tab(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        text = { Text("ساخت دستی", fontSize = 11.sp) },
                        icon = { Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Content based on tab
                when (selectedTab) {
                    0 -> {
                        // Clipboard / URI import
                        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                            // Quick Action: Scan QR Code Button
                            OutlinedButton(
                                onClick = { showQrScanner = true },
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, CyanPrimary.copy(alpha = 0.6f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.QrCodeScanner,
                                    contentDescription = null,
                                    tint = CyanPrimary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "📷 اسکن بارکد QR با دوربین یا از گالری",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = CyanPrimary
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            OutlinedTextField(
                                value = clipboardInput,
                                onValueChange = { clipboardInput = it },
                                placeholder = {
                                    Text("لینک‌های vless://، vmess://، trojan://، ss:// یا متن را اینجا پیست کنید...")
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(140.dp)
                                    .testTag("clipboard_input_field"),
                                shape = RoundedCornerShape(14.dp),
                                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = CyanPrimary
                                )
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            if (detectedCount > 0) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(
                                            CyanPrimary.copy(alpha = 0.15f),
                                            RoundedCornerShape(10.dp)
                                        )
                                        .padding(10.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.Assignment,
                                        contentDescription = null,
                                        tint = CyanPrimary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "تعداد $detectedCount نود معتبر شناسایی شد",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = CyanPrimary
                                    )
                                }
                            }
                        }
                    }
                    1 -> {
                        // Subscription Import
                        Column {
                            OutlinedTextField(
                                value = subscriptionUrl,
                                onValueChange = { subscriptionUrl = it },
                                label = { Text("آدرس لینک سابسکریپشن (Sub URL)") },
                                placeholder = { Text("https://example.com/api/v1/client/subscribe?token=...") },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("subscription_input_field"),
                                shape = RoundedCornerShape(14.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = ElectricViolet
                                )
                            )

                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "پشتیبانی از دیکود خودکار Base64 و واردسازی دسته‌ای تمامی کانفیگ‌ها.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    2 -> {
                        // Manual Builder
                        Column(
                            modifier = Modifier
                                .height(220.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            OutlinedTextField(
                                value = manualName,
                                onValueChange = { manualName = it },
                                label = { Text("نام سرور") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(6.dp))

                            // Protocol Dropdown
                            var protocolExpanded by remember { mutableStateOf(false) }
                            ExposedDropdownMenuBox(
                                expanded = protocolExpanded,
                                onExpandedChange = { protocolExpanded = !protocolExpanded }
                            ) {
                                OutlinedTextField(
                                    value = manualProtocol,
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("پروتکل") },
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = protocolExpanded) },
                                    modifier = Modifier
                                        .menuAnchor(MenuAnchorType.PrimaryNotEditable, true)
                                        .fillMaxWidth()
                                )
                                ExposedDropdownMenu(
                                    expanded = protocolExpanded,
                                    onDismissRequest = { protocolExpanded = false }
                                ) {
                                    listOf("VLESS", "VMESS", "TROJAN", "SHADOWSOCKS").forEach { p ->
                                        DropdownMenuItem(
                                            text = { Text(p) },
                                            onClick = {
                                                manualProtocol = p
                                                protocolExpanded = false
                                            }
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))
                            Row(modifier = Modifier.fillMaxWidth()) {
                                OutlinedTextField(
                                    value = manualAddress,
                                    onValueChange = { manualAddress = it },
                                    label = { Text("آدرس سرور / IP") },
                                    singleLine = true,
                                    modifier = Modifier.weight(2f)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                OutlinedTextField(
                                    value = manualPort,
                                    onValueChange = { manualPort = it },
                                    label = { Text("پورت") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            Spacer(modifier = Modifier.height(6.dp))
                            OutlinedTextField(
                                value = manualUuid,
                                onValueChange = { manualUuid = it },
                                label = { Text("UUID / رمز عبور") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(modifier = Modifier.height(6.dp))
                            OutlinedTextField(
                                value = manualSni,
                                onValueChange = { manualSni = it },
                                label = { Text("SNI / Server Name") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("انصراف")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            when (selectedTab) {
                                0 -> {
                                    if (clipboardInput.isNotBlank()) {
                                        onImportText(clipboardInput)
                                        onDismiss()
                                    }
                                }
                                1 -> {
                                    if (subscriptionUrl.isNotBlank()) {
                                        onImportSubscription(subscriptionUrl)
                                        onDismiss()
                                    }
                                }
                                2 -> {
                                    if (manualAddress.isNotBlank() && manualPort.isNotBlank()) {
                                        val portInt = manualPort.toIntOrNull() ?: 443
                                        val server = ServerConfig(
                                            name = manualName.ifBlank { "سرور دستی ($manualAddress)" },
                                            protocol = manualProtocol,
                                            address = manualAddress,
                                            port = portInt,
                                            uuid = manualUuid,
                                            transportType = manualTransport,
                                            security = manualSecurity,
                                            sni = manualSni,
                                            publicKey = manualPublicKey,
                                            shortId = manualShortId,
                                            flow = manualFlow,
                                            countryCode = "AUTO"
                                        )
                                        onSaveManualServer(server)
                                        onDismiss()
                                    }
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CyanPrimary
                        ),
                        enabled = when (selectedTab) {
                            0 -> clipboardInput.isNotBlank()
                            1 -> subscriptionUrl.isNotBlank()
                            2 -> manualAddress.isNotBlank() && manualPort.isNotBlank()
                            else -> false
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.testTag("submit_add_config_button")
                    ) {
                        Text("افزودن و ذخیره", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    // QR Code Scanner Dialog
    if (showQrScanner) {
        QrScannerDialog(
            onDismiss = { showQrScanner = false },
            onScanned = { scannedText ->
                showQrScanner = false
                onImportText(scannedText)
                onDismiss()
            }
        )
    }
}
