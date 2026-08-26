package com.verba.interpretation.cloud

import android.content.Context
import java.util.UUID

interface InstallationIdStore {
    fun get(): String
}

/** Stable opaque installation identifier; it is unrelated to an entitlement. */
class SharedPreferencesInstallationIdStore(context: Context) : InstallationIdStore {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override fun get(): String = preferences.getString(INSTALLATION_ID, null) ?: UUID.randomUUID().toString().also { id ->
        preferences.edit().putString(INSTALLATION_ID, id).commit()
    }

    private companion object {
        const val PREFERENCES = "cloud_installation"
        const val INSTALLATION_ID = "install_id"
    }
}
