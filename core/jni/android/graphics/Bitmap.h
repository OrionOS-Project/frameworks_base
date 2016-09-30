/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
#ifndef BITMAP_H_
#define BITMAP_H_

#include <jni.h>
#include <SkBitmap.h>
#include <SkColorTable.h>
#include <SkImageInfo.h>
#include <utils/Mutex.h>
#include <memory>

namespace android {

enum class PixelStorageType {
    Invalid,
    External,
    Heap,
    Ashmem,
};

class WrappedPixelRef;

typedef void (*FreeFunc)(void* addr, void* context);

/**
 * Glue-thingy that deals with managing the interaction between the Java
 * Bitmap object & SkBitmap along with trying to map a notion of strong/weak
 * lifecycles onto SkPixelRef which only has strong counts to avoid requiring
 * two GC passes to free the byte[] that backs a Bitmap.
 *
 * Since not all Bitmaps are byte[]-backed it also supports external allocations,
 * which currently is used by screenshots to wrap a gralloc buffer.
 */
class Bitmap {
public:
    Bitmap(void* address, size_t allocSize, const SkImageInfo& info, size_t rowBytes,
            SkColorTable* ctable);
    Bitmap(void* address, void* context, FreeFunc freeFunc,
            const SkImageInfo& info, size_t rowBytes, SkColorTable* ctable);
    Bitmap(void* address, int fd, size_t mappedSize, const SkImageInfo& info,
            size_t rowBytes, SkColorTable* ctable);

    const SkImageInfo& info() const;

    int width() const { return info().width(); }
    int height() const { return info().height(); }
    size_t rowBytes() const;
    SkPixelRef* peekAtPixelRef() const;
    SkPixelRef* refPixelRef();
    bool valid() const { return mPixelStorageType != PixelStorageType::Invalid; }

    void reconfigure(const SkImageInfo& info, size_t rowBytes, SkColorTable* ctable);
    void reconfigure(const SkImageInfo& info);
    void setAlphaType(SkAlphaType alphaType);

    void getSkBitmap(SkBitmap* outBitmap);
    void detachFromJava();

    void freePixels();

    bool hasHardwareMipMap();
    void setHasHardwareMipMap(bool hasMipMap);
    int getAshmemFd() const;
    size_t getAllocationByteCount() const;

private:
    friend class WrappedPixelRef;

    ~Bitmap();
    void doFreePixels();
    void onStrongRefDestroyed();

    void pinPixelsLocked();
    bool shouldDisposeSelfLocked();
    void assertValid() const;
    SkPixelRef* refPixelRefLocked();

    android::Mutex mLock;
    int mPinnedRefCount = 0;
    std::unique_ptr<WrappedPixelRef> mPixelRef;
    PixelStorageType mPixelStorageType;
    bool mAttachedToJava = true;

    union {
        struct {
            void* address;
            void* context;
            FreeFunc freeFunc;
        } external;
        struct {
            void* address;
            int fd;
            size_t size;
        } ashmem;
        struct {
            void* address;
            size_t size;
        } heap;
    } mPixelStorage;
};

} // namespace android

#endif /* BITMAP_H_ */
