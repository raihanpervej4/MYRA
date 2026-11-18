package com.myra.assistant.viewmodel

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.Settings
import android.telecom.TelecomManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.myra.assistant.model.AppCommand
import com.myra.assistant.service.AccessibilityHelperService
import com.myra.assistant.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        /** Spec test #10: with Accessibility OFF, open/close app shows an error instead of executing. */
        private const val REQUIRE_ACCESSIBILITY_FOR_OPEN = true

        private val APP_MAP = mapOf(
            "youtube" to "com.google.android.youtube", "whatsapp" to "com.whatsapp",
            "instagram" to "com.instagram.android", "facebook" to "com.facebook.katana",
            "chrome" to "com.android.chrome", "gmail" to "com.google.android.gm",
            "maps" to "com.google.android.apps.maps", "spotify" to "com.spotify.music",
            "netflix" to "com.netflix.mediaclient", "twitter" to "com.twitter.android",
            "x" to "com.twitter.android", "telegram" to "org.telegram.messenger",
            "snapchat" to "com.snapchat.android", "settings" to "com.android.settings",
            "calculator" to "com.google.android.calculator", "calendar" to "com.google.android.calendar",
            "clock" to "com.google.android.deskclock", "phone" to "com.google.android.dialer",
            "contacts" to "com.google.android.contacts", "play store" to "com.android.vending",
            "amazon" to "in.amazon.mShop.android.shopping", "flipkart" to "com.flipkart.android",
            "paytm" to "net.one97.paytm", "phonepe" to "com.phonepe.app",
            "gpay" to "com.google.android.apps.nbu.paisa.user", "zoom" to "us.zoom.videomeetings",
            "meet" to "com.google.android.apps.tachyon", "teams" to "com.microsoft.teams",
            "tiktok" to "com.zhiliaoapp.musically", "discord" to "com.discord",
            "linkedin" to "com.linkedin.android"
        )
        private val ALIASES = mapOf(
            "you tube" to "youtube", "insta" to "instagram", "wa" to "whatsapp", "whats app" to "whatsapp",
            "google maps" to "maps", "playstore" to "play store", "google pay" to "gpay",
            "g pay" to "gpay", "setting" to "settings", "dialer" to "phone", "camera" to "camera"
        )
    }

    private val ctx: Context get() = getApplication<Application>().applicationContext
    private val ui = Handler(Looper.getMainLooper())

    private val _commandResult = MutableLiveData<String?>()
    val commandResult: LiveData<String?> = _commandResult

    fun clearResult() { _commandResult.value = null }
    private fun result(msg: String) { _commandResult.postValue(msg) }
    private fun toast(msg: String) { ui.post { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() } }
    private fun has(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    private fun startAct(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
    }

    // ------------------------------------------------------------------ dispatcher
    fun execute(cmd: AppCommand) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val p = cmd.params
                when (cmd.type) {
                    "OPEN_APP" -> if (guardAccessibility()) openApp(p["app_name"].orEmpty())
                    "CLOSE_APP" -> if (guardAccessibility()) closeApp()
                    "CALL" -> call(p["name"], p["number"])
                    "SMS" -> sms(p["name"], p["message"].orEmpty())
                    "WHATSAPP_MSG" -> whatsapp(p["name"].orEmpty(), p["message"].orEmpty(), false)
                    "WHATSAPP_CALL" -> whatsapp(p["name"].orEmpty(), "", true)
                    "PRIME_CALL" -> primeAction(p["index"]?.toIntOrNull() ?: 0, true)
                    "PRIME_MSG" -> primeAction(p["index"]?.toIntOrNull() ?: 0, false)
                    "VOLUME_UP" -> volume(true)
                    "VOLUME_DOWN" -> volume(false)
                    "FLASHLIGHT_ON" -> torch(true)
                    "FLASHLIGHT_OFF" -> torch(false)
                    "WIFI_ON", "WIFI_OFF" -> wifi(cmd.type == "WIFI_ON")
                    "BLUETOOTH_ON", "BLUETOOTH_OFF" -> bluetooth(cmd.type == "BLUETOOTH_ON")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                result("Action fail ho gaya: ${e.message}")
            }
        }
    }

    private fun guardAccessibility(): Boolean {
        if (!REQUIRE_ACCESSIBILITY_FOR_OPEN || AccessibilityHelperService.isEnabled(ctx)) return true
        toast("Accessibility ON karo, tabhi MYRA ye kar paayegi")
        try { startAct(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (_: Exception) {}
        result("Accessibility service off hai, isliye command execute nahi hua. User ko settings me ON karne ko bolo.")
        return false
    }

    // ------------------------------------------------------------------ apps
    private fun openApp(rawName: String) {
        var name = rawName.lowercase().trim()
        name = ALIASES[name] ?: name
        val pm = ctx.packageManager

        var intent: Intent? = APP_MAP[name]?.let { pm.getLaunchIntentForPackage(it) }
        if (intent == null && name == "camera") {
            intent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
        }
        if (intent == null) {
            val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val match = pm.queryIntentActivities(main, 0).firstOrNull {
                it.loadLabel(pm).toString().lowercase().contains(name)
            }
            intent = match?.let { pm.getLaunchIntentForPackage(it.activityInfo.packageName) }
        }
        if (intent == null) { result("$rawName naam ka app nahi mila."); return }
        startAct(intent)
        result("$rawName khol diya.")
    }

    private fun closeApp() {
        val svc = AccessibilityHelperService.instance
        if (svc == null) { result("Accessibility service connected nahi hai."); return }
        svc.closeCurrentApp()
        result("App band kar diya (home pe aa gaye).")
    }

    // ------------------------------------------------------------------ contacts
    fun lookupContact(name: String): Pair<String, String>? {
        if (!has(Manifest.permission.READ_CONTACTS)) return null
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$name%"), null
        )?.use { if (it.moveToFirst()) return it.getString(0) to it.getString(1) }
        return null
    }

    private fun resolve(name: String?, number: String?): Pair<String, String>? {
        if (!number.isNullOrBlank()) return number to number
        if (name.isNullOrBlank()) return null
        return lookupContact(name)
    }

    private fun call(name: String?, number: String?) {
        if (!has(Manifest.permission.CALL_PHONE)) { result("Call permission nahi hai."); return }
        val c = resolve(name, number)
        if (c == null) { result("${name ?: "Contact"} contacts me nahi mila."); return }
        startAct(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(c.second))))
        result("${c.first} ko call laga di.")
    }

    private fun sms(name: String?, message: String) {
        val c = resolve(name, null)
        if (c == null) { result("${name ?: "Contact"} contacts me nahi mila."); return }
        startAct(Intent(Intent.ACTION_VIEW, Uri.parse("smsto:" + Uri.encode(c.second))).putExtra("sms_body", message))
        result("${c.first} ke liye SMS ready hai.")
    }

    private suspend fun whatsapp(name: String, message: String, call: Boolean) {
        val c = resolve(name, null)
        if (c == null) { result("$name contacts me nahi mila."); return }
        var digits = c.second.filter { it.isDigit() }
        if (digits.length == 10) digits = "91$digits"
        val url = "https://wa.me/$digits" + if (message.isNotBlank()) "?text=" + Uri.encode(message) else ""
        startAct(Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage("com.whatsapp"))
        val svc = AccessibilityHelperService.instance
        if (call) {
            delay(3000)
            val ok = svc?.clickOnText("Voice call") == true
            result(if (ok) "${c.first} ko WhatsApp call laga di." else "${c.first} ki WhatsApp chat khul gayi, call button manually dabana padega.")
        } else {
            if (message.isNotBlank() && svc != null) {
                delay(3000)
                svc.clickOnText("Send")
                result("${c.first} ko WhatsApp message bhej diya.")
            } else result("${c.first} ki WhatsApp chat khol di.")
        }
    }

    private fun primeAction(index: Int, isCall: Boolean) {
        val list = Prefs.getPrimeContacts(ctx)
        val pc = list.getOrNull(index)
        if (pc == null) { result("Prime contact set nahi hai. Settings me add karo."); return }
        if (isCall) call(null, pc.number)
        else {
            startAct(Intent(Intent.ACTION_VIEW, Uri.parse("smsto:" + Uri.encode(pc.number))))
            result("${pc.name} ke liye message ready hai.")
        }
    }

    // ------------------------------------------------------------------ call control
    fun acceptCall() {
        try {
            if (has(Manifest.permission.ANSWER_PHONE_CALLS)) {
                (ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager).acceptRingingCall()
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    fun rejectCall() {
        try {
            if (has(Manifest.permission.ANSWER_PHONE_CALLS)) {
                (ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager).endCall()
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    // ------------------------------------------------------------------ device
    private fun volume(up: Boolean) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        repeat(2) {
            am.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                if (it == 1) AudioManager.FLAG_SHOW_UI else 0
            )
        }
        result(if (up) "Volume badha diya." else "Volume kam kar diya.")
    }

    private fun torch(on: Boolean) {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull {
            val ch = cm.getCameraCharacteristics(it)
            ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
        if (id == null) { result("Is phone me flashlight nahi mili."); return }
        cm.setTorchMode(id, on)
        result(if (on) "Torch on kar di." else "Torch off kar di.")
    }

    // Android 10+ does not allow apps to toggle these silently -> open the system panel.
    private fun wifi(on: Boolean) {
        startAct(Intent(Settings.Panel.ACTION_WIFI))
        result("Android ki restriction ki wajah se WiFi ${if (on) "on" else "off"} karne ka panel khol diya, user ko toggle dabana hoga.")
    }

    private fun bluetooth(on: Boolean) {
        startAct(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        result("Bluetooth settings khol di, user ko ${if (on) "on" else "off"} karna hoga.")
    }
}
