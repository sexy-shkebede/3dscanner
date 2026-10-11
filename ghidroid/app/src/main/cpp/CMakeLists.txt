cmake_minimum_required(VERSION 3.22.1)
project(ghidroid CXX)

# Ghidra's decompiler / SLEIGH C++ sources (Apache 2.0, see ghidra/LICENSE and ghidra/PATCHES.md).
file(GLOB GHIDRA_SOURCES CONFIGURE_DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/ghidra/*.cc)

add_library(ghidroid SHARED ${GHIDRA_SOURCES} engine.cc jni_bridge.cc)

set_target_properties(ghidroid PROPERTIES
    CXX_STANDARD 17
    CXX_STANDARD_REQUIRED ON
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN ON)

target_include_directories(ghidroid PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/ghidra ${CMAKE_CURRENT_SOURCE_DIR})

# The decompiler is far too slow unoptimised, so optimise every variant (debug builds included).
target_compile_options(ghidroid PRIVATE
    -O2 -fexceptions -frtti -ffunction-sections -fdata-sections
    -Wno-deprecated-declarations -Wno-unused-parameter -Wno-sign-compare)

if(ANDROID)
  target_link_options(ghidroid PRIVATE -Wl,--gc-sections -Wl,-z,max-page-size=16384)
  target_link_libraries(ghidroid z log)
else()
  # Host build (JVM unit tests load the same JNI library on the development machine).
  find_package(JNI REQUIRED)
  find_package(ZLIB REQUIRED)
  target_include_directories(ghidroid PRIVATE ${JNI_INCLUDE_DIRS})
  target_link_options(ghidroid PRIVATE -Wl,--gc-sections)
  target_link_libraries(ghidroid ZLIB::ZLIB)
endif()
