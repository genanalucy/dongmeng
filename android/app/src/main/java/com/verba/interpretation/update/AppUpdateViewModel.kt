package com.verba.interpretation.update

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.verba.interpretation.BuildConfig
import com.verba.interpretation.cloud.CloudApi
import com.verba.interpretation.cloud.CloudEndpointSettings
import com.verba.interpretation.cloud.KeystoreTokenStore
import com.verba.interpretation.cloud.SharedPreferencesInstallationIdStore
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

data class AppUpdateProgress(val downloadedBytes: Long, val totalBytes: Long?) {
    val fraction: Float? get() = totalBytes?.takeIf { it > 0 }?.let { downloadedBytes.toFloat() / it }
}

data class AppUpdateInfo(
    val packageName: String,
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val releaseNotes: String,
    val forceUpdate: Boolean,
)

sealed interface AppUpdateState {
    data object Idle : AppUpdateState
    data object Checking : AppUpdateState
    data object Current : AppUpdateState
    data class Available(val update: AppUpdateInfo) : AppUpdateState
    data class Downloading(
        val update: AppUpdateInfo,
        val progress: AppUpdateProgress? = null,
        val verifying: Boolean = false,
    ) : AppUpdateState
    data class ReadyToInstall(val update: AppUpdateInfo, val apkUri: Uri) : AppUpdateState
    data class Failed(val message: String) : AppUpdateState
}

/** Public update endpoint; it never needs an account token. */
interface AppUpdateService { fun checkForUpdate(): AppUpdateInfo? }

/** Stores only update-prompt choices, never APK metadata or account credentials. */
interface AppUpdatePromptPreferences {
    fun shouldPrompt(versionCode: Int): Boolean
    fun ignoreVersion(versionCode: Int)
    fun disableAutomaticPrompts()
}

private class SharedPreferencesAppUpdatePromptPreferences(context: Context) : AppUpdatePromptPreferences {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override fun shouldPrompt(versionCode: Int): Boolean =
        preferences.getBoolean(AUTOMATIC_PROMPTS_ENABLED, true) && preferences.getInt(IGNORED_VERSION_CODE, NO_VERSION) != versionCode

    override fun ignoreVersion(versionCode: Int) {
        preferences.edit().putInt(IGNORED_VERSION_CODE, versionCode).apply()
    }

    override fun disableAutomaticPrompts() {
        preferences.edit().putBoolean(AUTOMATIC_PROMPTS_ENABLED, false).apply()
    }

    private companion object {
        const val PREFERENCES = "app_update_preferences"
        const val AUTOMATIC_PROMPTS_ENABLED = "automatic_prompts_enabled"
        const val IGNORED_VERSION_CODE = "ignored_version_code"
        const val NO_VERSION = -1
    }
}

class CloudAppUpdateService(private val api: CloudApi) : AppUpdateService {
    override fun checkForUpdate(): AppUpdateInfo? = api.appUpdate()?.let {
        AppUpdateInfo(it.packageName, it.versionCode, it.versionName, it.apkUrl, it.apkSha256, it.releaseNotes, it.forceUpdate)
    }
}

