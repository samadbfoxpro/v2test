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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.NetworkPing
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Title
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.data.model.ServerConfig
import com.example.data.ping.ConnectivityResult
import com.example.data.ping.PingManager
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.ElectricViolet
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.RoseError
import kotlinx.coroutines.launch

import com.example.data.model.Subscription

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateProxyChainDialog(
    availableServers: List<ServerConfig>,
    subscriptions: List<Subscription> = emptyList(),
    onDismiss: () -> Unit,
    onSaveChain: (ServerConfig) -> Unit,
    editingChain: ServerConfig? = null
) {
    val isEditMode = editingChain != null
    val nonChainServers = remember(availableServers, editingChain) {
        availableServers.filter { 
            (!it.isProxyChain && !it.protocol.equals("CHAIN", ignoreCase = true)) || it.id == editingChain?.id 
        }.filter { it.id != editingChain?.id }
    }

    var chainName by remember { mutableStateOf(editingChain?.name ?: "") }
    var selectedRelayServer by remember {
        mutableStateOf(
            if (editingChain != null) availableServers.find { it.id == editingChain.chainRelayId } ?: nonChainServers.firstOrNull()
            else nonChainServers.firstOrNull()
        )
    }
    var selectedExitServer by remember {
        mutableStateOf(
            if (editingChain != null) availableServers.find { it.id == editingChain.chainExitId } ?: nonChainServers.getOrNull(1) ?: nonChainServers.firstOrNull()
            else nonChainServers.getOrNull(1) ?: nonChainServers.firstOrNull()
        )
    }

    var showRelayPicker by remember { mutableStateOf(false) }
    var showExitPicker by remember { mutableStateOf(false) }

    var isTestingChain by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<ConnectivityResult?>(null) }

    val coroutineScope = rememberCoroutineScope()

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(22.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = ElectricViolet.copy(alpha = 0.15f),
                            modifier = Modifier.size(38.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Link,
                                contentDescription = null,
                                tint = ElectricViolet,
                                modifier = Modifier
                                    .padding(8.dp)
                                    .size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = if (isEditMode) "ویرایش زنجیره پروکسی" else "ساخت زنجیره پروکسی (Proxy Chain)",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "اتصال ۲ مرحله‌ای (Relay ➔ Exit Node)",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "بستن")
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Info Banner
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = CyanPrimary.copy(alpha = 0.08f)),
                    border = BorderStroke(1.dp, CyanPrimary.copy(alpha = 0.25f))
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = CyanPrimary,
                            modifier = Modifier
                                .size(16.dp)
                                .padding(top = 2.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "ترافیک شما ابتدا از نود واسط ۱ عبور کرده و سپس به نود خروجی ۲ تحویل داده می‌شود. سایت‌های مقصد فقط آی‌پی نود ۲ را می‌بینند.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            lineHeight = 16.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Chain Name Input
                OutlinedTextField(
                    value = chainName,
                    onValueChange = { chainName = it },
                    label = { Text("نام زنجیره پروکسی") },
                    placeholder = {
                        val autoName = "🔗 ${selectedRelayServer?.name ?: "نود ۱"} ➔ ${selectedExitServer?.name ?: "نود ۲"}"
                        Text(autoName, fontSize = 12.sp)
                    },
                    leadingIcon = {
                        Icon(Icons.Default.Title, contentDescription = null, tint = CyanPrimary)
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyanPrimary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Step 1: Select Relay / Bridge Node
                Text(
                    text = "۱. نود واسط / ورودی (Relay / Bridge Node)",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = CyanPrimary
                )
                Spacer(modifier = Modifier.height(6.dp))

                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, CyanPrimary.copy(alpha = 0.4f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { showRelayPicker = true }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (selectedRelayServer != null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(text = selectedRelayServer!!.getCountryFlag(), fontSize = 18.sp)
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = selectedRelayServer!!.name,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${selectedRelayServer!!.protocol} • ${selectedRelayServer!!.address}:${selectedRelayServer!!.port}",
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        } else {
                            Text(text = "انتخاب سرور واسط...", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = CyanPrimary.copy(alpha = 0.15f),
                            modifier = Modifier.padding(start = 8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(imageVector = Icons.Default.Search, contentDescription = null, tint = CyanPrimary, modifier = Modifier.size(12.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(text = "انتخاب", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = CyanPrimary)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Step 2: Select Exit Node
                Text(
                    text = "۲. نود خروجی / نهایی (Exit Outbound Node)",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = ElectricViolet
                )
                Spacer(modifier = Modifier.height(6.dp))

                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, ElectricViolet.copy(alpha = 0.4f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { showExitPicker = true }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (selectedExitServer != null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(text = selectedExitServer!!.getCountryFlag(), fontSize = 18.sp)
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = selectedExitServer!!.name,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${selectedExitServer!!.protocol} • ${selectedExitServer!!.address}:${selectedExitServer!!.port}",
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        } else {
                            Text(text = "انتخاب سرور خروجی...", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = ElectricViolet.copy(alpha = 0.15f),
                            modifier = Modifier.padding(start = 8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(imageVector = Icons.Default.Search, contentDescription = null, tint = ElectricViolet, modifier = Modifier.size(12.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(text = "انتخاب", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ElectricViolet)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Visual Schematic Diagram Card
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "مسیر اتصال زنجیره‌ای",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Step 1: Phone
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Surface(shape = CircleShape, color = CyanPrimary.copy(alpha = 0.15f), modifier = Modifier.size(32.dp)) {
                                    Icon(imageVector = Icons.Default.PhoneAndroid, contentDescription = null, tint = CyanPrimary, modifier = Modifier.padding(6.dp))
                                }
                                Text("گوشی", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }

                            Icon(imageVector = Icons.Default.ArrowForward, contentDescription = null, tint = CyanPrimary, modifier = Modifier.size(14.dp))

                            // Step 2: Relay Node
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Surface(shape = CircleShape, color = CyanPrimary.copy(alpha = 0.2f), modifier = Modifier.size(32.dp)) {
                                    Box(contentAlignment = Alignment.Center) { Text(selectedRelayServer?.getCountryFlag() ?: "🌉", fontSize = 14.sp) }
                                }
                                Text("نود واسط ۱", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = CyanPrimary)
                            }

                            Icon(imageVector = Icons.Default.ArrowForward, contentDescription = null, tint = ElectricViolet, modifier = Modifier.size(14.dp))

                            // Step 3: Exit Node
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Surface(shape = CircleShape, color = ElectricViolet.copy(alpha = 0.2f), modifier = Modifier.size(32.dp)) {
                                    Box(contentAlignment = Alignment.Center) { Text(selectedExitServer?.getCountryFlag() ?: "🌐", fontSize = 14.sp) }
                                }
                                Text("نود خروجی ۲", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = ElectricViolet)
                            }

                            Icon(imageVector = Icons.Default.ArrowForward, contentDescription = null, tint = EmeraldSuccess, modifier = Modifier.size(14.dp))

                            // Step 4: Internet
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Surface(shape = CircleShape, color = EmeraldSuccess.copy(alpha = 0.15f), modifier = Modifier.size(32.dp)) {
                                    Icon(imageVector = Icons.Default.Public, contentDescription = null, tint = EmeraldSuccess, modifier = Modifier.padding(6.dp))
                                }
                                Text("اینترنت آزاد", fontSize = 10.sp, color = EmeraldSuccess)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Test Chain Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = {
                            if (selectedRelayServer != null && selectedExitServer != null) {
                                isTestingChain = true
                                testResult = null
                                coroutineScope.launch {
                                    val dummyChain = ServerConfig(
                                        name = "Test Chain",
                                        protocol = "CHAIN",
                                        address = selectedExitServer!!.address,
                                        port = selectedExitServer!!.port,
                                        isProxyChain = true,
                                        chainRelayId = selectedRelayServer!!.id,
                                        chainExitId = selectedExitServer!!.id
                                    )
                                    val res = PingManager.testGoogleConnectivity(
                                        server = dummyChain,
                                        relayConfigOverride = selectedRelayServer,
                                        exitConfigOverride = selectedExitServer
                                    )
                                    testResult = res
                                    isTestingChain = false
                                }
                            }
                        },
                        enabled = !isTestingChain && selectedRelayServer != null && selectedExitServer != null,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        if (isTestingChain) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = CyanPrimary)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("در حال تست ۲ مرحله‌ای...", fontSize = 11.sp)
                        } else {
                            Icon(imageVector = Icons.Default.NetworkPing, contentDescription = null, modifier = Modifier.size(16.dp), tint = CyanPrimary)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("تست زنده زنجیره", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = CyanPrimary)
                        }
                    }

                    testResult?.let { res ->
                        Text(
                            text = if (res.isReachable) "✅ ${res.latencyMs}ms" else "❌ عدم پاسخ",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (res.isReachable) EmeraldSuccess else RoseError
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("انصراف", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val relay = selectedRelayServer
                            val exit = selectedExitServer
                            if (relay != null && exit != null) {
                                val finalName = chainName.ifBlank { "🔗 ${relay.name} ➔ ${exit.name}" }
                                val chainConfig = (editingChain ?: ServerConfig(
                                    name = finalName,
                                    protocol = "CHAIN",
                                    address = exit.address,
                                    port = exit.port,
                                    countryCode = exit.countryCode,
                                    isProxyChain = true
                                )).copy(
                                    name = finalName,
                                    protocol = "CHAIN",
                                    address = exit.address,
                                    port = exit.port,
                                    countryCode = exit.countryCode,
                                    isProxyChain = true,
                                    chainRelayId = relay.id,
                                    chainExitId = exit.id,
                                    chainRelayName = relay.name,
                                    chainExitName = exit.name,
                                    latencyMs = testResult?.latencyMs ?: editingChain?.latencyMs ?: -1L,
                                    lastTested = System.currentTimeMillis()
                                )
                                onSaveChain(chainConfig)
                                onDismiss()
                            }
                        },
                        enabled = selectedRelayServer != null && selectedExitServer != null,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricViolet)
                    ) {
                        Icon(imageVector = Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (isEditMode) "بروزرسانی زنجیره پروکسی" else "ذخیره زنجیره پروکسی", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    // Picker Sheets for Relay and Exit nodes
    if (showRelayPicker) {
        ServerPickerBottomSheet(
            title = "انتخاب نود واسط ۱ (Relay Node)",
            subtitle = "ترافیک گوشی شما ابتدا مستقیماً به این سرور ارسال می‌شود",
            servers = nonChainServers,
            subscriptions = subscriptions,
            selectedServerId = selectedRelayServer?.id ?: 0L,
            onSelect = {
                selectedRelayServer = it
                testResult = null
            },
            onDismiss = { showRelayPicker = false }
        )
    }

    if (showExitPicker) {
        ServerPickerBottomSheet(
            title = "انتخاب نود خروجی ۲ (Exit Node)",
            subtitle = "نود واسط ترافیک را به این سرور تحویل داده و از اینترنت خارج می‌شود",
            servers = nonChainServers,
            subscriptions = subscriptions,
            selectedServerId = selectedExitServer?.id ?: 0L,
            onSelect = {
                selectedExitServer = it
                testResult = null
            },
            onDismiss = { showExitPicker = false }
        )
    }
}
