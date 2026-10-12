package com.hplay.app

import android.app.Application
import android.content.Context
import com.hplay.app.discovery.DiscoveryRepository
import com.hplay.app.discovery.ProfileCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class HPlayApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val profiles by lazy { ProfileCache(this) }
    val discovery by lazy { DiscoveryRepository(this, appScope, profiles) }
}

val Context.hplay: HPlayApp get() = applicationContext as HPlayApp
