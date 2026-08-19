package com.example.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

    // Clipboard state
    var clipboardInput by remember { mutableStateOf(initialClipboardText) }
    val detectedCount = remember(clipboardInput) {
        if (clipboardInput.isNotBlank()) ConfigParser.parseInput(clipboardInput).size else 0
    }

    // Subscription state
    var subscriptionUrl by remember { mutableStateOf("") }

    // Manual form state
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
                Text(
                    text = "افزودن کانفیگ جدید",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "واردسازی از کلیپ‌بورد، لینک سابسکریپشن یا ساخت دستی",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Tabs
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("کلیپ‌بورد / لینک", fontSize = 12.sp) },
                        icon = { Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("لینک سابسکریپشن", fontSize = 12.sp) },
                        icon = { Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    )
                    Tab(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        text = { Text("ساخت دستی", fontSize = 12.sp) },
                        icon = { Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Content based on tab
                when (selectedTab) {
                    0 -> {
                        // Clipboard / URI import
                        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                            OutlinedTextField(
                                value = clipboardInput,
                                onValueChange = { clipboardInput = it },
                                placeholder = {
                                    Text("لینک‌های vless://، vmess://، trojan://، ss://، hy2:// یا متن base64 را اینجا پیست کنید...")
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(160.dp)
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
                                        imageVector = Icons.Default.Assignment,
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
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    2 -> {
                        // Manual Config Form
                        Column(
                            modifier = Modifier
                                .height(260.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = manualName,
                                onValueChange = { manualName = it },
                                label = { Text("نام سرور / عنوان") },
                                placeholder = { Text("مثال: 🇩🇪 آلمان پرسرعت") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = manualAddress,
                                    onValueChange = { manualAddress = it },
                                    label = { Text("آدرس سرور (IP یا دامنه)") },
                                    singleLine = true,
                                    modifier = Modifier.weight(2f)
                                )
                                OutlinedTextField(
                                    value = manualPort,
                                    onValueChange = { manualPort = it },
                                    label = { Text("پورت") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            OutlinedTextField(
                                value = manualUuid,
                                onValueChange = { manualUuid = it },
                                label = { Text("شناسه UUID یا رمز عبور") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = manualSni,
                                onValueChange = { manualSni = it },
                                label = { Text("دامنه SNI (Server Name Indication)") },
                                placeholder = { Text("مثال: speedtest.net") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = manualPublicKey,
                                onValueChange = { manualPublicKey = it },
                                label = { Text("کلید عمومی Reality (pbk)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = manualShortId,
                                onValueChange = { manualShortId = it },
                                label = { Text("کد Reality Short ID (sid)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text("انصراف")
                    }

                    Spacer(modifier = Modifier.width(10.dp))

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
                                    val portNum = manualPort.toIntOrNull() ?: 443
                                    if (manualAddress.isNotBlank()) {
                                        val newConfig = ServerConfig(
                                            name = manualName.ifBlank { "کانفیگ دستی VLESS (${manualAddress})" },
                                            protocol = manualProtocol,
                                            address = manualAddress.trim(),
                                            port = portNum,
                                            uuid = manualUuid.trim(),
                                            transportType = manualTransport,
                                            security = manualSecurity,
                                            sni = manualSni.trim(),
                                            publicKey = manualPublicKey.trim(),
                                            shortId = manualShortId.trim(),
                                            flow = manualFlow,
                                            rawUri = "vless://${manualUuid}@${manualAddress}:${portNum}?security=${manualSecurity}&sni=${manualSni}#${manualName}"
                                        )
                                        onSaveManualServer(newConfig)
                                        onDismiss()
                                    }
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CyanPrimary
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.testTag("confirm_import_button")
                    ) {
                        Text(
                            text = when (selectedTab) {
                                0 -> "افزودن نودها"
                                1 -> "دریافت سابسکریپشن"
                                else -> "ذخیره کانفیگ"
                            },
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
        }
    }
}
