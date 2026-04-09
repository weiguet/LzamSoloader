# SO 分级加载方案 — 技术方案文档

## 1. 背景与目标

### 1.1 问题描述

大型 Android 应用的 SO 文件总量通常达到数十 MB，直接打入 APK 有以下痛点：

- **包体积大**：SO 文件无法被 APK 的 ZIP 压缩有效压缩（ELF 格式熵值高）
- **启动慢**：所有 SO 在冷启动时全量加载，即使大多数功能首屏不需要
- **动态下发难**：按模块动态下发 SO 需要复杂的下载 + 校验 + 加载链路

### 1.2 目标

| 目标 | 说明 |
|------|------|
| 减小包体积 | 对 SO 使用 LZMA/XZ 压缩，压缩率通常达 40-60% |
| 分级加载 | 按启动优先级分 L0~L3，只在需要时解压加载 |
| 缓存复用 | 解压产物持久化，同版本二次启动跳过解压 |
| 工程可复用 | 封装为 Gradle 插件 + Android 库，一行接入其他项目 |

---

## 2. 整体架构

```
┌─────────────────────────────────────────────────────────────────┐
│                        LzmaLoaderDemo                           │
│                                                                 │
│   ┌──────────────┐   apply plugin   ┌──────────────────────┐   │
│   │   app 模块   │ ───────────────► │  so-loader-plugin    │   │
│   │  (Demo App)  │                  │  (Gradle 插件)        │   │
│   └──────┬───────┘                  └──────────┬───────────┘   │
│          │ implementation                       │ 构建期执行     │
│          ▼                                      ▼               │
│   ┌──────────────┐              ┌───────────────────────────┐  │
│   │   soloader   │              │       pack_so.py          │  │
│   │ (Android 库) │              │  (内置于插件 jar，压缩脚本) │  │
│   └──────────────┘              └───────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
```

### 三个核心组件

| 组件 | 类型 | 职责 |
|------|------|------|
| `so-loader-plugin` | Gradle 插件 | 构建期：压缩 SO、生成 manifest、注入依赖 |
| `soloader` | Android Library (AAR) | 运行期：分级调度、解压、缓存、System.load |
| `app` | Android Application | Demo：演示接入效果 |

---

## 3. 构建期流程（Gradle 插件）

### 3.1 插件组件结构

```
so-loader-plugin/
└── src/main/
    ├── resources/
    │   └── pack_so.py                  # 压缩脚本，打包进插件 jar
    └── kotlin/com/demo/soloader/gradle/
        ├── SoLoaderPlugin.kt           # 插件入口，注册任务、配置 AGP
        ├── SoLoaderExtension.kt        # DSL 扩展（soLoader { ... }）
        ├── PackSoAssetsTask.kt         # 压缩打包任务
        └── GenerateSoManifestTask.kt   # 生成 manifest 模板任务
```

### 3.2 DSL 扩展

```groovy
soLoader {
    soSrcDir     = file("tools/so_src/arm64-v8a")   // 原始 SO 目录（必填）
    manifestFile = file("tools/so_manifest.json")    // 分级配置（必填）
    outDir       = file("src/main/assets/so")        // 输出目录（可选，有默认值）
    algorithm    = 'lzma'                            // 压缩算法（可选，默认 lzma）
}
```

### 3.3 插件注册流程

```
SoLoaderPlugin.apply()
  ├── extensions.create("soLoader", SoLoaderExtension)   // 注册 DSL
  ├── tasks.register("generateSoManifest", ...)          // 立即注册（不需要 afterEvaluate）
  └── afterEvaluate {
        ├── android.aaptOptions.noCompress("lzma", "so") // 防止 APK 二次压缩
        ├── android.buildFeatures.buildConfig = true
        ├── 遍历每个 variant {
        │     ├── tasks.register("packSoAssets{Variant}", PackSoAssetsTask)
        │     ├── merge{Variant}Assets.dependsOn(packTask)   // 打包前完成压缩
        │     └── lint{Variant}.mustRunAfter(packTask)       // 消除隐式依赖警告
        │   }
        └── dependencies.add("implementation", "com.demo:soloader:1.0.0")
      }
```

### 3.4 PackSoAssetsTask — 压缩打包

```
PackSoAssetsTask.pack()
  ├── 从插件 jar 内提取 /pack_so.py → temporaryDir/pack_so.py
  └── exec("python3 pack_so.py --so-dir ... --manifest ... --out ... --algo lzma")
```

`pack_so.py` 执行逻辑：

```
读取 so_manifest.json
  └── 遍历每个 SO 条目
        ├── 按 level 决定压缩策略
        │     L0: 直接 copy（不压缩）
        │     L1: lzma preset=1（轻压缩，速度快）
        │     L2: lzma preset=6（标准压缩）
        │     L3: lzma preset=9（极限压缩）
        ├── 写入 outDir/l{level}/{name}.so 或 {name}.so.lzma
        └── 计算 orig_md5、orig_size、compressed_size

输出 outDir/manifest.json（供运行时解析）
```

