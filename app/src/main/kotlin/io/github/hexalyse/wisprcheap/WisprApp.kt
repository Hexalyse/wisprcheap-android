package io.github.hexalyse.wisprcheap

import android.app.Application
import io.github.hexalyse.wisprcheap.runtime.AppGraph

class WisprApp : Application() {
    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.startup()
    }

    companion object {
        lateinit var graph: AppGraph
            private set
    }
}
