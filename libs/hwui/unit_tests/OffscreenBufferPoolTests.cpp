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

#include <gtest/gtest.h>
#include <renderstate/OffscreenBufferPool.h>

#include <utils/TestUtils.h>

using namespace android;
using namespace android::uirenderer;

TEST(OffscreenBuffer, computeIdealDimension) {
    EXPECT_EQ(64u, OffscreenBuffer::computeIdealDimension(1));
    EXPECT_EQ(64u, OffscreenBuffer::computeIdealDimension(31));
    EXPECT_EQ(64u, OffscreenBuffer::computeIdealDimension(33));
    EXPECT_EQ(64u, OffscreenBuffer::computeIdealDimension(64));
    EXPECT_EQ(1024u, OffscreenBuffer::computeIdealDimension(1000));
}

TEST(OffscreenBuffer, construct) {
    TestUtils::runOnRenderThread([] (renderthread::RenderThread& thread) {
        OffscreenBuffer layer(thread.renderState(), Caches::getInstance(), 49u, 149u);
        EXPECT_EQ(49u, layer.viewportWidth);
        EXPECT_EQ(149u, layer.viewportHeight);

        EXPECT_EQ(64u, layer.texture.width);
        EXPECT_EQ(192u, layer.texture.height);

        EXPECT_EQ(64u * 192u * 4u, layer.getSizeInBytes());
    });
}

TEST(OffscreenBufferPool, construct) {
    TestUtils::runOnRenderThread([] (renderthread::RenderThread& thread) {
        OffscreenBufferPool pool;
        EXPECT_EQ(0u, pool.getCount()) << "pool must be created empty";
        EXPECT_EQ(0u, pool.getSize()) << "pool must be created empty";
        EXPECT_EQ((uint32_t) Properties::layerPoolSize, pool.getMaxSize())
                << "pool must read size from Properties";
    });

}

TEST(OffscreenBufferPool, getPutClear) {
    TestUtils::runOnRenderThread([] (renderthread::RenderThread& thread) {
        OffscreenBufferPool pool;

        auto layer = pool.get(thread.renderState(), 100u, 200u);
        EXPECT_EQ(100u, layer->viewportWidth);
        EXPECT_EQ(200u, layer->viewportHeight);

        ASSERT_LT(layer->getSizeInBytes(), pool.getMaxSize());

        pool.putOrDelete(layer);
        ASSERT_EQ(layer->getSizeInBytes(), pool.getSize());

        auto layer2 = pool.get(thread.renderState(), 102u, 202u);
        EXPECT_EQ(layer, layer2) << "layer should be recycled";
        ASSERT_EQ(0u, pool.getSize()) << "pool should have been emptied by removing only layer";

        pool.putOrDelete(layer);
        EXPECT_EQ(1u, pool.getCount());
        pool.clear();
        EXPECT_EQ(0u, pool.getSize());
        EXPECT_EQ(0u, pool.getCount());
    });
}

TEST(OffscreenBufferPool, resize) {
    TestUtils::runOnRenderThread([] (renderthread::RenderThread& thread) {
        OffscreenBufferPool pool;

        auto layer = pool.get(thread.renderState(), 64u, 64u);

        // resize in place
        ASSERT_EQ(layer, pool.resize(layer, 60u, 55u));
        EXPECT_EQ(60u, layer->viewportWidth);
        EXPECT_EQ(55u, layer->viewportHeight);
        EXPECT_EQ(64u, layer->texture.width);
        EXPECT_EQ(64u, layer->texture.height);

        // resized to use different object in pool
        auto layer2 = pool.get(thread.renderState(), 128u, 128u);
        pool.putOrDelete(layer2);
        ASSERT_EQ(1u, pool.getCount());
        ASSERT_EQ(layer2, pool.resize(layer, 120u, 125u));
        EXPECT_EQ(120u, layer2->viewportWidth);
        EXPECT_EQ(125u, layer2->viewportHeight);
        EXPECT_EQ(128u, layer2->texture.width);
        EXPECT_EQ(128u, layer2->texture.height);

        // original allocation now only thing in pool
        EXPECT_EQ(1u, pool.getCount());
        EXPECT_EQ(layer->getSizeInBytes(), pool.getSize());
    });
}

TEST(OffscreenBufferPool, putAndDestroy) {
    TestUtils::runOnRenderThread([] (renderthread::RenderThread& thread) {
        OffscreenBufferPool pool;
        // layer too big to return to the pool
        // Note: this relies on the fact that the pool won't reject based on max texture size
        auto hugeLayer = pool.get(thread.renderState(), pool.getMaxSize() / 64, 64);
        EXPECT_GT(hugeLayer->getSizeInBytes(), pool.getMaxSize());
        pool.putOrDelete(hugeLayer);
        EXPECT_EQ(0u, pool.getCount()); // failed to put (so was destroyed instead)
    });
}