### 3.5 GenerateSoManifestTask — 生成 manifest 模板

用于「SO 由第三方提供、开发者手动接入」的场景：

```bash
./gradlew generateSoManifest
```

```
扫描 soSrcDir 下所有 *.so
  ├── 若 manifest 不存在：全部写入 level=1，deps=[]
  └── 若已存在：只追加新增 SO，保留已有手动配置
      （开发者手动编辑 level 和 deps）
```

---

## 4. 运行期流程（Android 库）

### 4.1 库组件结构

```
soloader/
└── src/main/
    ├── cpp/
    │   ├── CMakeLists.txt
    │   └── lzma_jni.cpp        # Native：带 fsync 的 asset copy + MD5 校验
    └── java/com/demo/soloader/
        ├── SoLoader.kt         # 分级调度核心（单例）
        ├── LZMANative.kt       # JNI 桥接 + XZ 解压（Kotlin 侧）
        └── SOManifest.kt       # 数据模型 + LoadState
```

### 4.2 数据模型

**manifest.json 结构（运行时版本，由 pack_so.py 生成）：**

```json
{
  "version": 1,
  "abi": "arm64-v8a",
  "entries": {
    "libcore.so": {
      "level": 0,
      "asset_path": "so/l0/libcore.so",
      "compressed": false,
      "orig_size": 2097152,
      "compressed_size": 2097152,
      "orig_md5": "abc123...",
      "deps": []
    },
    "libnetwork.so": {
      "level": 1,
      "asset_path": "so/l1/libnetwork.so.lzma",
      "compressed": true,
      "orig_size": 4194304,
      "compressed_size": 1048576,
      "orig_md5": "def456...",
      "deps": ["libcore.so"]
    }
  }
}
```

**LoadState 状态机：**

```
Pending → Loading → Done(ms)
                  → Error(msg)
```

### 4.3 分级加载时序

```
Application.onCreate()
  └── SoLoader.init(context)
        ├── 从 PackageManager 读取 versionCode（用于缓存校验）
        ├── 解析 assets/so/manifest.json
        └── 初始化所有条目 loadStates = Pending

  └── SoLoader.initBlocking()        // 同步，阻塞主线程
        ├── L0: copyAsset（native fsync copy）
        └── L1: XZ 解压 → filesDir/so/

Activity.onCreate() → binding.root.post {
  └── SoLoader.preloadL2(lifecycleScope)
        └── Dispatchers.IO 协程
              └── L2: XZ 解压（后台，不阻塞 UI）
}

用户行为触发
  └── SoLoader.loadOnDemand("libvideo.so")
        ├── 递归确保 deps 已加载
        └── L3: XZ 解压
```

### 4.4 ensureAndLoad — 核心加载逻辑

```kotlin
@Synchronized
fun ensureAndLoad(entry: SOEntry): Boolean {
    if (loaded[entry.name] == true) return true   // 幂等保护

    updateState(entry.name, LoadState.Loading)

    if (!isCacheValid(entry, outFile)) {
        // 缓存未命中：解压 or copy
        val ok = if (entry.compressed)
            LZMANative.decompress(assetMgr, entry.assetPath, dstPath, entry.origSize)
        else
            LZMANative.copyAsset(assetMgr, entry.assetPath, dstPath)

        // 写缓存标记：key=versionCode_origMd5
        prefs.edit().putString(cacheKey(entry.name), cacheTag(entry)).apply()
    }

    System.load(outFile.absolutePath)             // 真实场景替换 simulateLoad
    loaded[entry.name] = true
    updateState(entry.name, LoadState.Done(ms))
}
```

### 4.5 缓存策略

```
缓存有效条件（三者同时满足）：
  1. filesDir/so/{name}.so 文件存在
  2. 文件大小 == orig_size（快速完整性检查）
  3. SharedPreferences["v_{name}"] == "{versionCode}_{origMd5}"

缓存失效场景：
  - APK 升级（versionCode 变化）
  - SO 文件内容变更（orig_md5 变化）
  - 用户手动清除 filesDir（文件不存在）
```

### 4.6 Native 层（lzma_jni.cpp）

| JNI 方法 | 作用 |
|----------|------|
| `copyAsset` | 用 AAssetManager 读取 asset，写入 dst，调用 `fsync()` 确保落盘 |
| `verifyMd5` | 对文件计算 MD5，与 manifest 中 orig_md5 比对 |

XZ 解压在 Kotlin 层通过 `org.tukaani:xz` 库完成（纯 Java，无需 NDK）。

---

## 5. 压缩策略设计

| Level | 触发时机 | 压缩 preset | 原因 |
|-------|----------|------------|------|
| L0 | 启动同步 | 不压缩 | 启动链路最关键，copy 比解压快 |
| L1 | 启动同步 | preset=1 | 轻压缩，节省包体积同时保持解压速度 |
| L2 | 首帧后后台 | preset=6 | 用户已看到 UI，后台解压不影响体验 |
| L3 | 按需触发 | preset=9 | 非高频，极限压缩最大化节省空间 |

