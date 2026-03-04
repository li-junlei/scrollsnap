#include <jni.h>
#include <android/bitmap.h>
#include <algorithm>
#include <cstdint>
#include <cmath>

namespace {

inline int gray_from_rgba8888(uint32_t pixel) {
    const int r = pixel & 0xFF;
    const int g = (pixel >> 8) & 0xFF;
    const int b = (pixel >> 16) & 0xFF;
    return (r * 77 + g * 150 + b * 29) >> 8;
}

int estimate_overlap_internal(
    const uint8_t* top_ptr,
    const AndroidBitmapInfo& top_info,
    const uint8_t* bottom_ptr,
    const AndroidBitmapInfo& bottom_info,
    int row_step,
    int col_step
) {
    // Lightweight fallback matcher based on grayscale SAD.
    // TODO: Replace this block with OpenCV ORB + RANSAC translation estimation.
    const int width = static_cast<int>(top_info.width);
    const int h1 = static_cast<int>(top_info.height);
    const int h2 = static_cast<int>(bottom_info.height);
    const int max_overlap = std::min(h1, h2) - 1;
    const int min_overlap = std::max(24, std::min(h1, h2) / 10);

    double best_score = 1e30;
    int best_overlap = min_overlap;

    for (int overlap = min_overlap; overlap <= max_overlap; overlap += std::max(1, row_step)) {
        double sum_abs = 0.0;
        int samples = 0;

        for (int y = 0; y < overlap; y += std::max(1, row_step)) {
            const int top_y = h1 - overlap + y;
            const int bottom_y = y;
            const uint32_t* top_row = reinterpret_cast<const uint32_t*>(top_ptr + top_y * top_info.stride);
            const uint32_t* bottom_row = reinterpret_cast<const uint32_t*>(bottom_ptr + bottom_y * bottom_info.stride);

            for (int x = 0; x < width; x += std::max(1, col_step)) {
                const int tg = gray_from_rgba8888(top_row[x]);
                const int bg = gray_from_rgba8888(bottom_row[x]);
                sum_abs += std::abs(tg - bg);
                samples++;
            }
        }

        if (samples == 0) {
            continue;
        }

        const double score = sum_abs / static_cast<double>(samples);
        if (score < best_score) {
            best_score = score;
            best_overlap = overlap;
        }
    }

    return best_overlap;
}

}  // namespace

extern "C"
JNIEXPORT jint JNICALL
Java_com_scrollsnap_core_stitch_NativeFeatureStitcher_estimateVerticalOverlap(
    JNIEnv* env,
    jobject /*thiz*/,
    jobject top_bitmap,
    jobject bottom_bitmap,
    jint row_step,
    jint col_step
) {
    AndroidBitmapInfo top_info{};
    AndroidBitmapInfo bottom_info{};

    if (AndroidBitmap_getInfo(env, top_bitmap, &top_info) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return 0;
    }
    if (AndroidBitmap_getInfo(env, bottom_bitmap, &bottom_info) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return 0;
    }

    if (top_info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        bottom_info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        top_info.width != bottom_info.width) {
        return 0;
    }

    void* top_pixels = nullptr;
    void* bottom_pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, top_bitmap, &top_pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return 0;
    }
    if (AndroidBitmap_lockPixels(env, bottom_bitmap, &bottom_pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        AndroidBitmap_unlockPixels(env, top_bitmap);
        return 0;
    }

    const int overlap = estimate_overlap_internal(
        reinterpret_cast<const uint8_t*>(top_pixels),
        top_info,
        reinterpret_cast<const uint8_t*>(bottom_pixels),
        bottom_info,
        static_cast<int>(row_step),
        static_cast<int>(col_step)
    );

    AndroidBitmap_unlockPixels(env, top_bitmap);
    AndroidBitmap_unlockPixels(env, bottom_bitmap);

    return overlap;
}
