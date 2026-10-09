cmake_minimum_required(VERSION 3.22)
project("agora_llama" LANGUAGES C CXX)

set(LLAMA_CPP_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../thirdparty/llama.cpp" CACHE PATH "llama.cpp source directory")
get_filename_component(LLAMA_CPP_DIR "${LLAMA_CPP_DIR}" REALPATH)

add_compile_options(
    -ffile-prefix-map=${CMAKE_SOURCE_DIR}=.
    -ffile-prefix-map=${LLAMA_CPP_DIR}=llama.cpp
    -g0
    -fno-ident
)

set(LLAMA_BUILD_NUMBER 0)
set(LLAMA_BUILD_COMMIT "unknown")
set(LLAMA_INSTALL_VERSION "0.0.0")
set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "")
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "")
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "")
set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

add_subdirectory(${LLAMA_CPP_DIR} ${CMAKE_CURRENT_BINARY_DIR}/llama)
add_subdirectory(${LLAMA_CPP_DIR}/tools/mtmd ${CMAKE_CURRENT_BINARY_DIR}/mtmd)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(AGORA_ANDROID_CPU_BACKENDS
        ggml-cpu-android_armv8.0_1
        ggml-cpu-android_armv8.2_1
        ggml-cpu-android_armv8.2_2
        ggml-cpu-android_armv8.6_1
        ggml-cpu-android_armv9.0_1
        ggml-cpu-android_armv9.2_1
        ggml-cpu-android_armv9.2_2
    )
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(AGORA_ANDROID_CPU_BACKENDS ggml-cpu-x64)
else()
    message(FATAL_ERROR "Unsupported Android ABI: ${ANDROID_ABI}")
endif()
foreach(backend IN LISTS AGORA_ANDROID_CPU_BACKENDS)
    if(NOT TARGET ${backend})
        message(FATAL_ERROR "Required Android CPU backend target is missing: ${backend}")
    endif()
    set_target_properties(${backend} PROPERTIES
        LIBRARY_OUTPUT_DIRECTORY ${CMAKE_LIBRARY_OUTPUT_DIRECTORY}
    )
endforeach()

foreach(cli llama-mtmd-cli llama-mtmd-debug
         llama-llava-cli llama-gemma3-cli llama-minicpmv-cli llama-qwen2vl-cli)
    if(TARGET ${cli})
        set_target_properties(${cli} PROPERTIES EXCLUDE_FROM_ALL TRUE)
    endif()
endforeach()
foreach(cli llama-llava-cli llama-gemma3-cli llama-minicpmv-cli llama-qwen2vl-cli)
    if(TARGET ${cli})
        target_include_directories(${cli} PRIVATE ${LLAMA_CPP_DIR}/common)
    endif()
endforeach()
if(TARGET llama-mtmd-cli)
    target_include_directories(llama-mtmd-cli PRIVATE ${LLAMA_CPP_DIR}/common ${LLAMA_CPP_DIR}/vendor)
endif()
if(TARGET llama-mtmd-debug)
    target_include_directories(llama-mtmd-debug PRIVATE ${LLAMA_CPP_DIR}/common ${LLAMA_CPP_DIR}/vendor)
endif()

target_compile_definitions(ggml-base PRIVATE GGML_COMMIT="unknown")

# Build llama JNI wrapper
add_library(agora_llama SHARED
    llama_jni.cpp
    llama_chat_jni.cpp
    llama_chat_callbacks.cpp
    llama_chat_template.cpp
    llama_chat_generation.cpp
    llama_chat_speculative.cpp
)
target_link_libraries(agora_llama llama llama-common mtmd log)
target_include_directories(agora_llama PRIVATE ${LLAMA_CPP_DIR}/include ${LLAMA_CPP_DIR}/tools/mtmd)
target_link_options(agora_llama PRIVATE
    -Wl,--build-id=none -Wl,--hash-style=gnu -Wl,--icf=all -Wl,--threads=1
)

