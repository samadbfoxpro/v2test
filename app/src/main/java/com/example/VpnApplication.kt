package com.example

import android.app.Application
import android.util.Log
import org.conscrypt.Conscrypt
import java.security.Security

class VpnApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            // Install Google's Conscrypt BoringSSL engine as the primary security provider.
            // Gives Android 7/8/9 full native TLS 1.3, modern Cipher Suites, and ISRG Root CA support!
            val provider = Conscrypt.newProvider()
            Security.insertProviderAt(provider, 1)
            Log.d("VpnApplication", "Conscrypt TLS 1.3 security provider installed successfully.")
        } catch (e: Exception) {
            Log.w("VpnApplication", "Failed to install Conscrypt security provider: ${e.message}")
        }
    }
}
