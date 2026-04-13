// lzma_jni.cpp — file I/O layer for com.demo.soloader
// LZMA/XZ decompression is handled in Kotlin via org.tukaani:xz.

#include <jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/stat.h>
#include <vector>
#include <string>

#define TAG  "LZMALoader"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static std::vector<uint8_t> read_asset(AAssetManager* mgr, const char* path) {
    AAsset* asset = AAssetManager_open(mgr, path, AASSET_MODE_BUFFER);
    if (!asset) {
        LOGE("asset not found: %s", path);
        return {};
    }
    size_t size = static_cast<size_t>(AAsset_getLength(asset));
    std::vector<uint8_t> buf(size);
    int nread = AAsset_read(asset, buf.data(), size);
    AAsset_close(asset);
    if (nread != static_cast<int>(size)) {
        LOGE("AAsset_read returned %d, expected %zu for: %s", nread, size, path);
        return {};
    }
    return buf;
}

// 权限 0600：仅 owner 读写，防止其他进程覆盖解压出的 SO
static bool write_file(const char* path, const uint8_t* data, size_t size) {
    int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0600);
    if (fd < 0) {
        LOGE("open failed: %s (errno=%d)", path, errno);
        return false;
    }
    size_t written = 0;
    while (written < size) {
        ssize_t n = write(fd, data + written, size - written);
        if (n < 0) {
            if (errno == EINTR) continue;  // 信号中断，重试
            LOGE("write failed: %s (errno=%d)", path, errno);
            close(fd);
            return false;
        }
        written += static_cast<size_t>(n);
    }
    if (fsync(fd) < 0) {
        LOGE("fsync failed: %s (errno=%d)", path, errno);
        close(fd);
        return false;
    }
    close(fd);
    return true;
}

extern "C" {

// com.demo.soloader.LZMANative.copyAsset
JNIEXPORT jboolean JNICALL
Java_com_demo_soloader_LZMANative_copyAsset(
    JNIEnv* env, jclass,
    jobject jAssetMgr,
    jstring jAssetPath,
    jstring jDstPath
) {
    AAssetManager* mgr = AAssetManager_fromJava(env, jAssetMgr);

    const char* asset_path = env->GetStringUTFChars(jAssetPath, nullptr);
    if (!asset_path) {
        LOGE("GetStringUTFChars failed for assetPath");
        return JNI_FALSE;
    }
    const char* dst_path = env->GetStringUTFChars(jDstPath, nullptr);
    if (!dst_path) {
        LOGE("GetStringUTFChars failed for dstPath");
        env->ReleaseStringUTFChars(jAssetPath, asset_path);
        return JNI_FALSE;
    }

    auto data = read_asset(mgr, asset_path);
    bool ok   = !data.empty() && write_file(dst_path, data.data(), data.size());

    LOGI("copyAsset %s: %s", asset_path, ok ? "OK" : "FAIL");
    env->ReleaseStringUTFChars(jAssetPath, asset_path);
    env->ReleaseStringUTFChars(jDstPath,   dst_path);
    return static_cast<jboolean>(ok);
}

} // extern "C"