# ── objcopy for reproducible builds ──────────────────────
find_program(LLVM_OBJCOPY llvm-objcopy
    HINTS ${ANDROID_NDK}/toolchains/llvm/prebuilt/*/bin NO_DEFAULT_PATH
)

# ── Minimal JNI stub for proot ───────────────────────────
# System.loadLibrary("agora_proot") triggers APK extraction of native libs.
# Actual proot execution uses ProcessBuilder on libproot_exec.so (GNUmakefile-built).
add_library(agora_proot SHARED proot_jni.cpp)
target_link_options(agora_proot PRIVATE
    -Wl,--build-id=none -Wl,--hash-style=gnu -Wl,--icf=all
)

# ── Cactus alternative engine ────────────────────────────
# Cactus runs ".cactus" bundle directories (config.txt + vocab.txt +
# components/manifest.json + *.weights), not GGUF. Its own NEON kernels are
# written for ARMv8.2-A + FP16 + DotProd + I8MM, so the full runtime is only
# packaged for arm64-v8a devices. 32-bit phones get the prebuilt Needle 3
# engine that Cactus publishes for armeabi-v7a (HuggingFace
# Cactus-Compute/needle3): a static library running the same family's tiny
# tool-calling models from single ".cact" files. Every other ABI keeps the
# llama.cpp engine only; a STATIC placeholder keeps the Gradle target list
# resolvable while no .so enters the APK, so the Kotlin availability probe
# (libagora_cactus.so presence) reports the engine as unavailable there.
set(CACTUS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../thirdparty/cactus" CACHE PATH "Cactus source directory")
get_filename_component(CACTUS_DIR "${CACTUS_DIR}" REALPATH)
set(CACTUS_PREBUILT_DIR "${CMAKE_CURRENT_SOURCE_DIR}/cactus-prebuilt")
if(ANDROID_ABI STREQUAL "arm64-v8a" AND EXISTS "${CACTUS_DIR}/cactus-engine/CMakeLists.txt")
    # The module builds with -std=c++17 (gradle cppFlags) while Cactus targets
    # set CXX_STANDARD 20. Compilers honor only the last -std flag, so the
    # stale one is removed up front instead of relying on flag ordering; the
    # NDK clang default (gnu++17) keeps the llama.cpp sources at C++17.
    string(REPLACE "-std=c++17" "" CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS}")
    add_subdirectory(${CACTUS_DIR}/cactus-engine ${CMAKE_CURRENT_BINARY_DIR}/cactus)
    add_library(agora_cactus SHARED cactus_engine_jni.cpp)
    set_target_properties(agora_cactus PROPERTIES
        CXX_STANDARD 20
        CXX_STANDARD_REQUIRED ON
    )
    target_include_directories(agora_cactus PRIVATE
        ${CACTUS_DIR}/cactus-engine
    )
    target_link_libraries(agora_cactus PRIVATE cactus_engine cactus_graph cactus_kernels)
    target_link_options(agora_cactus PRIVATE
        -Wl,-z,max-page-size=16384
        -Wl,--build-id=none -Wl,--hash-style=gnu -Wl,--icf=all -Wl,--threads=1
    )
    if(LLVM_OBJCOPY)
        add_custom_command(TARGET agora_cactus POST_BUILD
            COMMAND ${LLVM_OBJCOPY} --remove-section .comment $<TARGET_FILE:agora_cactus>
        )
    endif()
else()
    # 32-bit ARM: link Cactus' prebuilt Needle 3 engine. The module links the
    # shared STL (ANDROID_STL=c++_shared), which satisfies the engine's
    # "__ndk1" libc++ references; the NDK r28 libc++ no longer defines
    # std::__ndk1::__hash_memory, which the armv7 engine references out of
    # line, so hash_memory_shim.cpp (the verbatim LLVM murmur2 definition,
    # weak) is compiled in to close that one gap.
    if(ANDROID_ABI STREQUAL "armeabi-v7a" AND
       EXISTS "${CACTUS_PREBUILT_DIR}/armeabi-v7a/libneedle.a")
        add_library(agora_cactus SHARED
            cactus_needle_jni.cpp
            cactus-prebuilt/armeabi-v7a/hash_memory_shim.cpp
        )
        target_include_directories(agora_cactus PRIVATE
            "${CACTUS_PREBUILT_DIR}/include"
        )
        target_link_libraries(agora_cactus PRIVATE
            "${CACTUS_PREBUILT_DIR}/armeabi-v7a/libneedle.a"
            log
        )
        target_link_options(agora_cactus PRIVATE
            -Wl,--build-id=none -Wl,--hash-style=gnu -Wl,--icf=all -Wl,--threads=1
        )
        if(LLVM_OBJCOPY)
            add_custom_command(TARGET agora_cactus POST_BUILD
                COMMAND ${LLVM_OBJCOPY} --remove-section .comment $<TARGET_FILE:agora_cactus>
            )
        endif()
    else()
        add_library(agora_cactus STATIC cactus_engine_stub.cpp)
    endif()
endif()

# ── Reproducible: strip .comment ─────────────────────────
if(DEFINED ENV{SOURCE_DATE_EPOCH})
    add_definitions(-DSOURCE_DATE_EPOCH=$ENV{SOURCE_DATE_EPOCH})
endif()
if(LLVM_OBJCOPY)
    add_custom_command(TARGET agora_llama POST_BUILD
        COMMAND ${LLVM_OBJCOPY} --remove-section .comment $<TARGET_FILE:agora_llama>
    )
    add_custom_command(TARGET agora_proot POST_BUILD
        COMMAND ${LLVM_OBJCOPY} --remove-section .comment $<TARGET_FILE:agora_proot>
    )
endif()
