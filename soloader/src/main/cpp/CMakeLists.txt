cmake_minimum_required(VERSION 3.22.1)
project(lzma_loader)

add_library(lzma_loader SHARED
    lzma_jni.cpp
)

target_link_libraries(lzma_loader
    android
    log
)
