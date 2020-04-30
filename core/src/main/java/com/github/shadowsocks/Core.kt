/*******************************************************************************
 *                                                                             *
 *  Copyright (C) 2018 by Max Lv <max.c.lv@gmail.com>                          *
 *  Copyright (C) 2018 by Mygod Studio <contact-shadowsocks-android@mygod.be>  *
 *                                                                             *
 *  This program is free software: you can redistribute it and/or modify       *
 *  it under the terms of the GNU General Public License as published by       *
 *  the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                        *
 *                                                                             *
 *  This program is distributed in the hope that it will be useful,            *
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 *  GNU General Public License for more details.                               *
 *                                                                             *
 *  You should have received a copy of the GNU General Public License          *
 *  along with this program. If not, see <http://www.gnu.org/licenses/>.       *
 *                                                                             *
 *******************************************************************************/

package com.github.shadowsocks

import SpeedUpVPN.VpnEncrypt
import android.app.*
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.os.UserManager
import android.util.Log
import android.view.Gravity
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.work.Configuration
import androidx.work.WorkManager
import com.crashlytics.android.Crashlytics
import com.github.shadowsocks.acl.Acl
import com.github.shadowsocks.aidl.ShadowsocksConnection
import com.github.shadowsocks.core.R
import com.github.shadowsocks.database.Profile
import com.github.shadowsocks.database.ProfileManager
import com.github.shadowsocks.database.SSRSubManager
import com.github.shadowsocks.net.TcpFastOpen
import com.github.shadowsocks.preference.DataStore
import com.github.shadowsocks.utils.*
import com.github.shadowsocks.work.UpdateCheck
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import io.fabric.sdk.android.Fabric
import kotlinx.coroutines.DEBUG_PROPERTY_NAME
import kotlinx.coroutines.DEBUG_PROPERTY_VALUE_ON
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.net.*
import javax.net.ssl.SSLHandshakeException
import kotlin.reflect.KClass
import com.github.shadowsocks.bg.ProxyService
object Core {
    const val TAG = "Core"

    lateinit var app: Application
        @VisibleForTesting set
    lateinit var configureIntent: (Context) -> PendingIntent
    val activity by lazy { app.getSystemService<ActivityManager>()!! }
    val connectivity by lazy { app.getSystemService<ConnectivityManager>()!! }
    val notification by lazy { app.getSystemService<NotificationManager>()!! }
    val packageInfo: PackageInfo by lazy { getPackageInfo(app.packageName) }
    val deviceStorage by lazy { if (Build.VERSION.SDK_INT < 24) app else DeviceStorageApp(app) }
    val analytics: FirebaseAnalytics by lazy { FirebaseAnalytics.getInstance(deviceStorage) }
    val directBootSupported by lazy {
        Build.VERSION.SDK_INT >= 24 && app.getSystemService<DevicePolicyManager>()?.storageEncryptionStatus ==
                DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_PER_USER
    }

    val activeProfileIds get() = ProfileManager.getProfile(DataStore.profileId).let {
        if (it == null) emptyList() else listOfNotNull(it.id, it.udpFallback)
    }
    val currentProfile: Pair<Profile, Profile?>? get() {
        if (DataStore.directBootAware) DirectBoot.getDeviceProfile()?.apply { return this }
        var theOne=ProfileManager.getProfile(DataStore.profileId)
        if (theOne==null){
            theOne=ProfileManager.getRandomVPNServer()
            if (theOne!=null)DataStore.profileId=theOne.id
        }
        return ProfileManager.expand(theOne ?: return null)
    }

    fun switchProfile(id: Long): Profile {
        val result = ProfileManager.getProfile(id) ?: ProfileManager.createProfile()
        DataStore.profileId = result.id
        return result
    }

