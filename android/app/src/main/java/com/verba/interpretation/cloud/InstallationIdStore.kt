package com.verba.interpretation.cloud

import android.content.Context
import java.util.UUID

interface InstallationIdStore {
    fun get(): String

    /** 账户删除后安全清除本机安装标识；下次访问会重新生成新值。 */
    fun clear()
}

/** Stable opaque installation identifier; it is unrelated to an entitlement. */
class SharedPreferencesInstallationIdStore(context: Context) : InstallationIdStore {
    private val preferences = runCatching {
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    }.getOrNull()

    override fun get(): String = read()?.takeIf(String::isNotEmpty) ?: UUID.randomUUID().toString().also { id ->
        preferences?.edit()?.putString(INSTALLATION_ID, id)?.commit()
    }

    override fun clear() {
        preferences?.edit()?.remove(INSTALLATION_ID)?.apply()
    }

    private fun read(): String? = preferences?.getString(INSTALLATION_ID, null)

    private companion object {
        const val PREFERENCES = "cloud_installation"
        const val INSTALLATION_ID = "install_id"
    }
}
