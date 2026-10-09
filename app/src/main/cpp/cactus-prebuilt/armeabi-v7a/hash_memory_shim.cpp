// hash_memory_shim.cpp — linker shim for the prebuilt Cactus engine.
//
// The prebuilt libneedle.a for armeabi-v7a references
//   std::__ndk1::__hash_memory(void const*, unsigned int)
// an out-of-line hash helper that exists in the newer libc++ the engine was
// built against, but that the Android NDK's libc++_static.a (r28+) no longer
// ships. arm64 builds inlined the call, so only 32-bit ARM needs this shim.
//
// The algorithm below is the verbatim murmur2 specialization that libc++ uses
// for 32-bit size_t (__functional/hash.h, __murmur2_or_cityhash<_Size, 32>).
//
// SPDX-License-Identifier: Apache-2.0 WITH LLVM-exception
// (algorithm from LLVM libc++, https://llvm.org)

#include <cstring>

namespace std {
// Reopened as inline so the mangling matches libc++'s __ndk1 exactly.
inline namespace __ndk1 {

// Marked weak: if a future NDK's libc++ provides a strong definition again,
// that one wins and this shim is discarded by the linker.
__attribute__((weak, visibility("default"))) unsigned int
__hash_memory(const void* __key, unsigned int __len) noexcept {
  // murmur2 — verbatim from libc++ __murmur2_or_cityhash<size_t, 32>
  const unsigned int __m = 0x5bd1e995u;
  const unsigned int __r = 24;
  unsigned int __h = __len;
  const unsigned char* __data = static_cast<const unsigned char*>(__key);
  unsigned int __remaining = __len;
  for (; __remaining >= 4; __data += 4, __remaining -= 4) {
    unsigned int __k = 0;
    memcpy(&__k, __data, 4); // little-endian word load, as libc++ does
    __k *= __m;
    __k ^= __k >> __r;
    __k *= __m;
    __h *= __m;
    __h ^= __k;
  }
  switch (__remaining) {
  case 3:
    __h ^= static_cast<unsigned int>(__data[2] << 16);
    [[fallthrough]];
  case 2:
    __h ^= static_cast<unsigned int>(__data[1] << 8);
    [[fallthrough]];
  case 1:
    __h ^= __data[0];
    __h *= __m;
  default:
    break;
  }
  __h ^= __h >> 13;
  __h *= __m;
  __h ^= __h >> 15;
  return __h;
}

} // namespace __ndk1
} // namespace std