    //Import built-in subscription
    fun updateBuiltinServers(activity:Activity,stopUpdateNotification:Boolean=false){
        Log.e("updateBuiltinServers ","...")
        GlobalScope.launch {
            var  builtinSubUrls  = app.resources.getStringArray(R.array.builtinSubUrls)
            for (i in 0 until builtinSubUrls.size) {
                var builtinSub=SSRSubManager.create(builtinSubUrls.get(i),"aes")
                //var builtinSub=SSRSubManager.createBuiltInSub(builtinSubUrls.get(i))
                if (builtinSub != null) break
            }
            val profiles = ProfileManager.getAllProfilesByGroup(VpnEncrypt.vpnGroupName)
            if (profiles.isNullOrEmpty()) {
                Log.e("------","profiles empty, return@launch")
                activity.runOnUiThread {alertMessage("网络连接异常，连接互联网后，请重起本APP",activity)}
                return@launch
            }

            var selectedProfileId=profiles.first().id
            switchProfile(selectedProfileId)
            startService()
            var testMsg="测试通道，请稍候."

            activity.runOnUiThread {showMessage(testMsg)}
            Thread.sleep(5_000)
            var selectedProfileDelay = testConnection2(profiles.first())
            testMsg+="."
            activity.runOnUiThread {showMessage(testMsg)}


            Log.e("test proxy:",profiles.first().name+", delay:"+selectedProfileDelay)
            for (i in 1 until profiles.size) {
                if (!profiles.get(i).isBuiltin())continue
                switchProfile(profiles.get(i).id)
                reloadService()
                Thread.sleep(2_000)
                var delay = testConnection2(profiles.get(i))

                testMsg+="."
                activity.runOnUiThread {showMessage(testMsg)}

                Log.e("test proxy:",profiles.get(i).name+", delay:"+delay)
                if(delay < selectedProfileDelay){
                    selectedProfileDelay=delay
                    selectedProfileId=profiles.get(i).id
                }
            }

            if(DataStore.profileId!=selectedProfileId){
                switchProfile(selectedProfileId)
                reloadService()
            }
            Thread.sleep(2_000)

            val openURL = Intent(Intent.ACTION_VIEW)
            var startUrl="https://www.bannedbook.org/bnews/fq/?utm_source=org.mobile.jinwang"
            if (stopUpdateNotification)startUrl+="&stopUpdateNotification=true"
            openURL.data = Uri.parse(startUrl)
            openURL.setClassName(activity.applicationContext,activity.javaClass.name)
            activity.startActivity(openURL)
        }
    }
    private val URLConnection.responseLength: Long
        get() = if (Build.VERSION.SDK_INT >= 24) contentLengthLong else contentLength.toLong()

