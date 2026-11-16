package com.verba.interpretation.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Owns a session only while its navigation destination is active, not while it fades out. */
@Composable
internal fun WorkbenchSessionEffect(active: Boolean, onStop: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val stop by rememberUpdatedState(onStop)
    DisposableEffect(lifecycleOwner, active) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) stop()
        }
        if (active) lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            if (active) {
                lifecycleOwner.lifecycle.removeObserver(observer)
                stop()
            }
        }
    }
}
