package com.demo.lzmaloader

import android.app.Application
import android.util.Log
import com.demo.soloader.SoLoader

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this

        val t = System.currentTimeMillis()

        // Step1: 初始化加载器（解析manifest）
        SoLoader.init(this)

        // Step2: 同步解压加载 L0 + L1（主线程）
        SoLoader.initBlocking()

        Log.i("App", "SoLoader init done in ${System.currentTimeMillis() - t}ms")
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
