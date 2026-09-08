package com.verba.interpretation.update

import android.app.Application
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

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
    data class Downloading(val update: AppUpdateInfo) : AppUpdateState
    data class ReadyToInstall(val update: AppUpdateInfo, val apkUri: Uri) : AppUpdateState
    data class Failed(val message: String) : AppUpdateState
}

/** Public update endpoint; it never needs an account token. */
interface AppUpdateService { fun checkForUpdate(): AppUpdateInfo? }

class CloudAppUpdateService(private val api: CloudApi) : AppUpdateService {
    override fun checkForUpdate(): AppUpdateInfo? = api.appUpdate()?.let {
        AppUpdateInfo(it.packageName, it.versionCode, it.versionName, it.apkUrl, it.apkSha256, it.releaseNotes, it.forceUpdate)
    }
}

class AppUpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val service = CloudAppUpdateService(
        CloudApi(
            CloudEndpointSettings(application),
            KeystoreTokenStore(application),
            SharedPreferencesInstallationIdStore(application),
        ),
    )
    private val client = OkHttpClient()
    private val mutableState = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    val state: StateFlow<AppUpdateState> = mutableState.asStateFlow()

    fun check() = viewModelScope.launch {
        mutableState.value = AppUpdateState.Checking
        mutableState.value = try {
            val update = withContext(Dispatchers.IO) { service.checkForUpdate() }
            when {
                update == null || update.packageName != BuildConfig.APPLICATION_ID || update.versionCode <= BuildConfig.VERSION_CODE -> AppUpdateState.Current
                else -> AppUpdateState.Available(update)
            }
        } catch (_: Exception) {
            AppUpdateState.Failed("暂时无法检查更新，请确认网络后重试。")
        }
    }

    fun download(update: AppUpdateInfo) = viewModelScope.launch {
        mutableState.value = AppUpdateState.Downloading(update)
        mutableState.value = try {
            val apk = withContext(Dispatchers.IO) { downloadAndVerify(update) }
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

    private fun downloadAndVerify(update: AppUpdateInfo): File {
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
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        downloaded += count
                        if (downloaded > MAX_APK_BYTES) throw IOException("APK too large")
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                    }
                }
            }
            if (!digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }.equals(update.sha256, ignoreCase = true)) {
                temporary.delete()
                throw IOException("APK digest mismatch")
            }
            if (target.exists() && !target.delete()) throw IOException("APK replacement failed")
            if (!temporary.renameTo(target)) throw IOException("APK move failed")
            return target
        }
    }

    private fun isSafeHTTPS(raw: String): Boolean = runCatching {
        val uri = Uri.parse(raw)
        uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null
    }.getOrDefault(false)

    companion object { private const val MAX_APK_BYTES = 200L * 1024L * 1024L }
}
