cmake_minimum_required(VERSION 3.21.0)
project(ffmpegext C CXX)
set(CMAKE_CXX_STANDARD 11)

# NDK 23.1+ arm64 需要此 flag（上游 workaround，ExoPlayer issue 引用保留）
if(ANDROID_ABI STREQUAL "arm64-v8a")
    string(APPEND CMAKE_SHARED_LINKER_FLAGS " -Wl,-Bsymbolic")
endif()

set(ffmpeg_location ${CMAKE_CURRENT_SOURCE_DIR}/ffmpeg)
foreach(lib avutil swresample avcodec)
    add_library(${lib} STATIC IMPORTED)
    set_target_properties(${lib} PROPERTIES IMPORTED_LOCATION
        ${ffmpeg_location}/android-libs/${ANDROID_ABI}/lib${lib}.a)
endforeach()

find_library(android_log_lib log)

add_library(ffmpegext SHARED ffmpeg_jni.cc)
# 内部 helper（decodePacket/createContext 等 C++ 符号）不导出：Task3 Step5 nm 断言要求动态导出表
# 恰为 Java_com_novax_media3_ffmpeg_* 九个符号（上游 JNI_OnLoad 已随源码注释删除，见 ffmpeg_jni.cc）
target_compile_options(ffmpegext PRIVATE -fvisibility=hidden)
target_include_directories(ffmpegext PRIVATE ${ffmpeg_location})
# 链接顺序与上游一致：swresample 在 avcodec 前
target_link_libraries(ffmpegext PRIVATE android ${android_log_lib} swresample avcodec avutil)
# 关键：静态库符号不导出（libVLC 进程内共存防符号冲突；对外仅 Java_ JNI 符号）
target_link_options(ffmpegext PRIVATE -Wl,--exclude-libs,ALL)
