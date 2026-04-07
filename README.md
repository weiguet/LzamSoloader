# LzmaLoaderDemo

Android SO 分级加载 Demo：把 SO 用 LZMA 压缩放到 assets，运行时解压加载。

## 项目结构

```
LzmaLoaderDemo/
├── app/src/main/
│   ├── assets/so/
│   │   ├── manifest.json        # SO清单（由 pack_so.py 生成）
│   │   ├── l0/libcore.so        # L0: 不压缩
│   │   ├── l0/libcrash.so
│   │   ├── l1/libui.so.gz       # L1: 轻压缩（Demo用gzip，实际换LZMA）
│   │   ├── l1/libnetwork.so.gz
│   │   ├── l2/libpayment.so.gz  # L2: 标准压缩
│   │   ├── l2/libim.so.gz
│   │   ├── l3/libvideo.so.gz    # L3: 高压缩，按需加载
│   │   └── l3/libmap.so.gz
│   ├── cpp/
│   │   └── lzma_jni.cpp         # Native 解压层（当前用 zlib/gzip）
│   └── java/com/demo/lzmaloader/
│       ├── App.kt               # Application，L0+L1同步加载
│       ├── SoLoader.kt          # 核心加载调度
│       ├── LZMANative.kt        # JNI 桥接
│       ├── SOManifest.kt        # 数据模型
│       └── MainActivity.kt      # UI 展示
└── tools/
    └── pack_so.py               # 构建期压缩脚本
```

## 加载时序

```
App.onCreate()
  └── SoLoader.initBlocking()
        ├── L0: copy asset -> filesDir -> System.load  (不解压)
        └── L1: 解压 asset -> filesDir -> System.load  (同步)

Activity.onCreate() -> 首帧 post
  └── SoLoader.preloadL2()
        └── L2: 后台解压 -> System.load

业务触发（点击按钮）
  └── SoLoader.loadOnDemand("libvideo.so")
        └── L3: 按需解压 -> System.load
```

## 替换为真实 LZMA

Demo 用 gzip (zlib) 演示完整架构。替换 LZMA 只需两步：

**Step 1** — 打包脚本换压缩算法：
```bash
python3 tools/pack_so.py \
  --so-dir jniLibs/arm64-v8a \
  --manifest so_manifest.json \
  --out app/src/main/assets/so \
  --algo lzma          # ← 改这里
```

**Step 2** — `lzma_jni.cpp` 的 `decompress_gz()` 换成 LZMA 实现：
```cpp
// 换掉这个函数体（接口不变）
static bool decompress_gz(...) {
    // 替换为:
    lzma_stream strm = LZMA_STREAM_INIT;
    lzma_ret ret = lzma_stream_decoder(&strm, 256 * 1024 * 1024, 0);
    strm.next_in = src;   strm.avail_in = src_size;
    strm.next_out = dst;  strm.avail_out = dst_size;
    ret = lzma_code(&strm, LZMA_FINISH);
    *out_written = dst_size - strm.avail_out;
    lzma_end(&strm);
    return ret == LZMA_STREAM_END;
}
```

同时在 `CMakeLists.txt` 链接 `liblzma.a`（NDK 交叉编译，参考上方对话）。

## 运行效果

- 启动后 L0+L1 自动解压加载，显示耗时
- 首帧后 L2 后台加载，状态实时更新
- 点击「按需加载 L3」触发 L3 解压
- 点击「清除缓存」验证二次启动走缓存路径

## 缓存策略

解压产物写入 `filesDir/so/`，用 `VERSION_CODE + orig_md5` 做缓存 key。
APK 更新后自动失效，重新解压。
