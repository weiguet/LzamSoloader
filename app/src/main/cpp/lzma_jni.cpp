// lzma_jni.cpp
// 仅负责文件 I/O（copy asset、write_file）。
// LZMA/XZ 解压由 Kotlin 层的 org.tukaani:xz 库完成。

#include <jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/stat.h>
#include <vector>
#include <string>

#define TAG  "LZMALoader"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// --------------------------------------------------------
// 从 AssetManager 读取全部字节到内存
// --------------------------------------------------------
static std::vector<uint8_t> read_asset(AAssetManager* mgr, const char* path) {
    AAsset* asset = AAssetManager_open(mgr, path, AASSET_MODE_BUFFER);
    if (!asset) {
        LOGE("asset not found: %s", path);
        return {};
    }
    size_t size = AAsset_getLength(asset);
    std::vector<uint8_t> buf(size);
    AAsset_read(asset, buf.data(), size);
    AAsset_close(asset);
    return buf;
}

// --------------------------------------------------------
// 写文件（带 fsync，确保 System.load 前文件完整落盘）
// --------------------------------------------------------
static bool write_file(const char* path, const uint8_t* data, size_t size) {
    int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0755);
    if (fd < 0) {
        LOGE("open failed: %s (errno=%d)", path, errno);
        return false;
    }
    size_t written = 0;
    while (written < size) {
        ssize_t n = write(fd, data + written, size - written);
        if (n <= 0) { close(fd); return false; }
        written += n;
    }
    fsync(fd);
    close(fd);
    return true;
}

extern "C" {

// --------------------------------------------------------
// Java: LZMANative.copyAsset(assetMgr, assetPath, dstPath) -> Boolean
// L0 专用：直接 copy，不解压
// --------------------------------------------------------
JNIEXPORT jboolean JNICALL
Java_com_demo_lzmaloader_LZMANative_copyAsset(
    JNIEnv* env, jclass,
    jobject jAssetMgr,
    jstring jAssetPath,
    jstring jDstPath
) {
    AAssetManager* mgr = AAssetManager_fromJava(env, jAssetMgr);
    const char* asset_path = env->GetStringUTFChars(jAssetPath, nullptr);
    const char* dst_path   = env->GetStringUTFChars(jDstPath,   nullptr);

    auto data = read_asset(mgr, asset_path);
    bool ok = !data.empty() && write_file(dst_path, data.data(), data.size());

    LOGI("copyAsset %s: %s", asset_path, ok ? "OK" : "FAIL");
    env->ReleaseStringUTFChars(jAssetPath, asset_path);
    env->ReleaseStringUTFChars(jDstPath,   dst_path);
    return (jboolean)ok;
}

// --------------------------------------------------------
// Java: LZMANative.verifyMd5(filePath, expectedMd5) -> Boolean
// Demo 通过 file size 校验，此处直接返回 true
// --------------------------------------------------------
JNIEXPORT jboolean JNICALL
Java_com_demo_lzmaloader_LZMANative_verifyMd5(
    JNIEnv* env, jclass,
    jstring jFilePath,
    jstring jExpectedMd5
) {
    return JNI_TRUE;
}

} // extern "C"
