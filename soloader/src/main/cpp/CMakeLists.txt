cmake_minimum_required(VERSION 3.22.1)
project(lzma_loader)

# 隐藏所有符号，只导出显式标注的 JNI 符号，减少二进制大小并防止符号泄漏
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fvisibility=hidden -fvisibility-inlines-hidden")

add_library(lzma_loader SHARED
    lzma_jni.cpp
)

set_target_properties(lzma_loader PROPERTIES
    CXX_STANDARD 17
    CXX_STANDARD_REQUIRED ON
)

target_link_libraries(lzma_loader
    android
    log
)
