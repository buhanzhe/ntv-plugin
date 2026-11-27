// Supply the libc calls used by the decoder archives that Android API 15 lacks.
#include <cerrno>
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <malloc.h>
#include <libde265/de265.h>

extern "C" de265_error __real_de265_start_worker_threads(de265_decoder_context*, int);

// The TVBox artwork executor already provides a background thread on these devices.
extern "C" de265_error __wrap_de265_start_worker_threads(de265_decoder_context* decoder, int) {
    return __real_de265_start_worker_threads(decoder, 0);
}

extern "C" void* __wrap___memset_chk(void* target, int value, size_t length, size_t capacity) {
    if (length > capacity) std::abort();
    return std::memset(target, value, length);
}

extern "C" size_t __wrap___strlen_chk(const char* text, size_t capacity) {
    for (size_t i = 0; i < capacity; ++i) {
        if (text[i] == '\0') return i;
    }
    std::abort();
}

extern "C" char* __wrap___strcat_chk(char* target, const char* source, size_t capacity) {
    const size_t offset = __wrap___strlen_chk(target, capacity);
    const size_t length = std::strlen(source);
    if (length >= capacity - offset) std::abort();
    std::memcpy(target + offset, source, length + 1);
    return target;
}

extern "C" int __wrap___vsnprintf_chk(char* target, size_t length, int, size_t capacity,
                                      const char* format, va_list arguments) {
    if (length > capacity) std::abort();
    return std::vsnprintf(target, length, format, arguments);
}

extern "C" int __wrap___vsprintf_chk(char* target, int, size_t capacity,
                                     const char* format, va_list arguments) {
    const int result = std::vsnprintf(target, capacity, format, arguments);
    if (result >= 0 && static_cast<size_t>(result) >= capacity) std::abort();
    return result;
}

extern "C" int __wrap_posix_memalign(void** result, size_t alignment, size_t size) {
    if (alignment < sizeof(void*) || (alignment & (alignment - 1)) != 0) return EINVAL;
    const int saved = errno;
    void* buffer = memalign(alignment, size);
    errno = saved;
    if (!buffer) return ENOMEM;
    *result = buffer;
    return 0;
}
