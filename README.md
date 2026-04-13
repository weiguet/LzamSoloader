# LzmaLoaderDemo

Android SO 分级加载方案：SO 文件用 LZMA 压缩存入 assets，运行时按需解压加载。  
核心逻辑封装为 **Gradle 插件 + Android 库**，其他项目只需 `apply plugin` 即可接入。

> 纯 Kotlin/Java 实现，无 NDK 依赖。

---

## 项目结构

```
LzmaLoaderDemo/
├── app/                              # Demo 应用
│   └── src/main/
│       ├── assets/so/                # 由 packSoAssets 任务自动生成，勿手动修改
│       │   ├── manifest.json
│       │   ├── l0/libcore.so         # L0: 不压缩，直接 copy
│       │   ├── l0/libcrash.so
│       │   ├── l1/libnetwork.so.lzma # L1: LZMA 轻压缩
│       │   ├── l1/libui.so.lzma
│       │   ├── l2/libim.so.lzma      # L2: LZMA 标准压缩
│       │   ├── l2/libpayment.so.lzma
│       │   ├── l3/libmap.so.lzma     # L3: 高压缩，按需加载
│       │   └── l3/libvideo.so.lzma
│       └── java/com/demo/lzmaloader/
│           ├── App.kt
│           └── MainActivity.kt
│
├── soloader/                         # Android 运行时库（AAR，纯 Kotlin，无 NDK）
│   ├── settings.gradle               # 独立项目，支持 includeBuild
│   ├── build.gradle
│   └── src/main/java/com/demo/soloader/
│       ├── SoLoader.kt               # 分级调度核心
│       ├── LZMANative.kt             # XZ 解压 + asset copy（纯 Kotlin）
│       └── SOManifest.kt             # 数据模型 + LoadState
│
├── so-loader-plugin/                 # Gradle 插件
│   ├── settings.gradle.kts           # 独立项目，支持 includeBuild
│   ├── build.gradle.kts
│   └── src/main/
│       ├── resources/pack_so.py      # 压缩脚本，内置于插件 jar
│       └── kotlin/com/demo/soloader/gradle/
│           ├── SoLoaderPlugin.kt         # 插件入口
│           ├── SoLoaderExtension.kt      # DSL 扩展
│           ├── PackSoAssetsTask.kt       # 压缩打包任务
│           └── GenerateSoManifestTask.kt # 自动生成 manifest 模板
│
└── tools/
    ├── so_manifest.json              # SO 分级配置（手动维护 level / deps）
    └── so_src/arm64-v8a/             # 原始 SO 文件
```

---

## 加载时序

```
App.onCreate()
  └── SoLoader.init(this)               # 解析 manifest（失败立即抛出）
  └── SoLoader.initL0Blocking()         # 同步，主线程（无解压，copy 极快）
        └── L0: asset → filesDir/so/
  └── SoLoader.initL1Async(appScope)    # 异步，IO 线程（不阻塞首帧渲染）
        └── L1: XZ 解压 → filesDir/so/

Activity.onCreate() → 首帧 post
  └── SoLoader.preloadL2(lifecycleScope)
        └── L2: 后台 IO 协程解压

用户触发
  └── SoLoader.loadOnDemand("libvideo.so")
        └── L3: 按需解压（递归加载依赖）
```

---

## 缓存策略

解压产物写入 `Context.filesDir/so/`，缓存 key 为 `versionCode + orig_md5`。  
APK 升级后自动失效重新解压；同版本二次启动走缓存，跳过解压。

---

## 在其他项目中接入

### 方式一：本地源码引用（推荐，开发阶段）

**Step 1 — 配置 `settings.gradle`**

```groovy
pluginManagement {
    includeBuild('/path/to/LzmaLoaderDemo/so-loader-plugin') // 引入本地插件
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// 将 com.demo:soloader 解析到本地模块，无需发布
includeBuild('/path/to/LzmaLoaderDemo/soloader') {
    dependencySubstitution {
        substitute module('com.demo:soloader') using project(':')
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
```

**Step 2 — 配置 `app/build.gradle`**

