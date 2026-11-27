#include <android/bitmap.h>
#include <jni.h>
#include <libheif/heif.h>

#include <algorithm>
#include <memory>

namespace {
constexpr uint64_t maxPixels = 4000000;

struct Bytes {
    JNIEnv* env;
    jbyteArray array;
    jbyte* data;
    ~Bytes() {
        if (data) env->ReleaseByteArrayElements(array, data, JNI_ABORT);
    }
};
}

extern "C" JNIEXPORT jobject JNICALL
Java_xiao_bu_channel_tvbox_BoxHeif_decodeNative(JNIEnv* env, jclass, jbyteArray bytes, jint edge) {
    const jsize length = env->GetArrayLength(bytes);
    if (length < 16 || length > 3 * 1024 * 1024 || edge < 1 || edge > 1440) return nullptr;
    Bytes input{env, bytes, env->GetByteArrayElements(bytes, nullptr)};
    if (!input.data) return nullptr;

    std::unique_ptr<heif_context, decltype(&heif_context_free)> context(heif_context_alloc(), heif_context_free);
    if (!context) return nullptr;
    heif_security_limits limits = *heif_get_global_security_limits();
    limits.max_image_size_pixels = maxPixels;
    limits.max_memory_block_size = 32 * 1024 * 1024;
    heif_context_set_security_limits(context.get(), &limits);
    heif_context_set_max_decoding_threads(context.get(), 0);
    if (heif_context_read_from_memory_without_copy(context.get(), input.data, length, nullptr).code) return nullptr;

    heif_image_handle* primary = nullptr;
    if (heif_context_get_primary_image_handle(context.get(), &primary).code) return nullptr;
    std::unique_ptr<heif_image_handle, decltype(&heif_image_handle_release)> handle(primary, heif_image_handle_release);
    int width = heif_image_handle_get_width(primary);
    int height = heif_image_handle_get_height(primary);
    if (width <= 0 || height <= 0 || static_cast<uint64_t>(width) * height > maxPixels) return nullptr;

    bool compact = false;
#ifdef NTV_HEIF_ICS
    // Keep several covers in the existing bitmap budget on older TVs and phones.
    edge = std::min(edge, 420);
    compact = !heif_image_handle_has_alpha_channel(primary);
#endif
    const int channels = compact ? 3 : 4;
    heif_image* decoded = nullptr;
    const auto chroma = compact ? heif_chroma_interleaved_RGB : heif_chroma_interleaved_RGBA;
    if (heif_decode_image(primary, &decoded, heif_colorspace_RGB, chroma, nullptr).code) return nullptr;
    std::unique_ptr<heif_image, decltype(&heif_image_release)> image(decoded, heif_image_release);
    width = heif_image_get_width(decoded, heif_channel_interleaved);
    height = heif_image_get_height(decoded, heif_channel_interleaved);
    int longest = std::max(width, height);
    if (longest > edge) {
        heif_image* scaled = nullptr;
        width = std::max(1, width * edge / longest);
        height = std::max(1, height * edge / longest);
        if (heif_image_scale_image(decoded, &scaled, width, height, nullptr).code) return nullptr;
        image.reset(scaled);
    }
    int stride = 0;
    const uint8_t* plane = heif_image_get_plane_readonly(image.get(), heif_channel_interleaved, &stride);
    if (!plane || width <= 0 || height <= 0 || stride < width * channels) return nullptr;

    jclass bitmap = env->FindClass("android/graphics/Bitmap");
    jclass config = env->FindClass("android/graphics/Bitmap$Config");
    if (!bitmap || !config) return nullptr;
    jfieldID format = env->GetStaticFieldID(config, compact ? "RGB_565" : "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
    jmethodID create = env->GetStaticMethodID(bitmap, "createBitmap",
            "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    if (!format || !create) return nullptr;
    jobject result = env->CallStaticObjectMethod(bitmap, create, width, height, env->GetStaticObjectField(config, format));
    if (!result || env->ExceptionCheck()) return nullptr;
    AndroidBitmapInfo info{};
    void* pixels = nullptr;
    const auto bitmapFormat = compact ? ANDROID_BITMAP_FORMAT_RGB_565 : ANDROID_BITMAP_FORMAT_RGBA_8888;
    if (AndroidBitmap_getInfo(env, result, &info) != ANDROID_BITMAP_RESULT_SUCCESS
            || info.format != bitmapFormat || info.stride < static_cast<uint32_t>(width) * (compact ? 2 : 4)
            || AndroidBitmap_lockPixels(env, result, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) return nullptr;
    for (int row = 0; row < height; ++row) {
        const uint8_t* source = plane + row * stride;
        auto* target = static_cast<uint8_t*>(pixels) + row * info.stride;
        if (compact) {
            auto* packed = reinterpret_cast<uint16_t*>(target);
            for (int col = 0; col < width; ++col, source += 3) {
                packed[col] = ((source[0] >> 3) << 11) | ((source[1] >> 2) << 5) | (source[2] >> 3);
            }
            continue;
        }
        // Android ARGB_8888 stores premultiplied color channels.
        for (int col = 0; col < width; ++col, source += 4, target += 4) {
            const unsigned alpha = source[3];
            target[0] = (source[0] * alpha + 127) / 255;
            target[1] = (source[1] * alpha + 127) / 255;
            target[2] = (source[2] * alpha + 127) / 255;
            target[3] = alpha;
        }
    }
    AndroidBitmap_unlockPixels(env, result);
    return result;
}
