# 3.16 so the Linux build runs on Ubuntu 20.04 (glibc 2.31, CMake 3.16):
# binaries built there load on that and every newer distro.
cmake_minimum_required(VERSION 3.16)
project(tessera_bridge LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)

if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif()

set(VENDOR_DIR ${CMAKE_CURRENT_SOURCE_DIR}/vendor/bc7enc_rdo)

set(VENDOR_SOURCES
    ${VENDOR_DIR}/bc7enc.cpp
    ${VENDOR_DIR}/ert.cpp
    ${VENDOR_DIR}/rgbcx.cpp
    ${VENDOR_DIR}/utils.cpp
    ${VENDOR_DIR}/bc7decomp.cpp
    ${VENDOR_DIR}/bc7decomp_ref.cpp
    ${VENDOR_DIR}/rdo_bc_encoder.cpp
)

foreach(required rdo_bc_encoder.h bc7enc.h ert.h rgbcx.h utils.h bc7decomp.h dds_defs.h)
    if(NOT EXISTS ${VENDOR_DIR}/${required})
        message(FATAL_ERROR
            "Vendor sources missing or incomplete in ${VENDOR_DIR} (missing "
            "${required}). This build expects vendor/bc7enc_rdo/fetch.sh to "
            "have already vendored bc7enc_rdo (with patches) into that "
            "directory. Run:\n    cd ${VENDOR_DIR} && ./fetch.sh\nand re-run "
            "the build.")
    endif()
endforeach()

add_library(tessera_bridge SHARED
    ${VENDOR_SOURCES}
    ${VENDOR_DIR}/tessera_bridge.cpp
)

target_include_directories(tessera_bridge PRIVATE ${VENDOR_DIR})

# The encoder spawns worker threads (std::thread). Without this, on glibc < 2.34
# libpthread is not recorded as NEEDED and pthread_* only resolve because the
# JVM happened to load libpthread first.
# -pthread (driver flag) rather than a bare -lpthread: with the default
# --as-needed and -static-libstdc++, -lpthread lands before libstdc++.a (the
# actual pthread_* user) and the linker drops it.
set(THREADS_PREFER_PTHREAD_FLAG ON)
find_package(Threads REQUIRED)
target_link_libraries(tessera_bridge PRIVATE Threads::Threads)

# JNI headers only -- the library never links libjvm (the JVM that loads it
# provides the symbols). find_package(JNI) would demand AWT/JVM libraries,
# which cross toolchains don't have, so locate the headers directly.
# Override with -DJNI_INCLUDE_ROOT=<jdk>/include for cross builds.
set(JNI_INCLUDE_ROOT "$ENV{JAVA_HOME}/include" CACHE PATH "JDK include directory containing jni.h")
find_path(TESSERA_JNI_H jni.h PATHS ${JNI_INCLUDE_ROOT} NO_DEFAULT_PATH)
if(WIN32)
    set(TESSERA_JNI_MD_SUBDIR win32)
elseif(APPLE)
    set(TESSERA_JNI_MD_SUBDIR darwin)
else()
    set(TESSERA_JNI_MD_SUBDIR linux)
endif()
find_path(TESSERA_JNI_MD_H jni_md.h PATHS ${JNI_INCLUDE_ROOT}/${TESSERA_JNI_MD_SUBDIR} NO_DEFAULT_PATH)
# Explicit checks instead of find_path(... REQUIRED), which needs CMake 3.18.
if(NOT TESSERA_JNI_H OR NOT TESSERA_JNI_MD_H)
    message(FATAL_ERROR "JNI headers not found under ${JNI_INCLUDE_ROOT} (set JAVA_HOME or -DJNI_INCLUDE_ROOT)")
endif()
target_include_directories(tessera_bridge PRIVATE ${TESSERA_JNI_H} ${TESSERA_JNI_MD_H})

target_compile_definitions(tessera_bridge PRIVATE
    $<$<CONFIG:Release>:NDEBUG>
)

if(MSVC)
    target_compile_options(tessera_bridge PRIVATE
            $<$<CONFIG:Release>:/O2 /Oi /Ot>
            /GR-
            /fp:fast
    )
    # No /arch:AVX2: an AVX2-only binary loads fine on pre-AVX2 CPUs and then
    # dies with an illegal-instruction fault the JVM cannot catch. x64 MSVC
    # defaults to SSE2, which every x86_64 CPU supports.
    # No C++ exceptions cross the JNI boundary (every entry point returns a
    # plain status code), so unwinding support is disabled.
    string(REGEX REPLACE "/EH[sc]+" "" CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS}")
    target_compile_options(tessera_bridge PRIVATE /EHs-c-)
else()
    target_compile_options(tessera_bridge PRIVATE
        -O3 -ffast-math -fno-exceptions -fno-rtti -fomit-frame-pointer
    )
    if(CMAKE_SYSTEM_PROCESSOR MATCHES "x86_64|AMD64")
        # Baseline SSE4.1 only (see the MSVC note above). For AVX2, ship a second
        # binary and select it at load time instead of raising this floor.
        target_compile_options(tessera_bridge PRIVATE -msse4.1)
    elseif(CMAKE_SYSTEM_PROCESSOR MATCHES "aarch64|arm64")
        target_compile_options(tessera_bridge PRIVATE -ftree-vectorize)
    endif()
    if(CMAKE_SYSTEM_NAME STREQUAL "Linux")
        # Static C++ runtime: the .so must not depend on the player's
        # libstdc++ version (older distros ship older GLIBCXX symbols).
        target_link_options(tessera_bridge PRIVATE -static-libstdc++ -static-libgcc)
    endif()
    include(CheckIPOSupported)
    check_ipo_supported(RESULT lto_supported)
    if(lto_supported)
        set_target_properties(tessera_bridge PROPERTIES INTERPROCEDURAL_OPTIMIZATION TRUE)
    endif()
endif()

# Output name matches what TesseraNativeBridge (java.lang.foreign side)
# looks for on the classpath: libtessera_bridge.so / .dylib / tessera_bridge.dll
set_target_properties(tessera_bridge PROPERTIES
    OUTPUT_NAME "tessera_bridge"
    PREFIX "${CMAKE_SHARED_LIBRARY_PREFIX}"
)

install(TARGETS tessera_bridge
    RUNTIME DESTINATION natives
    LIBRARY DESTINATION natives
)
