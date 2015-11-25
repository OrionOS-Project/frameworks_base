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

#ifndef ANDROID_HWUI_BAKED_OP_DISPATCHER_H
#define ANDROID_HWUI_BAKED_OP_DISPATCHER_H

#include "BakedOpState.h"
#include "RecordedOp.h"

namespace android {
namespace uirenderer {

/**
 * Provides all "onBitmapOp(...)" style static methods for every op type, which convert the
 * RecordedOps and their state to Glops, and renders them with the provided BakedOpRenderer.
 *
 * This dispatcher is separate from the renderer so that the dispatcher / renderer interaction is
 * minimal through public BakedOpRenderer APIs.
 */
class BakedOpDispatcher {
public:
    // Declares all "onBitmapOp(...)" style methods for every op type
#define DISPATCH_METHOD(Type) \
        static void on##Type(BakedOpRenderer& renderer, const Type& op, const BakedOpState& state);
    MAP_OPS(DISPATCH_METHOD);
};

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_BAKED_OP_DISPATCHER_H
