/*
 * Copyright 2016 The Android Open Source Project
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

//#define LOG_NDEBUG 0
#define LOG_TAG "ExifInterface_JNI"

#include "android_media_Utils.h"

#include "src/piex_types.h"
#include "src/piex.h"

#include <jni.h>
#include <JNIHelp.h>
#include <android_runtime/AndroidRuntime.h>
#include <nativehelper/ScopedLocalRef.h>

#include <utils/Log.h>
#include <utils/String8.h>
#include <utils/KeyedVector.h>

// ----------------------------------------------------------------------------

using namespace android;

#define FIND_CLASS(var, className) \
    var = env->FindClass(className); \
    LOG_FATAL_IF(! var, "Unable to find class " className);

#define GET_METHOD_ID(var, clazz, fieldName, fieldDescriptor) \
    var = env->GetMethodID(clazz, fieldName, fieldDescriptor); \
    LOG_FATAL_IF(! var, "Unable to find method " fieldName);

struct HashMapFields {
    jmethodID init;
    jmethodID put;
};

struct fields_t {
    HashMapFields hashMap;
    jclass hashMapClassId;
};

static fields_t gFields;

static jobject KeyedVectorToHashMap(JNIEnv *env, KeyedVector<String8, String8> const &map) {
    jclass clazz = gFields.hashMapClassId;
    jobject hashMap = env->NewObject(clazz, gFields.hashMap.init);
    for (size_t i = 0; i < map.size(); ++i) {
        jstring jkey = env->NewStringUTF(map.keyAt(i).string());
        jstring jvalue = env->NewStringUTF(map.valueAt(i).string());
        env->CallObjectMethod(hashMap, gFields.hashMap.put, jkey, jvalue);
        env->DeleteLocalRef(jkey);
        env->DeleteLocalRef(jvalue);
    }
    return hashMap;
}

extern "C" {

// -------------------------- ExifInterface methods ---------------------------

static void ExifInterface_initRaw(JNIEnv *env) {
    jclass clazz;
    FIND_CLASS(clazz, "java/util/HashMap");
    gFields.hashMapClassId = static_cast<jclass>(env->NewGlobalRef(clazz));

    GET_METHOD_ID(gFields.hashMap.init, clazz, "<init>", "()V");
    GET_METHOD_ID(gFields.hashMap.put, clazz, "put",
                  "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
}

static jobject ExifInterface_getRawMetadata(
        JNIEnv* env, jclass /* clazz */, jobject jfileDescriptor) {
    int fd = jniGetFDFromFileDescriptor(env, jfileDescriptor);
    if (fd < 0) {
        ALOGI("Invalid file descriptor");
        return NULL;
    }

    piex::PreviewImageData image_data;
    std::unique_ptr<FileStream> stream(new FileStream(fd));

    if (!GetExifFromRawImage(stream.get(), String8("[file descriptor]"), image_data)) {
        ALOGI("Raw image not detected");
        return NULL;
    }

    KeyedVector<String8, String8> map;

    if (image_data.thumbnail_length > 0) {
        map.add(String8("hasThumbnail"), String8("true"));
        map.add(String8("thumbnailOffset"), String8::format("%d", image_data.thumbnail_offset));
        map.add(String8("thumbnailLength"), String8::format("%d", image_data.thumbnail_length));
    } else {
        map.add(String8("hasThumbnail"), String8("false"));
    }

    map.add(
            String8("Orientation"),
            String8::format("%u", image_data.exif_orientation));
    map.add(
            String8("ImageWidth"),
            String8::format("%u", image_data.full_width));
    map.add(
            String8("ImageLength"),
            String8::format("%u", image_data.full_height));

    // Current PIEX does not have LightSource information while JPEG version of
    // EXIFInterface always declares the light source field. For the
    // compatibility, it provides the default value of the light source field.
    map.add(String8("LightSource"), String8("0"));

    if (!image_data.maker.empty()) {
        map.add(String8("Make"), String8(image_data.maker.c_str()));
    }

    if (!image_data.model.empty()) {
        map.add(String8("Model"), String8(image_data.model.c_str()));
    }

    if (!image_data.date_time.empty()) {
        map.add(String8("DateTime"), String8(image_data.date_time.c_str()));
    }

    if (image_data.iso) {
        map.add(
                String8("ISOSpeedRatings"),
                String8::format("%u", image_data.iso));
    }

    if (image_data.exposure_time.numerator != 0
            && image_data.exposure_time.denominator != 0) {
        double exposureTime =
            (double)image_data.exposure_time.numerator
            / image_data.exposure_time.denominator;

        const char* format;
        if (exposureTime < 0.01) {
            format = "%6.4f";
        } else {
            format = "%5.3f";
        }
        map.add(String8("ExposureTime"), String8::format(format, exposureTime));
    }

    if (image_data.fnumber.numerator != 0
            && image_data.fnumber.denominator != 0) {
        double fnumber =
            (double)image_data.fnumber.numerator
            / image_data.fnumber.denominator;
        map.add(String8("FNumber"), String8::format("%5.3f", fnumber));
    }

    if (image_data.focal_length.numerator != 0
            && image_data.focal_length.denominator != 0) {
        map.add(
                String8("FocalLength"),
                String8::format(
                        "%u/%u",
                        image_data.focal_length.numerator,
                        image_data.focal_length.denominator));
    }

    if (image_data.gps.is_valid) {
        if (image_data.gps.latitude[0].denominator != 0
                && image_data.gps.latitude[1].denominator != 0
                && image_data.gps.latitude[2].denominator != 0) {
            map.add(
                    String8("GPSLatitude"),
                    String8::format(
                            "%u/%u,%u/%u,%u/%u",
                            image_data.gps.latitude[0].numerator,
                            image_data.gps.latitude[0].denominator,
                            image_data.gps.latitude[1].numerator,
                            image_data.gps.latitude[1].denominator,
                            image_data.gps.latitude[2].numerator,
                            image_data.gps.latitude[2].denominator));
        }

        if (image_data.gps.latitude_ref) {
            char str[2];
            str[0] = image_data.gps.latitude_ref;
            str[1] = 0;
            map.add(String8("GPSLatitudeRef"), String8(str));
        }

        if (image_data.gps.longitude[0].denominator != 0
                && image_data.gps.longitude[1].denominator != 0
                && image_data.gps.longitude[2].denominator != 0) {
            map.add(
                    String8("GPSLongitude"),
                    String8::format(
                            "%u/%u,%u/%u,%u/%u",
                            image_data.gps.longitude[0].numerator,
                            image_data.gps.longitude[0].denominator,
                            image_data.gps.longitude[1].numerator,
                            image_data.gps.longitude[1].denominator,
                            image_data.gps.longitude[2].numerator,
                            image_data.gps.longitude[2].denominator));
        }

        if (image_data.gps.longitude_ref) {
            char str[2];
            str[0] = image_data.gps.longitude_ref;
            str[1] = 0;
            map.add(String8("GPSLongitudeRef"), String8(str));
        }

        if (image_data.gps.altitude.denominator != 0) {
            map.add(
                    String8("GPSAltitude"),
                    String8::format("%u/%u",
                            image_data.gps.altitude.numerator,
                            image_data.gps.altitude.denominator));

            map.add(
                    String8("GPSAltitudeRef"),
                    String8(image_data.gps.altitude_ref ? "1" : "0"));
        }

        if (image_data.gps.time_stamp[0].denominator != 0
                && image_data.gps.time_stamp[1].denominator != 0
                && image_data.gps.time_stamp[2].denominator != 0) {
            map.add(
                    String8("GPSTimeStamp"),
                    String8::format(
                            "%02u:%02u:%02u",
                            image_data.gps.time_stamp[0].numerator
                            / image_data.gps.time_stamp[0].denominator,
                            image_data.gps.time_stamp[1].numerator
                            / image_data.gps.time_stamp[1].denominator,
                            image_data.gps.time_stamp[2].numerator
                            / image_data.gps.time_stamp[2].denominator));
        }

        if (!image_data.gps.date_stamp.empty()) {
            map.add(
                    String8("GPSDateStamp"),
                    String8(image_data.gps.date_stamp.c_str()));
        }
    }

    return KeyedVectorToHashMap(env, map);
}

} // extern "C"

// ----------------------------------------------------------------------------

static JNINativeMethod gMethods[] = {
    { "initRawNative", "()V", (void *)ExifInterface_initRaw },
    { "getRawAttributesNative", "(Ljava/io/FileDescriptor;)Ljava/util/HashMap;",
      (void*)ExifInterface_getRawMetadata },
};

int register_android_media_ExifInterface(JNIEnv *env) {
    return AndroidRuntime::registerNativeMethods(
            env,
            "android/media/ExifInterface",
            gMethods,
            NELEM(gMethods));
}
