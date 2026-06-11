package com.plugin.vpnservice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Bundle
import androidx.core.app.NotificationCompat

import app.tauri.plugin.JSObject

class TauriVpnService : VpnService() {
    companion object {
        @JvmField var triggerCallback: (String, JSObject) -> Unit = { _, _ -> }
        @JvmField var self: TauriVpnService? = null
        @JvmField var ipv4Addr: String? = null
        @JvmField var routes: Array<String> = emptyArray()
        @JvmField var dns: String? = null

        const val IPV4_ADDR = "IPV4_ADDR"
        const val ROUTES = "ROUTES"
        const val DNS = "DNS"
        const val DISALLOWED_APPLICATIONS = "DISALLOWED_APPLICATIONS"
        const val MTU = "MTU"

        const val CHANNEL_ID = "easytier_vpn_channel"
        const val NOTIFICATION_ID = 1356
    }

    private lateinit var vpnInterface: ParcelFileDescriptor

    override fun onCreate() {
        super.onCreate()
        self = this
        println("vpn on create")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        println("vpn on start command ${intent?.getExtras()} $intent")
        
        // 首先显示通知，确保服务成为前台服务
        try {
            startForegroundWithNotification()
            println("vpn startForeground succeeded")
        } catch (e: Exception) {
            println("vpn startForeground failed: ${e.message}")
            e.printStackTrace()
            // 如果 startForeground 失败，停止服务
            stopSelf()
            return START_NOT_STICKY
        }
        
        var args = intent?.getExtras()
        ipv4Addr = args?.getString(IPV4_ADDR)
        routes = args?.getStringArray(ROUTES) ?: emptyArray()
        dns = args?.getString(DNS)

        try {
            vpnInterface = createVpnInterface(args)
            println("vpn created ${vpnInterface.fd}")

            var event_data = JSObject()
            event_data.put("fd", vpnInterface.fd)
            triggerCallback("vpn_service_start", event_data)
        } catch (e: Exception) {
            println("vpn createVpnInterface failed: ${e.message}")
            e.printStackTrace()
            // 即使 VPN 创建失败，也保持前台服务运行
            // 只是不发送启动事件
        }

        return START_STICKY
    }

    private fun startForegroundWithNotification() {
        println("vpn creating notification channel")
        createNotificationChannel()
        
        println("vpn building notification")
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("EasyTier VPN")
            .setContentText("VPN is connected")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()

        println("vpn calling startForeground")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        println("vpn startForeground called")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "EasyTier VPN",
                    NotificationManager.IMPORTANCE_LOW
                )
                channel.setShowBadge(false)
                val manager = getSystemService(NotificationManager::class.java)
                manager?.createNotificationChannel(channel)
                println("vpn notification channel created")
            } catch (e: Exception) {
                println("vpn failed to create notification channel: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    override fun onDestroy() {
        println("vpn on destroy")
        super.onDestroy()
        disconnect()
        self = null
    }

    override fun onRevoke() {
        println("vpn on revoke")
        super.onRevoke()
        disconnect()
        self = null
    }

    private fun disconnect() {
        if (self == this && this::vpnInterface.isInitialized) {
            triggerCallback("vpn_service_stop", JSObject())
            vpnInterface.close()
        }
        clearStatus()
    }

    private fun clearStatus() {
        ipv4Addr = null
        routes = emptyArray()
        dns = null
    }

    private fun createVpnInterface(args: Bundle?): ParcelFileDescriptor {
        var builder = Builder()
                .setSession("TauriVpnService")
                .setBlocking(false)
        
        var mtu = args?.getInt(MTU) ?: 1500
        var ipv4Addr = args?.getString(IPV4_ADDR) ?: "10.126.126.1/24"
        var dns: String? = args?.getString(DNS)
        var routes = args?.getStringArray(ROUTES) ?: emptyArray()
        var disallowedApplications = args?.getStringArray(DISALLOWED_APPLICATIONS) ?: emptyArray()

        println("vpn create vpn interface. mtu: $mtu, ipv4Addr: $ipv4Addr, dns:" +
            "$dns, routes: ${java.util.Arrays.toString(routes)}," +
            "disallowedApplications:  ${java.util.Arrays.toString(disallowedApplications)}")

        val ipParts = ipv4Addr.split("/")
        if (ipParts.size != 2) throw IllegalArgumentException("Invalid IP addr string")
        builder.addAddress(ipParts[0], ipParts[1].toInt())
        builder.addAddress("fd00::1", 128)

        builder.setMtu(mtu)
        dns?.let { builder.addDnsServer(it) }

        for (route in routes) {
            val ipParts = route.split("/")
            if (ipParts.size != 2) throw IllegalArgumentException("Invalid route cidr string")
            builder.addRoute(ipParts[0], ipParts[1].toInt())
        }
        
        for (app in disallowedApplications) {
            builder.addDisallowedApplication(app)
        }

        return builder.also {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                it.setMetered(false)
            }
        }
        .establish()
        ?: throw IllegalStateException("Failed to init VpnService")
    }
}
