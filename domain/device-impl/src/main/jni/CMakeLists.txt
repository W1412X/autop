# ADB 注入通道的 SPAKE2 配对库（N199）：搬运自 Shizuku (Apache-2.0) manager/src/main/jni，
# 仅保留 libadb.so（adb_pairing.cpp），去掉 starter/selinux/cgroup 等 root 工具链。
cmake_minimum_required(VERSION 3.22.1)

project(autop_adb)

set(CMAKE_CXX_STANDARD 17)

add_compile_options(-Werror=format -fdata-sections -ffunction-sections -fno-exceptions -fno-rtti -fno-threadsafe-statics)

if (NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
    add_compile_options(-Os -flto -fvisibility=hidden -fvisibility-inlines-hidden)
    add_link_options(-flto -Wl,--exclude-libs,ALL -Wl,--gc-sections -Wl,--strip-all)
endif ()

find_package(boringssl REQUIRED CONFIG)

add_library(adb SHARED
        adb_pairing.cpp)

# C++ 运行时用 NDK 自带 libc++（c++_static），不依赖 Shizuku 的 lsposed libcxx prefab。
target_link_libraries(adb log boringssl::crypto_static)
