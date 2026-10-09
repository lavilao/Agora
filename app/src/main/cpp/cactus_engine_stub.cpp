// Placeholder translation unit for ABIs where the Cactus engine is not built.
//
// Cactus' kernels are written for ARMv8.2-A + FP16 + DotProd + I8MM and are
// only packaged for arm64-v8a. On every other ABI (armeabi-v7a phones, x86_64
// emulators) the CMake setup still has to provide a target named agora_cactus
// so the Gradle externalNativeBuild target list resolves; building it as a
// STATIC library keeps any .so out of the APK, and the Kotlin availability
// probe (libcactus_engine.so presence) then reports the engine as unavailable.
int agora_cactus_absent_placeholder;