    fun testConnection2(server:Profile): Long {
        var result : Long = 3600000  // 1 hour
        var conn: HttpURLConnection? = null

        try {
            val url = URL("https",
                    "www.google.com",
                    "/generate_204")
            //Log.e("start test server",server.name + "...")
            //conn = url.openConnection(Proxy(Proxy.Type.HTTP,DataStore.httpProxyAddress)) as HttpURLConnection
            conn = url.openConnection(
                    Proxy(Proxy.Type.HTTP,
                            InetSocketAddress("127.0.0.1", VpnEncrypt.HTTP_PROXY_PORT))) as HttpURLConnection
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 6.1; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/79.0.3945.117 Safari/537.36")
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("Connection", "close")
            conn.instanceFollowRedirects = false
            conn.useCaches = false

            val start = SystemClock.elapsedRealtime()
            val code = conn.responseCode
            val elapsed = SystemClock.elapsedRealtime() - start
            //Log.e("test server",server.name + " delay is "+ elapsed.toString())
            if (code == 204 || code == 200 && conn.responseLength == 0L) {
                result = elapsed
            } else {
                throw IOException(app.getString(R.string.connection_test_error_status_code, code))
            }
        }
        catch (e: IOException) {
            // network exception
            Log.e("Core:","testConnection2:"+e.toString())
        } catch (e: Exception) {
            // library exception, eg sumsung
            Log.e("Core-","testConnection Exception: "+Log.getStackTraceString(e))
        } finally {
            conn?.disconnect()
        }
        return result
    }
    /**
     * import free sub
     */
    fun importFreeSubs(): Boolean {
        try {
            GlobalScope.launch {
                var  freesuburl  = app.resources.getStringArray(R.array.freesuburl)
                for (i in freesuburl.indices) {
                    var freeSub=SSRSubManager.createSSSub(freesuburl[i])
                    if (freeSub != null) break
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
        return true
    }

    fun init(app: Application, configureClass: KClass<out Any>) {
        this.app = app
        this.configureIntent = {
            PendingIntent.getActivity(it, 0, Intent(it, configureClass.java)
                    .setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT), 0)
        }

        if (Build.VERSION.SDK_INT >= 24) {  // migrate old files
            deviceStorage.moveDatabaseFrom(app, Key.DB_PUBLIC)
            val old = Acl.getFile(Acl.CUSTOM_RULES, app)
            if (old.canRead()) {
                Acl.getFile(Acl.CUSTOM_RULES).writeText(old.readText())
                old.delete()
            }
        }

        // overhead of debug mode is minimal: https://github.com/Kotlin/kotlinx.coroutines/blob/f528898/docs/debugging.md#debug-mode
        System.setProperty(DEBUG_PROPERTY_NAME, DEBUG_PROPERTY_VALUE_ON)
        Fabric.with(deviceStorage, Crashlytics())   // multiple processes needs manual set-up
        FirebaseApp.initializeApp(deviceStorage)
        WorkManager.initialize(deviceStorage, Configuration.Builder().apply {
            setExecutor { GlobalScope.launch { it.run() } }
            setTaskExecutor { GlobalScope.launch { it.run() } }
        }.build())
        UpdateCheck.enqueue() //google play Publishing, prohibiting self-renewal

        // handle data restored/crash
        if (Build.VERSION.SDK_INT >= 24 && DataStore.directBootAware &&
                app.getSystemService<UserManager>()?.isUserUnlocked == true) DirectBoot.flushTrafficStats()
        if (DataStore.tcpFastOpen && !TcpFastOpen.sendEnabled) TcpFastOpen.enableTimeout()
        if (DataStore.publicStore.getLong(Key.assetUpdateTime, -1) != packageInfo.lastUpdateTime) {
            val assetManager = app.assets
            try {
                for (file in assetManager.list("acl")!!) assetManager.open("acl/$file").use { input ->
                    File(deviceStorage.noBackupFilesDir, file).outputStream().use { output -> input.copyTo(output) }
                }
            } catch (e: IOException) {
                printLog(e)
            }
            DataStore.publicStore.putLong(Key.assetUpdateTime, packageInfo.lastUpdateTime)
        }
        updateNotificationChannels()
    }

    fun updateNotificationChannels() {
        if (Build.VERSION.SDK_INT >= 26) @RequiresApi(26) {
            notification.createNotificationChannels(listOf(
                    NotificationChannel("service-vpn", app.getText(R.string.service_vpn),
                            if (Build.VERSION.SDK_INT >= 28) NotificationManager.IMPORTANCE_MIN
                            else NotificationManager.IMPORTANCE_LOW),   // #1355
                    NotificationChannel("service-proxy", app.getText(R.string.service_proxy),
                            NotificationManager.IMPORTANCE_LOW),
                    NotificationChannel("service-transproxy", app.getText(R.string.service_transproxy),
                            NotificationManager.IMPORTANCE_LOW)))
            notification.deleteNotificationChannel("service-nat")   // NAT mode is gone for good
        }
    }

    fun getPackageInfo(packageName: String) = app.packageManager.getPackageInfo(packageName,
            if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
            else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES)!!

    fun startService() = ContextCompat.startForegroundService(app, Intent(app, ShadowsocksConnection.serviceClass))
    fun reloadService() = app.sendBroadcast(Intent(Action.RELOAD).setPackage(app.packageName))
    fun stopService() = app.sendBroadcast(Intent(Action.CLOSE).setPackage(app.packageName))
    fun startServiceForTest() = app.startService(Intent(app, ProxyService::class.java).putExtra("test","go"))
    fun showMessage(msg: String) {
        var toast = Toast.makeText(app, msg, Toast.LENGTH_LONG)
        toast.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, 150)
        toast.show()
    }

    fun alertMessage(msg: String,activity:Context) {
        try {
            if(activity==null || (activity as Activity).isFinishing)return
        val builder: AlertDialog.Builder? = activity.let {
            AlertDialog.Builder(activity)
        }
        builder?.setMessage(msg)?.setTitle("SS VPN")?.setPositiveButton("ok", DialogInterface.OnClickListener {
            _, _ ->
        })
        val dialog: AlertDialog? = builder?.create()
        dialog?.show()
        }
        catch (t:Throwable){}
    }
}
