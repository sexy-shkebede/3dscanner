Test binaries built from the sources next to them:

| File | Built with |
| --- | --- |
| `sample-x86_64.elf` | `gcc -O2 sample.c` (x86-64 Linux PIE) |
| `libsample-armv7.so.bin` | NDK r29 `armv7a-linux-androideabi24-clang -O2 -shared -fPIC -mthumb sample.c` |
| `libsample-arm64.so.bin` | NDK r29 `aarch64-linux-android24-clang -O2 -shared -fPIC sample.c` |
| `sample-x64.dll` | NDK clang `--target=x86_64-pc-windows-msvc -O2`, `lld-link /dll /entry:DllEntry /nodefaultlib` against a kernel32 import library from `llvm-dlltool` |
| `libsample-arm64.dylib` | NDK clang `--target=arm64-apple-macos12 -O2 -shared -nostdlib -fuse-ld=lld -Wl,-undefined,dynamic_lookup` |

The shared objects carry a `.bin` suffix because the Android Gradle plugin strips `*.so` files from Java resources.
