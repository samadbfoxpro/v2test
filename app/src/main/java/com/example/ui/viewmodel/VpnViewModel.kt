package com.example.ui.viewmodel

import android.app.Application
import android.net.VpnService
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.AppDatabase
import com.example.data.model.ConnectionStatus
import com.example.data.model.RoutingMode
import com.example.data.model.ServerConfig
import com.example.data.model.SpeedStats
import com.example.data.ping.PingManager
import com.example.data.repository.ServerRepository
import com.example.service.XrayVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

enum class SortOrder(val displayName: String) {
    DEFAULT("جدیدترین"),
    LOWEST_PING("کمترین تاخیر (پینگ)"),
    NAME("نام سرور (الفبا)"),
    PROTOCOL("نوع پروتکل")
}

data class UiMessage(val text: String, val isError: Boolean = false)

class VpnViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: ServerRepository

    init {
        val db = AppDatabase.getInstance(application)
        repository = ServerRepository(db.serverDao())

        viewModelScope.launch(Dispatchers.IO) {
            val existing = db.serverDao().getAllServersList()
            val hasTargetConfig = existing.any { it.uuid == "9fb12a38-9b39-49d9-8d45-b643ca5a2c1a" }
            if (existing.isEmpty() || !hasTargetConfig) {
                db.serverDao().clearAll()
                db.serverDao().insertServers(AppDatabase.getDefaultServers())
            }
        }
    }

    // Raw servers list
    private val rawServers = repository.allServers

    // UI filters & sorting
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedProtocolFilter = MutableStateFlow("ALL")
    val selectedProtocolFilter: StateFlow<String> = _selectedProtocolFilter.asStateFlow()

    private val _sortOrder = MutableStateFlow(SortOrder.DEFAULT)
    val sortOrder: StateFlow<SortOrder> = _sortOrder.asStateFlow()

    // Smart Auto-Connect vs Manual Mode
    private val _isSmartMode = MutableStateFlow(true)
    val isSmartMode: StateFlow<Boolean> = _isSmartMode.asStateFlow()

    // Filtered & Sorted Servers
    val filteredServers: StateFlow<List<ServerConfig>> = combine(
        rawServers,
        _searchQuery,
        _selectedProtocolFilter,
        _sortOrder
    ) { servers, query, protocol, sort ->
        var list = servers

        // Filter by protocol
        if (protocol != "ALL") {
            list = list.filter { it.protocol.equals(protocol, ignoreCase = true) }
        }

        // Filter by query
        if (query.isNotBlank()) {
            val q = query.trim().lowercase()
            list = list.filter {
                it.name.lowercase().contains(q) ||
                it.address.lowercase().contains(q) ||
                it.protocol.lowercase().contains(q) ||
                it.countryCode.lowercase().contains(q)
            }
        }

        // Sort
        when (sort) {
            SortOrder.DEFAULT -> list // Keep database order (addedAt DESC)
            SortOrder.LOWEST_PING -> list.sortedWith(compareBy<ServerConfig> {
                when {
                    it.latencyMs > 0 -> it.latencyMs
                    it.latencyMs == -1L -> 999998L // untested
                    else -> 999999L // timeout (-2)
                }
            })
            SortOrder.NAME -> list.sortedBy { it.name.lowercase() }
            SortOrder.PROTOCOL -> list.sortedBy { it.protocol }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val selectedServer: StateFlow<ServerConfig?> = repository.selectedServer.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = null
    )

    // Connection state
    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _connectingStepMessage = MutableStateFlow("")
    val connectingStepMessage: StateFlow<String> = _connectingStepMessage.asStateFlow()

    val speedStats: StateFlow<SpeedStats> = XrayVpnService.liveSpeedStats

    // Routing and settings
    private val _routingMode = MutableStateFlow(RoutingMode.BYPASS_LAN_AND_IRAN)
    val routingMode: StateFlow<RoutingMode> = _routingMode.asStateFlow()

    private val _dnsProvider = MutableStateFlow("Cloudflare (1.1.1.1)")
    val dnsProvider: StateFlow<String> = _dnsProvider.asStateFlow()

    private val _muxEnabled = MutableStateFlow(true)
    val muxEnabled: StateFlow<Boolean> = _muxEnabled.asStateFlow()

    private val _fragmentEnabled = MutableStateFlow(true)
    val fragmentEnabled: StateFlow<Boolean> = _fragmentEnabled.asStateFlow()

    // Batch Ping state
    private val _isBatchTesting = MutableStateFlow(false)
    val isBatchTesting: StateFlow<Boolean> = _isBatchTesting.asStateFlow()

    private val _batchProgress = MutableStateFlow(0f)
    val batchProgress: StateFlow<Float> = _batchProgress.asStateFlow()

    private val _batchStatusText = MutableStateFlow("")
    val batchStatusText: StateFlow<String> = _batchStatusText.asStateFlow()

    // Snackbars / Feedback
    private val _uiMessage = MutableStateFlow<UiMessage?>(null)
    val uiMessage: StateFlow<UiMessage?> = _uiMessage.asStateFlow()

    private var connectionJob: Job? = null

    init {
        viewModelScope.launch {
            XrayVpnService.isVpnRunning.collect { isRunning ->
                if (isRunning && _connectionStatus.value != ConnectionStatus.CONNECTED) {
                    _connectionStatus.value = ConnectionStatus.CONNECTED
                } else if (!isRunning && _connectionStatus.value == ConnectionStatus.CONNECTED) {
                    _connectionStatus.value = ConnectionStatus.DISCONNECTED
                }
            }
        }
    }

    fun toggleSmartMode() {
        _isSmartMode.value = !_isSmartMode.value
        val modeName = if (_isSmartMode.value) "اتصال هوشمند (تست و سوییچ خودکار)" else "حالت انتخاب دستی سرور"
        _uiMessage.value = UiMessage("حالت فعال: $modeName")
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setProtocolFilter(protocol: String) {
        _selectedProtocolFilter.value = protocol
    }

    fun setSortOrder(order: SortOrder) {
        _sortOrder.value = order
    }

    fun setRoutingMode(mode: RoutingMode) {
        _routingMode.value = mode
    }

    fun setDnsProvider(dns: String) {
        _dnsProvider.value = dns
    }

    fun toggleMux() {
        _muxEnabled.value = !_muxEnabled.value
    }

    fun toggleFragment() {
        _fragmentEnabled.value = !_fragmentEnabled.value
    }

    fun clearUiMessage() {
        _uiMessage.value = null
    }

    fun selectServer(server: ServerConfig) {
        viewModelScope.launch {
            repository.selectServer(server.id)
            if (_connectionStatus.value == ConnectionStatus.CONNECTED) {
                XrayVpnService.startVpn(getApplication(), server, _routingMode.value)
                _uiMessage.value = UiMessage("تونل اتصال به ${server.name} تغییر یافت")
            }
        }
    }

    fun toggleConnection(onVpnPermissionRequired: () -> Unit = {}) {
        val current = _connectionStatus.value

        if (current == ConnectionStatus.DISCONNECTED) {
            val prepareIntent = VpnService.prepare(getApplication())
            if (prepareIntent != null) {
                onVpnPermissionRequired()
                return
            }

            if (_isSmartMode.value) {
                startSmartConnectWithFailover()
            } else {
                startManualConnect()
            }
        } else if (current == ConnectionStatus.CONNECTED || current == ConnectionStatus.CONNECTING) {
            disconnect()
        }
    }

    fun onVpnPermissionApproved() {
        if (_isSmartMode.value) {
            startSmartConnectWithFailover()
        } else {
            startManualConnect()
        }
    }

    private fun startSmartConnectWithFailover() {
        connectionJob?.cancel()
        connectionJob = viewModelScope.launch {
            val allNodes = filteredServers.value.ifEmpty { repository.allServers.stateIn(viewModelScope).value }
            if (allNodes.isEmpty()) {
                _uiMessage.value = UiMessage("هیچ سروری برای اتصال وجود ندارد", true)
                return@launch
            }

            _connectionStatus.value = ConnectionStatus.CONNECTING

            val candidateNodes = allNodes.sortedWith(compareBy {
                when {
                    it.latencyMs > 0 -> it.latencyMs
                    it.latencyMs == -1L -> 500L
                    else -> 9999L
                }
            })

            var connectedNode: ServerConfig? = null

            for ((index, node) in candidateNodes.withIndex()) {
                _connectingStepMessage.value = "در حال تست نود ${index + 1} از ${candidateNodes.size}: ${node.name}..."
                delay(300)

                val testResult = PingManager.testGoogleConnectivity(node)

                if (testResult.isReachable) {
                    val updated = repository.testServerLatency(node)
                    repository.selectServer(node.id)
                    connectedNode = updated
                    _connectingStepMessage.value = "تایید شد! دسترسی به گوگل برقرار است (${testResult.latencyMs}ms)"
                    delay(350)
                    break
                } else {
                    _connectingStepMessage.value = "نود ${node.name} پاسخ نداد ⬅ بررسی خودکار نود بعدی..."
                    delay(400)
                }
            }

            if (connectedNode != null) {
                XrayVpnService.startVpn(getApplication(), connectedNode, _routingMode.value)

                _connectionStatus.value = ConnectionStatus.CONNECTED
                _connectingStepMessage.value = ""
                _uiMessage.value = UiMessage("با موفقیت به ${connectedNode.name} متصل شدید 🔑")
            } else {
                _connectionStatus.value = ConnectionStatus.DISCONNECTED
                _connectingStepMessage.value = ""
                _uiMessage.value = UiMessage("تمامی نودها در تست اتصال ناموفق بودند. لطفا کانفیگ جدید اضافه کنید.", true)
            }
        }
    }

    private fun startManualConnect() {
        val currentServer = selectedServer.value
        if (currentServer == null) {
            _uiMessage.value = UiMessage("لطفا ابتدا یک سرور از لیست انتخاب کنید", true)
            return
        }

        connectionJob?.cancel()
        connectionJob = viewModelScope.launch {
            _connectionStatus.value = ConnectionStatus.CONNECTING
            _connectingStepMessage.value = "در حال بررسی و اتصال به ${currentServer.name}..."

            val testResult = PingManager.testGoogleConnectivity(currentServer)
            if (testResult.isReachable) {
                delay(350)

                XrayVpnService.startVpn(getApplication(), currentServer, _routingMode.value)

                _connectionStatus.value = ConnectionStatus.CONNECTED
                _connectingStepMessage.value = ""
                _uiMessage.value = UiMessage("متصل به ${currentServer.name} • تونل فعال شد 🔑")
            } else {
                _connectingStepMessage.value = ""
                _connectionStatus.value = ConnectionStatus.DISCONNECTED
                _uiMessage.value = UiMessage("خطا در برقراری اتصال به ${currentServer.name}: سرور پاسخ نداد", true)
            }
        }
    }

    private fun disconnect() {
        connectionJob?.cancel()
        viewModelScope.launch {
            _connectionStatus.value = ConnectionStatus.DISCONNECTING
            _connectingStepMessage.value = ""
            XrayVpnService.stopVpn(getApplication())
            delay(350)
            _connectionStatus.value = ConnectionStatus.DISCONNECTED
            _uiMessage.value = UiMessage("اتصال وی‌پی‌ان قطع شد")
        }
    }

    fun testSingleServerLatency(server: ServerConfig) {
        viewModelScope.launch {
            _uiMessage.value = UiMessage("در حال تست پینگ و بررسی دسترسی ${server.name}...")
            val result = PingManager.testGoogleConnectivity(server)
            if (result.isReachable) {
                val updated = repository.testServerLatency(server)
                _uiMessage.value = UiMessage("✅ پینگ سرور: ${updated.latencyMs} میلی‌ثانیه (${result.message})")
            } else {
                _uiMessage.value = UiMessage("❌ سرور ${server.name} پاسخ نداد (تایم‌اوت)", true)
            }
        }
    }

    fun batchPingAll() {
        val list = filteredServers.value
        if (list.isEmpty()) {
            _uiMessage.value = UiMessage("هیچ سروری برای تست وجود ندارد", true)
            return
        }

        viewModelScope.launch {
            _isBatchTesting.value = true
            _batchProgress.value = 0f
            _batchStatusText.value = "در حال تست 0/${list.size} سرور..."

            PingManager.batchTestLatency(list, maxConcurrency = 6) { completed, total, updatedServer ->
                repository.updateServer(updatedServer)
                _batchProgress.value = completed.toFloat() / total.toFloat()
                _batchStatusText.value = "در حال تست پینگ $completed از $total سرور..."
            }

            _isBatchTesting.value = false
            _batchProgress.value = 1f
            _uiMessage.value = UiMessage("تست پینگ همگانی برای ${list.size} نود با موفقیت پایان یافت")
        }
    }

    fun autoSelectFastest() {
        val list = filteredServers.value
        if (list.isEmpty()) {
            _uiMessage.value = UiMessage("سروری جهت انتخاب هوشمند یافت نشد", true)
            return
        }

        viewModelScope.launch {
            _uiMessage.value = UiMessage("⚡ در حال تست و انتخاب سریع‌ترین سرور...")
            _isBatchTesting.value = true
            _batchProgress.value = 0f

            val tested = PingManager.batchTestLatency(list, maxConcurrency = 6) { completed, total, updatedServer ->
                repository.updateServer(updatedServer)
                _batchProgress.value = completed.toFloat() / total.toFloat()
            }

            _isBatchTesting.value = false

            val fastest = tested.filter { it.latencyMs > 0 }.minByOrNull { it.latencyMs }
            if (fastest != null) {
                repository.selectServer(fastest.id)
                _uiMessage.value = UiMessage("سریع‌ترین سرور انتخاب شد: ${fastest.name} (${fastest.latencyMs} میلی‌ثانیه)")
            } else {
                _uiMessage.value = UiMessage("تمامی سرورها تایم‌اوت هستند یا پاسخ ندادند", true)
            }
        }
    }

    fun importFromClipboard(clipboardText: String) {
        if (clipboardText.isBlank()) {
            _uiMessage.value = UiMessage("کلیپ‌بورد خالی است", true)
            return
        }

        viewModelScope.launch {
            val count = repository.importFromText(clipboardText)
            if (count > 0) {
                _uiMessage.value = UiMessage("$count کانفیگ جدید با موفقیت اضافه شد")
            } else {
                _uiMessage.value = UiMessage("هیچ لینک معتبر VLESS، VMess، Trojan یا Shadowsocks در کلیپ‌بورد یافت نشد", true)
            }
        }
    }

    fun importFromSubscriptionUrl(url: String) {
        val trimmed = url.trim()
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            _uiMessage.value = UiMessage("لطفا یک آدرس لینک سابسکریپشن معتبر (HTTP/HTTPS) وارد کنید", true)
            return
        }

        viewModelScope.launch {
            _uiMessage.value = UiMessage("در حال دریافت اطلاعات سابسکریپشن...")
            val result = repository.importFromSubscriptionUrl(trimmed)
            if (result.isSuccess) {
                val count = result.getOrDefault(0)
                _uiMessage.value = UiMessage("$count سرور از لینک سابسکریپشن با موفقیت دریافت شد")
            } else {
                _uiMessage.value = UiMessage("خطا در دریافت سابسکریپشن: ${result.exceptionOrNull()?.message}", true)
            }
        }
    }

    fun saveServer(server: ServerConfig) {
        viewModelScope.launch {
            if (server.id == 0L) {
                repository.insertServer(server)
                _uiMessage.value = UiMessage("کانفیگ ${server.name} افزوده شد")
            } else {
                repository.updateServer(server)
                _uiMessage.value = UiMessage("کانفیگ ${server.name} ویرایش شد")
            }
        }
    }

    fun deleteServer(server: ServerConfig) {
        viewModelScope.launch {
            repository.deleteServer(server.id)
            _uiMessage.value = UiMessage("کانفیگ ${server.name} حذف شد")
        }
    }

    fun deleteTimeoutServers() {
        viewModelScope.launch {
            repository.deleteTimeoutServers()
            _uiMessage.value = UiMessage("تمام سرورهای قطع شده و تایم‌اوت پاکسازی شدند")
        }
    }

    fun clearAllServers() {
        viewModelScope.launch {
            repository.clearAll()
            _uiMessage.value = UiMessage("تمام سرورها با موفقیت پاک شدند")
        }
    }

    override fun onCleared() {
        super.onCleared()
        connectionJob?.cancel()
    }
}
