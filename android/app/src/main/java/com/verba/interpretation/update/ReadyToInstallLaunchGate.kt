package com.verba.interpretation.update

import android.net.Uri

/** Ensures a single composition root starts each downloaded update at most once. */
class ReadyToInstallLaunchGate {
    private var launchedVersionCode: Int? = null

    fun consume(state: AppUpdateState, launch: (Uri) -> Unit) {
        val ready = state as? AppUpdateState.ReadyToInstall ?: return
        consume(ready.update.versionCode) { launch(ready.apkUri) }
    }

    fun consume(versionCode: Int, launch: () -> Unit) {
        if (launchedVersionCode == versionCode) return
        launchedVersionCode = versionCode
        launch()
    }
}