---

## 6. 模块间依赖关系

```
┌──────────────────────────────────────────────────────┐
│  构建期依赖（Gradle）                                  │
│                                                       │
│  app ──apply──► so-loader-plugin                     │
│                      │                               │
│                      ├── compileOnly AGP 8.1.0       │
│                      ├── 自动注入 implementation      │
│                      │   com.demo:soloader:1.0.0      │
│                      └── pack_so.py 内置于 jar       │
└──────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────┐
│  运行期依赖（Android）                                 │
│                                                       │
│  app ──implementation──► soloader (AAR)              │
│                              │                       │
│                              ├── org.tukaani:xz      │
│                              ├── com.google.code.    │
│                              │   gson:gson           │
│                              └── liblzma_loader.so   │
│                                  (CMake NDK 编译)     │
└──────────────────────────────────────────────────────┘
```

---

## 7. 接入方式

### 方式一：本地源码引用（开发阶段推荐）

**settings.gradle：**

```groovy
pluginManagement {
    includeBuild('/path/to/LzmaLoaderDemo/so-loader-plugin')
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}

includeBuild('/path/to/LzmaLoaderDemo/soloader') {
    dependencySubstitution {
        substitute module('com.demo:soloader') using project(':')
    }
}
```

**app/build.gradle：**

```groovy
plugins {
    id 'com.android.application'
    id 'org.jetbrains.kotlin.android'
    id 'com.demo.so-loader'
}

soLoader {
    soSrcDir     = file("libs/so/arm64-v8a")
    manifestFile = file("libs/so/so_manifest.json")
}
```

### 方式二：发布到 mavenLocal（团队共享）

```bash
# 发布一次
./gradlew :soloader:publishToMavenLocal
cd so-loader-plugin && ./gradlew publishToMavenLocal
```

settings.gradle 中将 `includeBuild` 替换为 `mavenLocal()` 仓库即可，其余接入步骤相同。

---

## 8. 关键设计决策

### 8.1 为什么用 XZ/LZMA 而不是 zlib

- ELF 格式中代码段重复模式多，LZMA 字典压缩比 zlib 高 30~50%
- XZ 有纯 Java 实现（`org.tukaani:xz`），无需额外 NDK 代码

### 8.2 为什么 aaptOptions.noCompress

APK 本质是 ZIP，`aapt` 默认对 assets 再次 ZIP 压缩。  
对已经 LZMA 压缩的 `.lzma` 文件二次 ZIP 压缩不仅无效，还会导致运行时无法用 `AAssetManager` 做 mmap（Android 要求 asset 以 stored 模式存储才能 mmap）。

### 8.3 为什么不用 BuildConfig.VERSION_CODE

`soloader` 是 Android Library 模块，Library 的 `BuildConfig.VERSION_CODE` 永远是 0（Library 没有版本概念）。  
改为在运行时通过 `PackageManager.getPackageInfo()` 读取宿主 App 的实际 versionCode，保证缓存校验正确。

### 8.4 pack_so.py 内置于插件 jar

- 消费方不需要维护脚本文件，`apply plugin` 即可使用
- `PackSoAssetsTask` 在执行时从 jar 内提取到 `temporaryDir`，运行后自动清理
- 脚本版本与插件版本一致，避免版本漂移

### 8.5 Gradle 任务依赖设计

```
packSoAssets{Variant}
  ▲ dependsOn（强依赖，必须先完成）
merge{Variant}Assets

lint{Variant}
  ▲ mustRunAfter（弱约束，避免隐式依赖警告，不要求 lint 一定运行）
packSoAssets{Variant}
```

---

## 9. 目录结构速查

```
LzmaLoaderDemo/
├── app/                              # Demo 应用
│   └── src/main/assets/so/           # packSoAssets 任务自动生成，勿手动修改
│       ├── manifest.json
│       ├── l0/libcore.so
│       ├── l1/libnetwork.so.lzma
│       ├── l2/libim.so.lzma
│       └── l3/libvideo.so.lzma
│
├── soloader/                         # 运行时库（AAR）
│   └── src/main/
│       ├── cpp/lzma_jni.cpp          # Native copyAsset + MD5
│       └── java/com/demo/soloader/
│           ├── SoLoader.kt           # 分级调度核心
│           ├── LZMANative.kt         # JNI 桥接 + XZ 解压
│           └── SOManifest.kt         # 数据模型 + LoadState
│
├── so-loader-plugin/                 # Gradle 插件
│   └── src/main/
│       ├── resources/pack_so.py      # 内置压缩脚本
│       └── kotlin/com/demo/soloader/gradle/
│           ├── SoLoaderPlugin.kt
│           ├── SoLoaderExtension.kt
│           ├── PackSoAssetsTask.kt
│           └── GenerateSoManifestTask.kt
│
└── tools/
    ├── so_manifest.json              # 分级配置（手动维护）
    └── so_src/arm64-v8a/             # 原始 SO 文件
```
