/*
 * Copyright (C) 2014 The Android Open Source Project
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

#define LOG_TAG "Minikin"

#include "JNIHelp.h"
#include <core_jni_helpers.h>

#include "SkData.h"
#include "SkFontMgr.h"
#include "SkRefCnt.h"
#include "SkTypeface.h"
#include "GraphicsJNI.h"
#include <ScopedPrimitiveArray.h>
#include <ScopedUtfChars.h>
#include <android_runtime/android_util_AssetManager.h>
#include <androidfw/AssetManager.h>
#include "Utils.h"

#include "TypefaceImpl.h"
#include <minikin/FontFamily.h>
#include "MinikinSkia.h"

#include <memory>

namespace android {

static jlong FontFamily_create(JNIEnv* env, jobject clazz, jstring lang, jint variant) {
    if (lang == NULL) {
        return (jlong)new FontFamily(variant);
    }
    ScopedUtfChars str(env, lang);
    uint32_t langId = FontStyle::registerLanguageList(str.c_str());
    return (jlong)new FontFamily(langId, variant);
}

static void FontFamily_unref(JNIEnv* env, jobject clazz, jlong familyPtr) {
    FontFamily* fontFamily = reinterpret_cast<FontFamily*>(familyPtr);
    fontFamily->Unref();
}

static jboolean addSkTypeface(FontFamily* family, SkTypeface* face) {
    MinikinFont* minikinFont = new MinikinFontSkia(face);
    bool result = family->addFont(minikinFont);
    minikinFont->Unref();
    return result;
}

static jboolean FontFamily_addFont(JNIEnv* env, jobject clazz, jlong familyPtr, jstring path,
        jint ttcIndex) {
    NPE_CHECK_RETURN_ZERO(env, path);
    ScopedUtfChars str(env, path);
    SkTypeface* face = SkTypeface::CreateFromFile(str.c_str(), ttcIndex);
    if (face == NULL) {
        ALOGE("addFont failed to create font %s", str.c_str());
        return false;
    }
    FontFamily* fontFamily = reinterpret_cast<FontFamily*>(familyPtr);
    return addSkTypeface(fontFamily, face);
}

static struct {
    jmethodID mGet;
    jmethodID mSize;
} gListClassInfo;

static struct {
    jfieldID mTag;
    jfieldID mStyleValue;
} gAxisClassInfo;

static jboolean FontFamily_addFontWeightStyle(JNIEnv* env, jobject clazz, jlong familyPtr,
        jstring path, jint ttcIndex, jobject listOfAxis, jint weight, jboolean isItalic) {
    NPE_CHECK_RETURN_ZERO(env, path);

    // Declare axis native type.
    std::unique_ptr<SkFontMgr::FontParameters::Axis[]> skiaAxes;
    int skiaAxesLength = 0;
    if (listOfAxis) {
        jint listSize = env->CallIntMethod(listOfAxis, gListClassInfo.mSize);

        skiaAxes.reset(new SkFontMgr::FontParameters::Axis[listSize]);
        skiaAxesLength = listSize;
        for (jint i = 0; i < listSize; ++i) {
            jobject axisObject = env->CallObjectMethod(listOfAxis, gListClassInfo.mGet, i);
            if (!axisObject) {
                skiaAxes[i].fTag = 0;
                skiaAxes[i].fStyleValue = 0;
                continue;
            }

            jint tag = env->GetIntField(axisObject, gAxisClassInfo.mTag);
            jfloat stylevalue = env->GetFloatField(axisObject, gAxisClassInfo.mStyleValue);
            skiaAxes[i].fTag = tag;
            skiaAxes[i].fStyleValue = SkFloatToScalar(stylevalue);
        }
    }

    ScopedUtfChars str(env, path);
    SkAutoTUnref<SkFontMgr> fm(SkFontMgr::RefDefault());
    std::unique_ptr<SkStreamAsset> fontData(SkStream::NewFromFile(str.c_str()));
    if (!fontData) {
        ALOGE("addFont failed to open %s", str.c_str());
        return false;
    }
    SkFontMgr::FontParameters params;
    params.setCollectionIndex(ttcIndex);
    params.setAxes(skiaAxes.get(), skiaAxesLength);
    SkTypeface* face = fm->createFromStream(fontData.release(), params);
    if (face == NULL) {
        ALOGE("addFont failed to create font %s#%d", str.c_str(), ttcIndex);
        return false;
    }
    FontFamily* fontFamily = reinterpret_cast<FontFamily*>(familyPtr);
    MinikinFont* minikinFont = new MinikinFontSkia(face);
    fontFamily->addFont(minikinFont, FontStyle(weight / 100, isItalic));
    minikinFont->Unref();
    return true;
}

static void releaseAsset(const void* ptr, void* context) {
    delete static_cast<Asset*>(context);
}

static jboolean FontFamily_addFontFromAsset(JNIEnv* env, jobject, jlong familyPtr,
        jobject jassetMgr, jstring jpath) {
    NPE_CHECK_RETURN_ZERO(env, jassetMgr);
    NPE_CHECK_RETURN_ZERO(env, jpath);

    AssetManager* mgr = assetManagerForJavaObject(env, jassetMgr);
    if (NULL == mgr) {
        return false;
    }

    ScopedUtfChars str(env, jpath);
    Asset* asset = mgr->open(str.c_str(), Asset::ACCESS_BUFFER);
    if (NULL == asset) {
        return false;
    }

    const void* buf = asset->getBuffer(false);
    if (NULL == buf) {
        delete asset;
        return false;
    }

    SkAutoTUnref<SkData> data(SkData::NewWithProc(buf, asset->getLength(), releaseAsset, asset));
    SkMemoryStream* stream = new SkMemoryStream(data);
    // CreateFromStream takes ownership of stream.
    SkTypeface* face = SkTypeface::CreateFromStream(stream);
    if (face == NULL) {
        ALOGE("addFontFromAsset failed to create font %s", str.c_str());
        return false;
    }
    FontFamily* fontFamily = reinterpret_cast<FontFamily*>(familyPtr);
    return addSkTypeface(fontFamily, face);
}

///////////////////////////////////////////////////////////////////////////////

static const JNINativeMethod gFontFamilyMethods[] = {
    { "nCreateFamily",         "(Ljava/lang/String;I)J", (void*)FontFamily_create },
    { "nUnrefFamily",          "(J)V", (void*)FontFamily_unref },
    { "nAddFont",              "(JLjava/lang/String;I)Z", (void*)FontFamily_addFont },
    { "nAddFontWeightStyle",   "(JLjava/lang/String;ILjava/util/List;IZ)Z",
            (void*)FontFamily_addFontWeightStyle },
    { "nAddFontFromAsset",     "(JLandroid/content/res/AssetManager;Ljava/lang/String;)Z",
            (void*)FontFamily_addFontFromAsset },
};

int register_android_graphics_FontFamily(JNIEnv* env)
{
    int err = RegisterMethodsOrDie(env, "android/graphics/FontFamily", gFontFamilyMethods,
            NELEM(gFontFamilyMethods));

    jclass listClass = FindClassOrDie(env, "java/util/List");
    gListClassInfo.mGet = GetMethodIDOrDie(env, listClass, "get", "(I)Ljava/lang/Object;");
    gListClassInfo.mSize = GetMethodIDOrDie(env, listClass, "size", "()I");

    jclass axisClass = FindClassOrDie(env, "android/graphics/FontListParser$Axis");
    gAxisClassInfo.mTag = GetFieldIDOrDie(env, axisClass, "tag", "I");
    gAxisClassInfo.mStyleValue = GetFieldIDOrDie(env, axisClass, "styleValue", "F");

    return err;
}

}
