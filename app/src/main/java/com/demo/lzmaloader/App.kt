package com.demo.lzmaloader

import android.app.Application
import android.util.Log
import com.demo.soloader.SoLoader
import kotlinx.coroutines.MainScope

class App : Application() {

    // Application 生命周期与进程一致，使用 MainScope 驱动后台协程
    private val appScope = MainScope()

    override fun onCreate() {
        super.onCreate()
        instance = this

        val t = System.currentTimeMillis()

        // Step1: 初始化加载器（解析 manifest，fail-fast）
        SoLoader.init(this)

        // Step2: 同步加载 L0（无压缩 copy，耗时极短）
        SoLoader.initL0Blocking()
        Log.i("App", "L0 done in ${System.currentTimeMillis() - t}ms")

        // Step3: 异步加载 L1（IO 线程解压，不阻塞主线程/首帧渲染）
        SoLoader.initL1Async(appScope)
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