class AppUpdateViewModel @JvmOverloads constructor(
    application: Application,
    private val service: AppUpdateService = CloudAppUpdateService(
        CloudApi(
            CloudEndpointSettings(application),
            KeystoreTokenStore(application),
            SharedPreferencesInstallationIdStore(application),
        ),
    ),
    private val promptPreferences: AppUpdatePromptPreferences = SharedPreferencesAppUpdatePromptPreferences(application),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AndroidViewModel(application) {
    private val client = OkHttpClient()
    private val mutableState = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    val state: StateFlow<AppUpdateState> = mutableState.asStateFlow()
    private val mutableAutomaticPrompt = MutableStateFlow<AppUpdateInfo?>(null)
    val automaticPrompt: StateFlow<AppUpdateInfo?> = mutableAutomaticPrompt.asStateFlow()

    /** Manual checks always report the available version, regardless of automatic prompt preferences. */
    fun check() = viewModelScope.launch {
        mutableState.value = AppUpdateState.Checking
        mutableState.value = try {
            val update = withContext(dispatcher) { service.checkForUpdate() }
            update.asAvailableState()
        } catch (_: Exception) {
            AppUpdateState.Failed("暂时无法检查更新，请确认网络后重试。")
        }
    }

    /** Automatic checks are opt-out for ordinary updates; force updates always surface. */
    fun checkAutomatically() = viewModelScope.launch {
        val update = runCatching { withContext(dispatcher) { service.checkForUpdate() } }.getOrNull() ?: return@launch
        if (update.isNewerForThisApp() && (update.forceUpdate || promptPreferences.shouldPrompt(update.versionCode))) {
            mutableAutomaticPrompt.value = update
        }
    }

    fun ignoreAutomaticPromptVersion() {
        val update = mutableAutomaticPrompt.value ?: return
        if (update.forceUpdate) return
        promptPreferences.ignoreVersion(update.versionCode)
        mutableAutomaticPrompt.value = null
    }

    fun disableAutomaticPrompts() {
        val update = mutableAutomaticPrompt.value ?: return
        if (update.forceUpdate) return
        promptPreferences.disableAutomaticPrompts()
        mutableAutomaticPrompt.value = null
    }

    fun downloadFromAutomaticPrompt() {
        val update = mutableAutomaticPrompt.value ?: return
        download(update)
    }

    fun download(update: AppUpdateInfo) = viewModelScope.launch {
        mutableState.value = AppUpdateState.Downloading(update)
        mutableState.value = try {
            val apk = withContext(dispatcher) {
                downloadAndVerify(update) { progress, verifying ->
                    mutableState.value = AppUpdateState.Downloading(update, progress, verifying)
                }
            }
            val uri = FileProvider.getUriForFile(getApplication(), "${BuildConfig.APPLICATION_ID}.updates", apk)
            AppUpdateState.ReadyToInstall(update, uri)
        } catch (_: Exception) {
            AppUpdateState.Failed("更新包下载或校验失败，未安装该文件。")
        }
    }

    fun installerIntent(uri: Uri): Intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun downloadAndVerify(
        update: AppUpdateInfo,
        onProgress: (AppUpdateProgress?, Boolean) -> Unit,
    ): File {
        val request = Request.Builder().url(update.apkUrl).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful || !isSafeHTTPS(response.request.url.toString())) throw IOException("APK request failed")
            var prior = response.priorResponse
            while (prior != null) {
                if (!isSafeHTTPS(prior.request.url.toString())) throw IOException("unsafe APK redirect")
                prior = prior.priorResponse
            }
            val body = response.body ?: throw IOException("APK response missing body")
            if (body.contentLength() > MAX_APK_BYTES) throw IOException("APK too large")
            val directory = File(getApplication<Application>().cacheDir, "updates").apply { mkdirs() }
            val temporary = File(directory, "update.apk.part")
            val target = File(directory, "update.apk")
            val digest = MessageDigest.getInstance("SHA-256")
            body.byteStream().use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var downloaded = 0L
                    onProgress(AppUpdateProgress(0L, body.contentLength().takeIf { it >= 0 }), false)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        downloaded += count
                        if (downloaded > MAX_APK_BYTES) throw IOException("APK too large")
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        onProgress(AppUpdateProgress(downloaded, body.contentLength().takeIf { it >= 0 }), false)
                    }
                }
            }
            onProgress(null, true)
            if (!digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }.equals(update.sha256, ignoreCase = true)) {
                temporary.delete()
                throw IOException("APK digest mismatch")
            }
            if (target.exists() && !target.delete()) throw IOException("APK replacement failed")
            if (!temporary.renameTo(target)) throw IOException("APK move failed")
            return target
        }
    }

    private fun AppUpdateInfo.isNewerForThisApp(): Boolean =
        packageName == BuildConfig.APPLICATION_ID && versionCode > BuildConfig.VERSION_CODE

    private fun AppUpdateInfo?.asAvailableState(): AppUpdateState = when {
        this == null || !isNewerForThisApp() -> AppUpdateState.Current
        else -> AppUpdateState.Available(this)
    }

    private fun isSafeHTTPS(raw: String): Boolean = runCatching {
        val uri = Uri.parse(raw)
        uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null
    }.getOrDefault(false)

    companion object { private const val MAX_APK_BYTES = 200L * 1024L * 1024L }
}