```groovy
plugins {
    id 'com.android.application'
    id 'org.jetbrains.kotlin.android'
    id 'com.demo.so-loader'   // ← 一行接入
}

soLoader {
    soSrcDir     = file("libs/so/arm64-v8a")        // 原始 SO 目录
    manifestFile = file("libs/so/so_manifest.json")  // 分级配置文件
    // outDir    = file("src/main/assets/so")         // 可选，默认值如此
    // algorithm = 'lzma'                             // 可选，默认 lzma
}
```

插件自动完成：
- `aaptOptions { noCompress 'lzma', 'so' }`
- `buildFeatures { buildConfig true }`
- 每个 variant 注册 `packSoAssets{Variant}` 任务，在 `mergeAssets` 前执行
- 自动添加 `implementation 'com.demo:soloader:1.0.0'` 运行时依赖
- `pack_so.py` 内置于插件 jar，无需额外提供

**Step 3 — 把 SO 放入 `soSrcDir`，生成 manifest**

```bash
./gradlew generateSoManifest
```

扫描 `soSrcDir` 下所有 `.so` 文件，生成 `so_manifest.json` 模板（默认全部 level=1，deps=[]）。  
**手动编辑** manifest 设置正确的 level 和 deps。后续新增 SO 再跑一次，只追加新条目，不覆盖已有配置。

**Step 4 — 初始化运行时**

```kotlin
import com.demo.soloader.SoLoader
import kotlinx.coroutines.MainScope

class App : Application() {
    private val appScope = MainScope()

    override fun onCreate() {
        super.onCreate()
        SoLoader.init(this)
        SoLoader.initL0Blocking()       // 同步加载 L0（极快）
        SoLoader.initL1Async(appScope)  // 异步加载 L1，不阻塞主线程
    }
}
```

L2 / L3 按需触发：

```kotlin
// 首帧后预加载 L2（在 Activity/Fragment 中）
binding.root.post { SoLoader.preloadL2(lifecycleScope) }

// 按需加载 L3
lifecycleScope.launch { SoLoader.loadOnDemand("libvideo.so") }
```

**Step 5 — 构建**

```bash
./gradlew assembleRelease
# packSoAssetsRelease 会在 mergeReleaseAssets 前自动执行
```

---

### 方式二：发布到 mavenLocal（CI / 团队共享）

在本仓库执行一次发布：

```bash
./gradlew :soloader:publishToMavenLocal
cd so-loader-plugin && ./gradlew publishToMavenLocal
```

其他项目 `settings.gradle` 添加 `mavenLocal()`：

```groovy
pluginManagement {
    repositories {
        mavenLocal()
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        mavenLocal()
        google()
        mavenCentral()
    }
}
```

之后同方式一 Step 2–5 接入，无需 `includeBuild`。

---

## so_manifest.json 格式

由 `./gradlew generateSoManifest` 生成模板，手动维护 `level` 和 `deps`：

```json
{
  "levels": {
    "0": [
      { "name": "libcore.so",  "deps": [] },
      { "name": "libcrash.so", "deps": [] }
    ],
    "1": [
      { "name": "libnetwork.so", "deps": ["libcore.so"] },
      { "name": "libui.so",      "deps": ["libcore.so"] }
    ],
    "2": [
      { "name": "libim.so",      "deps": ["libnetwork.so"] },
      { "name": "libpayment.so", "deps": ["libcore.so"] }
    ],
    "3": [
      { "name": "libmap.so",   "deps": [] },
      { "name": "libvideo.so", "deps": [] }
    ]
  }
}
```

| Level | 触发时机 | 压缩策略 |
|-------|----------|----------|
| L0 | `Application.onCreate` 同步 | 不压缩（直接 copy，优先启动速度） |
| L1 | `Application.onCreate` IO 线程异步 | LZMA 轻压缩 |
| L2 | 首帧渲染后，后台 IO 线程 | LZMA 标准压缩 |
| L3 | 业务按需触发 | LZMA 极限压缩 |

---

## 运行效果（Demo）

- 启动后 L0 同步 copy，L1 异步解压，显示各 SO 耗时
- 首帧后 L2 后台加载，状态实时刷新
- 点击「按需加载 L3」触发 L3 解压
- 点击「清除缓存」验证二次启动走缓存路径（跳过解压）
