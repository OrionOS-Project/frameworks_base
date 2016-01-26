/*
 * Copyright (C) 2016 The Android Open Source Project
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

#include <BakedOpState.h>
#include <DeferredLayerUpdater.h>
#include <FrameBuilder.h>
#include <LayerUpdateQueue.h>
#include <RecordedOp.h>
#include <RecordingCanvas.h>
#include <tests/common/TestUtils.h>

#include <unordered_map>

namespace android {
namespace uirenderer {

const LayerUpdateQueue sEmptyLayerUpdateQueue;
const Vector3 sLightCenter = {100, 100, 100};

/**
 * Virtual class implemented by each test to redirect static operation / state transitions to
 * virtual methods.
 *
 * Virtual dispatch allows for default behaviors to be specified (very common case in below tests),
 * and allows Renderer vs Dispatching behavior to be merged.
 *
 * onXXXOp methods fail by default - tests should override ops they expect
 * startRepaintLayer fails by default - tests should override if expected
 * startFrame/endFrame do nothing by default - tests should override to intercept
 */
class TestRendererBase {
public:
    virtual ~TestRendererBase() {}
    virtual OffscreenBuffer* startTemporaryLayer(uint32_t, uint32_t) {
        ADD_FAILURE() << "Layer creation not expected in this test";
        return nullptr;
    }
    virtual void startRepaintLayer(OffscreenBuffer*, const Rect& repaintRect) {
        ADD_FAILURE() << "Layer repaint not expected in this test";
    }
    virtual void endLayer() {
        ADD_FAILURE() << "Layer updates not expected in this test";
    }
    virtual void startFrame(uint32_t width, uint32_t height, const Rect& repaintRect) {}
    virtual void endFrame(const Rect& repaintRect) {}

    // define virtual defaults for single draw methods
#define X(Type) \
    virtual void on##Type(const Type&, const BakedOpState&) { \
        ADD_FAILURE() << #Type " not expected in this test"; \
    }
    MAP_RENDERABLE_OPS(X)
#undef X

    // define virtual defaults for merged draw methods
#define X(Type) \
    virtual void onMerged##Type##s(const MergedBakedOpList& opList) { \
        ADD_FAILURE() << "Merged " #Type "s not expected in this test"; \
    }
    MAP_MERGEABLE_OPS(X)
#undef X

    int getIndex() { return mIndex; }

protected:
    int mIndex = 0;
};

/**
 * Dispatches all static methods to similar formed methods on renderer, which fail by default but
 * are overridden by subclasses per test.
 */
class TestDispatcher {
public:
    // define single op methods, which redirect to TestRendererBase
#define X(Type) \
    static void on##Type(TestRendererBase& renderer, const Type& op, const BakedOpState& state) { \
        renderer.on##Type(op, state); \
    }
    MAP_RENDERABLE_OPS(X);
#undef X

    // define merged op methods, which redirect to TestRendererBase
#define X(Type) \
    static void onMerged##Type##s(TestRendererBase& renderer, const MergedBakedOpList& opList) { \
        renderer.onMerged##Type##s(opList); \
    }
    MAP_MERGEABLE_OPS(X);
#undef X
};

class FailRenderer : public TestRendererBase {};

TEST(FrameBuilder, simple) {
    class SimpleTestRenderer : public TestRendererBase {
    public:
        void startFrame(uint32_t width, uint32_t height, const Rect& repaintRect) override {
            EXPECT_EQ(0, mIndex++);
            EXPECT_EQ(100u, width);
            EXPECT_EQ(200u, height);
        }
        void onRectOp(const RectOp& op, const BakedOpState& state) override {
            EXPECT_EQ(1, mIndex++);
        }
        void onBitmapOp(const BitmapOp& op, const BakedOpState& state) override {
            EXPECT_EQ(2, mIndex++);
        }
        void endFrame(const Rect& repaintRect) override {
            EXPECT_EQ(3, mIndex++);
        }
    };

    auto node = TestUtils::createNode(0, 0, 100, 200,
            [](RenderProperties& props, RecordingCanvas& canvas) {
        SkBitmap bitmap = TestUtils::createSkBitmap(25, 25);
        canvas.drawRect(0, 0, 100, 200, SkPaint());
        canvas.drawBitmap(bitmap, 10, 10, nullptr);
    });
    FrameBuilder frameBuilder(sEmptyLayerUpdateQueue, SkRect::MakeWH(100, 200), 100, 200,
            TestUtils::createSyncedNodeList(node), sLightCenter);
    SimpleTestRenderer renderer;
    frameBuilder.replayBakedOps<TestDispatcher>(renderer);
    EXPECT_EQ(4, renderer.getIndex()); // 2 ops + start + end
}

TEST(FrameBuilder, simpleStroke) {
    class SimpleStrokeTestRenderer : public TestRendererBase {
    public:
        void onPointsOp(const PointsOp& op, const BakedOpState& state) override {
            EXPECT_EQ(0, mIndex++);
            // even though initial bounds are empty...
            EXPECT_TRUE(op.unmappedBounds.isEmpty())
                    << "initial bounds should be empty, since they're unstroked";
            EXPECT_EQ(Rect(45, 45, 55, 55), state.computedState.clippedBounds)
                    << "final bounds should account for stroke";
        }
    };

    auto node = TestUtils::createNode(0, 0, 100, 200,
            [](RenderProperties& props, RecordingCanvas& canvas) {
        SkPaint strokedPaint;
        strokedPaint.setStrokeWidth(10);
        canvas.drawPoint(50, 50, strokedPaint);
    });
    FrameBuilder frameBuilder(sEmptyLayerUpdateQueue, SkRect::MakeWH(100, 200), 100, 200,
            TestUtils::createSyncedNodeList(node), sLightCenter);
    SimpleStrokeTestRenderer renderer;
    frameBuilder.replayBakedOps<TestDispatcher>(renderer);
    EXPECT_EQ(1, renderer.getIndex());
}

TEST(FrameBuilder, simpleRejection) {
    auto node = TestUtils::createNode(0, 0, 200, 200,
            [](RenderProperties& props, RecordingCanvas& canvas) {
        canvas.save(SkCanvas::kMatrix_SaveFlag | SkCanvas::kClip_SaveFlag);
        canvas.clipRect(200, 200, 400, 400, SkRegion::kIntersect_Op); // intersection should be empty
        canvas.drawRect(0, 0, 400, 400, SkPaint());
        canvas.restore();
    });
    FrameBuilder frameBuilder(sEmptyLayerUpdateQueue, SkRect::MakeWH(200, 200), 200, 200,
            TestUtils::createSyncedNodeList(node), sLightCenter);

    FailRenderer renderer;
    frameBuilder.replayBakedOps<TestDispatcher>(renderer);
}

TEST(FrameBuilder, simpleBatching) {
    const int LOOPS = 5;
    class SimpleBatchingTestRenderer : public TestRendererBase {
    public:
        void onBitmapOp(const BitmapOp& op, const BakedOpState& state) override {
            EXPECT_TRUE(mIndex++ >= LOOPS) << "Bitmaps should be above all rects";
        }
        void onRectOp(const RectOp& op, const BakedOpState& state) override {
            EXPECT_TRUE(mIndex++ < LOOPS) << "Rects should be below all bitmaps";
        }
    };

    auto node = TestUtils::createNode(0, 0, 200, 200,
            [](RenderProperties& props, RecordingCanvas& canvas) {
        SkBitmap bitmap = TestUtils::createSkBitmap(10, 10,
                kAlpha_8_SkColorType); // Disable merging by using alpha 8 bitmap

        // Alternate between drawing rects and bitmaps, with bitmaps overlapping rects.
        // Rects don't overlap bitmaps, so bitmaps should be brought to front as a group.
        canvas.save(SkCanvas::kMatrix_SaveFlag | SkCanvas::kClip_SaveFlag);
        for (int i = 0; i < LOOPS; i++) {
            canvas.translate(0, 10);
            canvas.drawRect(0, 0, 10, 10, SkPaint());
            canvas.drawBitmap(bitmap, 5, 0, nullptr);
        }
        canvas.restore();
    });

    FrameBuilder frameBuilder(sEmptyLayerUpdateQueue, SkRect::MakeWH(200, 200), 200, 200,
            TestUtils::createSyncedNodeList(node), sLightCenter);
    SimpleBatchingTestRenderer renderer;
    frameBuilder.replayBakedOps<TestDispatcher>(renderer);
    EXPECT_EQ(2 * LOOPS, renderer.getIndex())
            << "Expect number of ops = 2 * loop count";
}

// TODO: Disabled due to b/26793764
TEST(FrameBuilder, DISABLED_clippedMerging) {
    class ClippedMergingTestRenderer : public TestRendererBase {
    public:
        void onMergedBitmapOps(const MergedBakedOpList& opList) override {
            EXPECT_EQ(0, mIndex);
            mIndex += opList.count;
            EXPECT_EQ(4u, opList.count);
            EXPECT_EQ(Rect(10, 10, 90, 90), opList.clip);
            EXPECT_EQ(OpClipSideFlags::Left | OpClipSideFlags::Top | OpClipSideFlags::Right,
                    opList.clipSideFlags);
        }
    };
    auto node = TestUtils::createNode(0, 0, 100, 100,
            [](RenderProperties& props, TestCanvas& canvas) {
        SkBitmap bitmap = TestUtils::createSkBitmap(20, 20);

        // left side clipped (to inset left half)
        canvas.clipRect(10, 0, 50, 100, SkRegion::kReplace_Op);
        canvas.drawBitmap(bitmap, 0, 40, nullptr);

        // top side clipped (to inset top half)
        canvas.clipRect(0, 10, 100, 50, SkRegion::kReplace_Op);
        canvas.drawBitmap(bitmap, 40, 0, nullptr);

        // right side clipped (to inset right half)
        canvas.clipRect(50, 0, 90, 100, SkRegion::kReplace_Op);
        canvas.drawBitmap(bitmap, 80, 40, nullptr);

        // bottom not clipped, just abutting (inset bottom half)
        canvas.clipRect(0, 50, 100, 90, SkRegion::kReplace_Op);
        canvas.drawBitmap(bitmap, 40, 70, nullptr);
    });

    FrameBuilder frameBuilder(sEmptyLayerUpdateQueue, SkRect::MakeWH(100, 100), 100, 100,
            TestUtils::createSyncedNodeList(node), sLightCenter);
    ClippedMergingTestRenderer renderer;
    frameBuilder.replayBakedOps<TestDispatcher>(renderer);
    EXPECT_EQ(4, renderer.getIndex());
}

TEST(FrameBuilder, textMerging) {
    class TextMergingTestRenderer : public TestRendererBase {
    public:
        void onMergedTextOps(const MergedBakedOpList& opList) override {
            EXPECT_EQ(0, mIndex);
            mIndex += opList.count;
            EXPECT_EQ(2u, opList.count);
            EXPECT_EQ(OpClipSideFlags::Top, opList.clipSideFlags);
            EXPECT_EQ(OpClipSideFlags::Top, opList.states[0]->computedState.clipSideFlags);
            EXPECT_EQ(OpClipSideFlags::None, opList.states[1]->computedState.clipSideFlags);
        }
    };
    auto node = TestUtils::createNode(0, 0, 400, 400,
            [](RenderProperties& props, TestCanvas& canvas) {
        SkPaint paint;
        paint.setTextEncoding(SkPaint::kGlyphID_TextEncoding);
        paint.setAntiAlias(true);
        paint.setTextSize(50);
        TestUtils::drawTextToCanvas(&canvas, "Test string1", paint, 100, 0); // will be top clipped
        TestUtils::drawTextToCanvas(&canvas, "Test string1", paint, 100, 100); // not clipped
    });
    FrameBuilder frameBuilder(sEmptyLayerUpdateQueue, SkRect::MakeWH(400, 400), 400, 400,
            TestUtils::createSyncedNodeList(node), sLightCenter);
    TextMergingTestRenderer renderer;
    frameBuilder.replayBakedOps<TestDispatcher>(renderer);
    EXPECT_EQ(2, renderer.getIndex()) << "Expect 2 ops";
}

TEST(FrameBuilder, textStrikethrough) {
    const int LOOPS = 5;
    class TextStrikethroughTestRenderer : public TestRendererBase {
    public:
        void onRectOp(const RectOp& op, const BakedOpState& state) override {
            EXPECT_TRUE(mIndex++ >= LOOPS) << "Strikethrough rects should be above all text";
        }
        void onMergedTextOps(const MergedBakedOpList& opList) override {
            EXPECT_EQ(0, mIndex);
            mIndex += opList.count;
            EXPECT_EQ(5u, opList.count);
        }
    };
    auto node = TestUtils::createNode(0, 0, 200, 2000,
            [](RenderProperties& props, RecordingCanvas& canvas) {
        SkPaint textPaint;
        textPaint.setAntiAlias(true);
        textPaint.setTextSize(20);
        textPaint.setStrikeThruText(true);
        textPaint.setTextEncoding(SkPaint::kGlyphID_TextEncoding);
        for (int i = 0; i < LOOPS; i++) {
            TestUtils::drawTextToCanvas(&canvas, "test text", textPaint, 10, 100 * (i + 1));
        }
    });
    FrameBuilder frameBuilder(sEmptyLayerUpdateQueue, SkRect::MakeWH(200, 2000), 200, 2000,
            TestUtils::createSyncedNodeList(node), sLightCenter);
    TextStrikethroughTestRenderer renderer;
    frameBuilder.replayBakedOps<TestDispatcher>(renderer);
    EXPECT_EQ(2 * LOOPS, renderer.getIndex())
            << "Expect number of ops = 2 * loop count";
}

RENDERTHREAD_TEST(FrameBuilder, textureLayer) {
    class TextureLayerTestRenderer : public TestRendererBase {
    public:
        void onTextureLayerOp(const TextureLayerOp& op, const BakedOpState& state) override {
            EXPECT_EQ(0, mIndex++);
            EXPECT_EQ(Rect(50, 50, 150, 150), state.computedState.clipRect());
            EXPECT_EQ(Rect(50, 50, 105, 105), state.computedState.clippedBounds);

            Matrix4 expected;
            expected.loadTranslate(5, 5, 0);
            EXPECT_MATRIX_APPROX_EQ(expected, state.computedState.transform);
        }
    };

    auto layerUpdater = TestUtils::createTextureLayerUpdater(renderThread, 100, 100,
            [](Matrix4* transform) {
        transform->loadTranslate(5, 5, 0);
    });

    auto node = TestUtils::createNode(0, 0, 200, 200,
            [&layerUpdater](RenderProperties& props, RecordingCanvas& canvas) {
        canvas.save(SkCanvas::kMatrixClip_SaveFlag);
        canvas.clipRect(50, 50, 150, 150, SkRegion::kIntersect_Op);
        canvas.drawLayer(layerUpdater.get());
        canvas.restore();
    });
    FrameBuilder frameBuilder(sEmptyLayerUpdateQueue, SkRect::MakeWH(200, 200), 200, 200,
            TestUtils::createSyncedNodeList(node), sLightCenter);
    TextureLayerTestRenderer renderer;
    frameBuilder.replayBakedOps<TestDispatcher>(renderer);
    EXPECT_EQ(1, renderer.getIndex());
}

TEST(FrameBuilder, renderNode) {
    class RenderNodeTestRenderer : public TestRendererBase {
    public:
        void onRectOp(const RectOp& op, const BakedOpState& state) override {
            switch(mIndex++) {
            case 0:
                EXPECT_EQ(Rect(200, 200), state.computedState.clippedBounds);
                EXPECT_EQ(SK_ColorDKGRAY, op.paint->getColor());
                break;
            case 1:
                EXPECT_EQ(Rect(50, 50, 150, 150), state.computedState.clippedBounds);
                EXPECT_EQ(SK_ColorWHITE, op.paint->getColor());
                break;
            default:
                ADD_FAILURE();
            }
        }
    };

    auto child = TestUtils::createNode(10, 10, 110, 110,
            [](RenderProperties& props, RecordingCanvas& canvas) {
        SkPaint paint;
        paint.setColor(SK_ColorWHITE);
        canvas.drawRect(0, 0, 100, 100, paint);
    });

    auto parent = TestUtils::createNode(0, 0, 200, 200,
            [&child](RenderProperties& props, RecordingCanvas& canvas) {
        SkPaint paint;
        paint.setColor(SK_ColorDKGRAY);
        canvas.drawRect(0, 0, 200, 200, paint);

        canvas.save(SkCanvas::kMatrix_SaveFlag | SkCanvas::kClip_SaveFlag);
        canvas.translate(40, 40);
        canvas.drawRenderNode(child.get());
        canvas.restore();
    });

    FrameBuilder frameBuilder(sEmptyLayerUpdateQueue, SkRect::MakeWH(200, 200), 200, 200,
            TestUtils::createSyncedNodeList(parent), sLightCenter);
    RenderNodeTestRenderer renderer;
    frameBuilder.replayBakedOps<TestDispatcher>(renderer);
}

TEST(FrameBuilder, clipped) {
    class ClippedTestRenderer : public TestRendererBase {
    public:
        void onBitmapOp(const BitmapOp& op, const BakedOpState& state) override {
            EXPECT_EQ(0, mIndex++);
            EXPECT_EQ(Rect(10, 20, 30, 40), state.computedState.clippedBounds);
            EXPECT_EQ(Rect(10, 20, 30, 40), state.computedState.clipRect());
            EXPECT_TRUE(state.computedState.transform.isIdentity());
        }
    };

    auto node = TestUtils::createNode(0, 0, 200, 200,
            [](RenderProperties& props, RecordingCanvas& canvas) {
        SkBitmap bitmap = TestUtils::createSkBitmap(200, 200);
        canvas.drawBitmap(bitmap, 0, 0, nullptr);
    });

    FrameBuilder frameBuilder(sEmptyLayerUpdateQueue,
            SkRect::MakeLTRB(10, 20, 30, 40), // clip to small area, should see in receiver
            200, 200, TestUtils::createSyncedNodeList(node), sLightCenter);
    ClippedTestRenderer renderer;
    frameBuilder.replayBakedOps<TestDispatcher>(renderer);
}

TEST(FrameBuilder, saveLayer_simple) {
    class SaveLayerSimpleTestRenderer : public TestRendererBase {
    public:
        OffscreenBuffer* startTemporaryLayer(uint32_t width, uint32_t height) override {
            EXPECT_EQ(0, mIndex++);
            EXPECT_EQ(180u, width);
            EXPECT_EQ(180u, height);
            return nullptr;
        }
        void endLayer() override {
            EXPECT_EQ(2, mIndex++);
        }
        void onRectOp(const RectOp& op, const BakedOpState& state) override {
            EXPECT_EQ(1, mIndex++);
            EXPECT_EQ(Rect(10, 10, 190, 190), op.unmappedBounds);
            EXPECT_EQ(Rect(180, 180), state.computedState.clippedBounds);
            EXPECT_EQ(Rect(180, 180), state.computedState.clipRect());

            Matrix4 expectedTransform;
            expectedTransform.loadTranslate(-10, -10, 0);
            EXPECT_MATRIX_APPROX_EQ(expectedTransform, state.computedState.transform);
        }
        void onLayerOp(const LayerOp& op, const BakedOpState& state) override {
            EXPECT_EQ(3, mIndex++);
            EXPECT_EQ(Rect(10, 10, 190, 190), state.computedState.clippedBounds);
            EXPECT_EQ(Rect(200, 200), state.computedState.clipRect());
            EXPECT_TRUE(state.computedState.transform.isIdentity());
        }
    };

    auto node = TestUtils::createNode(0, 0, 200, 200,
            [](RenderProperties& props, RecordingCanvas& canvas) {
        canvas.saveLayerAlpha(10, 10, 190, 190, 128, SkCanvas::kClipToLayer_SaveFlag);
        canvas.drawRect(10, 10, 190, 190, SkPaint());
        canvas.restore();
    });
    FrameBuilder frameBuilder(sEmptyLayerUpdateQueue, SkRect::MakeWH(200, 200), 200, 200,
            TestUtils::createSyncedNodeList(node), sLightCenter);
    SaveLayerSimpleTestRenderer renderer;
    frameBuilder.replayBakedOps<TestDispatcher>(renderer);
    EXPECT_EQ(4, renderer.getIndex());
}

TEST(FrameBuilder, saveLayer_nested) {
    /* saveLayer1 { rect1, saveLayer2 { rect2 } } will play back as:
     * - startTemporaryLayer2, rect2 endLayer2
     * - startTemporaryLayer1, rect1, drawLayer2, endLayer1
     * - startFrame, layerOp1, endFrame
     */
    class SaveLayerNestedTestRenderer : public TestRendererBase {
    public:
        OffscreenBuffer* startTemporaryLayer(uint32_t width, uint32_t height) override {
            const int index = mIndex++;
            if (index == 0) {
                EXPECT_EQ(400u, width);
                EXPECT_EQ(400u, height);
                return (OffscreenBuffer*) 0x400;
            } else if (index == 3) {
                EXPECT_EQ(800u, width);
                EXPECT_EQ(800u, height);
                return (OffscreenBuffer*) 0x800;
            } else { ADD_FAILURE(); }
            return (OffscreenBuffer*) nullptr;
        }
        void endLayer() override {
            int index = mIndex++;
            EXPECT_TRUE(index == 2 || index == 6);
        }
        void startFrame(uint32_t width, uint32_t height, const Rect& repaintRect) override {
            EXPECT_EQ(7, mIndex++);
        }
        void endFrame(const Rect& repaintRect) override {
            EXPECT_EQ(9, mIndex++);
        }
        void onRectOp(const RectOp& op, const BakedOpState& state) override {
            const int index = mIndex++;
            if (index == 1) {
                EXPECT_EQ(Rect(400, 400), op.unmappedBounds); // inner rect
            } else if (index == 4) {
                EXPECT_EQ(Rect(800, 800), op.unmappedBounds); // outer rect
            } else { ADD_FAILURE(); }
        }
        void onLayerOp(const LayerOp& op, const BakedOpState& state) override {
            const int index = mIndex++;
            if (index == 5) {
                EXPECT_EQ((OffscreenBuffer*)0x400, *op.layerHandle);
                EXPECT_EQ(Rect(400, 400), op.unmappedBounds); // inner layer
            } else if (index == 8) {
                EXPECT_EQ((OffscreenBuffer*)0x800, *op.layerHandle);
                EXPECT_EQ(Rect(800, 800), op.unmappedBounds); // outer layer
            } else { ADD_FAILURE(); }
        }
    };

    auto node = TestUtils::createNode(0, 0, 800, 800,
            [](RenderProperties& props, RecordingCanvas& canvas) {
        canvas.saveLayerAlpha(0, 0, 800, 800, 128, SkCanvas::kClipToLayer_SaveFlag);
        {
            canvas.drawRect(0, 0, 800, 800, SkPaint());
            canvas.saveLayerAlpha(0, 0, 400, 400, 128, SkCanvas::kClipToLayer_SaveFlag);
            {
                canvas.drawRect(0, 0, 400, 400, SkPaint());
            }
            canvas.restore();
        }
        canvas.restore();
    });

    FrameBuilder frameBuilder(sEmptyLayerUpdateQueue, SkRect::MakeWH(800, 800), 800, 800,
            TestUtils::createSyncedNodeList(node), sLightCenter);
    SaveLayerNestedTestRenderer renderer;
    frameBuilder.replayBakedOps<TestDispatcher>(renderer);
    EXPECT_EQ(10, renderer.getIndex());
}

TEST(FrameBuilder, saveLayer_contentRejection) {
        auto node = TestUtils::createNode(0, 0, 200, 200,
                [](RenderProperties& props, RecordingCanvas& canvas) {
        canvas.save(SkCanvas::kMatrix_SaveFlag | SkCanvas::kClip_SaveFlag);
        canvas.clipRect(200, 200, 400, 400, SkRegion::kIntersect_Op);
        canvas.saveLayerAlpha(200, 200, 400, 400, 128, SkCanvas::kClipToLayer_SaveFlag);

        // draw within save layer may still be recorded, but shouldn't be drawn
        canvas.drawRect(200, 200, 400, 400, SkPaint());

        canvas.restore();
        canvas.restore();
    });
    FrameBuilder frameBuilder(sEmptyLayerUpdateQueue, SkRect::MakeWH(200, 200), 200, 200,
            TestUtils::createSyncedNodeList(node), sLightCenter);

    FailRenderer renderer;
    // should see no ops, even within the layer, since the layer should be rejected
    frameBuilder.replayBakedOps<TestDispatcher>(renderer);
}

TEST(FrameBuilder, saveLayerUnclipped_simple) {
    class SaveLayerUnclippedSimpleTestRenderer : public TestRendererBase {
    public:
        void onCopyToLayerOp(const CopyToLayerOp& op, const BakedOpState& state) override {
            EXPECT_EQ(0, mIndex++);
            EXPECT_EQ(Rect(10, 10, 190, 190), state.computedState.clippedBounds);
            EXPECT_CLIP_RECT(Rect(200, 200), state.computedState.clipState);
            EXPECT_TRUE(state.computedState.transform.isIdentity());
        }
        void onSimpleRectsOp(const SimpleRectsOp& op, const BakedOpState& state) override {
            EXPECT_EQ(1, mIndex++);
            ASSERT_NE(nullptr, op.paint);
            ASSERT_EQ(SkXfermode::kClear_Mode, PaintUtils::getXfermodeDirect(op.paint));
        }
        void onRectOp(const RectOp& op, const BakedOpState& state) override {
            EXPECT_EQ(2, mIndex++);
            EXPECT_EQ(Rect(200, 200), op.unmappedBounds);
            EXPECT_EQ(Rect(200, 200), state.computedState.clippedBounds);
            EXPECT_EQ(Rect(200, 200), state.computedState.clipRect());
            EXPECT_TRUE(state.computedState.transform.isIdentity());
        }
        void onCopyFromLayerOp(const CopyFromLayerOp& op, const BakedOpState& state) override {
            EXPECT_EQ(3, mIndex++);
            EXPECT_EQ(Rect(10, 10, 190, 190), state.computedState.clippedBounds);
            EXPECT_CLIP_RECT(Rect(200, 200), state.computedState.clipState);
            EXPECT_TRUE(state.computedState.transform.isIdentity());
        }
    };

    auto node = TestUtils::createNode(0, 0, 200, 200,
            [](RenderProperties& props, RecordingCanvas& canvas) {
        canvas.saveLayerAlpha(10, 10, 190, 190, 128, (SkCanvas::SaveFlags)(0));
        canvas.drawRect(0, 0, 200, 200, SkPaint());
        canvas.restore();
    });
    FrameBuilder frameBuilder(sEmptyLayerUpdateQueue, SkRect::MakeWH(200, 200), 200, 200,
            TestUtils::createSyncedNodeList(node), sLightCenter);
    SaveLayerUnclippedSimpleTestRenderer renderer;
    frameBuilder.replayBakedOps<TestDispatcher>(renderer);
    EXPECT_EQ(4, renderer.getIndex());
}

TEST(FrameBuilder, saveLayerUnclipped_mergedClears) {
    class SaveLayerUnclippedMergedClearsTestRenderer : public TestRendererBase {
    public:
        void onCopyToLayerOp(const CopyToLayerOp& op, const BakedOpState& state) override {
            int index = mIndex++;
            EXPECT_GT(4, index);
            EXPECT_EQ(5, op.unmappedBounds.getWidth());
            EXPECT_EQ(5, op.unmappedBounds.getHeight());
            if (index == 0) {
                EXPECT_EQ(Rect(10, 10), state.computedState.clippedBounds);
            } else if (index == 1) {
                EXPECT_EQ(Rect(190, 0, 200, 10), state.computedState.clippedBounds);
            } else if (index == 2) {
                EXPECT_EQ(Rect(0, 190, 10, 200), state.computedState.clippedBounds);
            } else if (index == 3) {
                EXPECT_EQ(Rect(190, 190, 200, 200), state.computedState.clippedBounds);
            }
        }
        void onSimpleRectsOp(const SimpleRectsOp& op, const BakedOpState& state) override {
            EXPECT_EQ(4, mIndex++);
            ASSERT_EQ(op.vertexCount, 16u);
            for (size_t i = 0; i < op.vertexCount; i++) {
                auto v = op.vertices[i];
                EXPECT_TRUE(v.x == 0 || v.x == 10 || v.x == 190 || v.x == 200);
                EXPECT_TRUE(v.y == 0 || v.y == 10 || v.y == 190 || v.y == 200);
            }
        }
        void onRectOp(const RectOp& op, const BakedOpState& state) override {
            EXPECT_EQ(5, mIndex++);
        }
        void onCopyFromLayerOp(const CopyFromLayerOp& op, const BakedOpState& state) override {
            EXPECT_LT(5, mIndex++);
        }
    };

    auto node = TestUtils::createNode(0, 0, 200, 200,
            [](RenderProperties& props, RecordingCanvas& canvas) {

        int restoreTo = canvas.save(SkCanvas::kMatrixClip_SaveFlag);
        canvas.scale(2, 2);
        canvas.saveLayerAlpha(0, 0, 5, 5, 128, SkCanvas::kMatrixClip_SaveFlag);
        canvas.saveLayerAlpha(95, 0, 100, 5, 128, SkCanvas::kMatrixClip_SaveFlag);
        canvas.saveLayerAlpha(0, 95, 5, 100, 128, SkCanvas::kMatrixClip_SaveFlag);
        canvas.saveLayerAlpha(95, 95, 100, 100, 128, SkCanvas::kMatrixClip_SaveFlag);
        canvas.drawRect(0, 0, 100, 100, SkPaint());
        canvas.restoreToCount(restoreTo);
    });
    FrameBuilder frameBuilder(sEmptyLayerUpdateQueue, SkRect::MakeWH(200, 200), 200, 200,
            TestUtils::createSyncedNodeList(node), sLightCenter);
    SaveLayerUnclippedMergedClearsTestRenderer renderer;
    frameBuilder.replayBakedOps<TestDispatcher>(renderer);
    EXPECT_EQ(10, renderer.getIndex())
            << "Expect 4 copyTos, 4 copyFroms, 1 clear SimpleRects, and 1 rect.";
}

/* saveLayerUnclipped { saveLayer { saveLayerUnclipped { rect } } } will play back as:
 * - startTemporaryLayer, onCopyToLayer, onSimpleRects, onRect, onCopyFromLayer, endLayer
 * - startFrame, onCopyToLayer, onSimpleRects, drawLayer, onCopyFromLayer, endframe
 */
TEST(FrameBuilder, saveLayerUnclipped_complex) {
    class SaveLayerUnclippedComplexTestRenderer : public TestRendererBase {
    public:
        OffscreenBuffer* startTemporaryLayer(uint32_t width, uint32_t height) {
            EXPECT_EQ(0, mIndex++); // savelayer first
            return (OffscreenBuffer*)0xabcd;
        }
        void onCopyToLayerOp(const CopyToLayerOp& op, const BakedOpState& state) override {
            int index = mIndex++;
            EXPECT_TRUE(index == 1 || index == 7);
        }
        void onSimpleRectsOp(const SimpleRectsOp& op, const BakedOpState& state) override {
            int index = mIndex++;
            EXPECT_TRUE(index == 2 || index == 8);
        }
        void onRectOp(const RectOp& op, const BakedOpState& state) override {
            EXPECT_EQ(3, mIndex++);
            Matrix4 expected;
            expected.loadTranslate(-100, -100, 0);
            EXPECT_EQ(Rect(100, 100, 200, 200), state.computedState.clippedBounds);
            EXPECT_MATRIX_APPROX_EQ(expected, state.computedState.transform);
        }
        void onCopyFromLayerOp(const CopyFromLayerOp& op, const BakedOpState& state) override {
            int index = mIndex++;
            EXPECT_TRUE(index == 4 || index == 10);
        }
        void endLayer() override {
            EXPECT_EQ(5, mIndex++);
        }
        void startFrame(uint32_t width, uint32_t height, const Rect& repaintRect) override {
            EXPECT_EQ(6, mIndex++);
        }
        void onLayerOp(const LayerOp& op, const BakedOpState& state) override {
            EXPECT_EQ(9, mIndex++);
        }
        void endFrame(const Rect& repaintRect) override {
            EXPECT_EQ(11, mIndex++);
        }
    };

    auto node = TestUtils::createNode(0, 0, 600, 600, // 500x500 triggers clipping
            [](RenderProperties& props, RecordingCanvas& canvas) {
        canvas.saveLayerAlpha(0, 0, 500, 500, 128, (SkCanvas::SaveFlags)0); // unclipped
        canvas.saveLayerAlpha(100, 100, 400, 400, 128, SkCanvas::kClipToLayer_SaveFlag); // clipped
        canvas.saveLayerAlpha(200, 200, 300, 300, 128, (SkCanvas::SaveFlags)0); // unclipped
        canvas.drawRect(200, 200, 300, 300, SkPaint());
        canvas.restore();
        canvas.restore();
        canvas.restore();
    });
    FrameBuilder frameBuilder(sEmptyLayerUpdateQueue, SkRect::MakeWH(600, 600), 600, 600,
            TestUtils::createSyncedNodeList(node), sLightCenter);
    SaveLayerUnclippedComplexTestRenderer renderer;
    frameBuilder.replayBakedOps<TestDispatcher>(renderer);
    EXPECT_EQ(12, renderer.getIndex());
}

RENDERTHREAD_TEST(FrameBuilder, hwLayer_simple) {
    class HwLayerSimpleTestRenderer : public TestRendererBase {
    public:
        void startRepaintLayer(OffscreenBuffer* offscreenBuffer, const Rect& repaintRect) override {
            EXPECT_EQ(0, mIndex++);
            EXPECT_EQ(100u, offscreenBuffer->viewportWidth);
            EXPECT_EQ(100u, offscreenBuffer->viewportHeight);
            EXPECT_EQ(Rect(25, 25, 75, 75), repaintRect);
        }
        void onRectOp(const RectOp& op, const BakedOpState& state) override {
            EXPECT_EQ(1, mIndex++);

            EXPECT_TRUE(state.computedState.transform.isIdentity())
                    << "Transform should be reset within layer";

            EXPECT_EQ(Rect(25, 25, 75, 75), state.computedState.clipRect())
                    << "Damage rect should be used to clip layer content";
        }
        void endLayer() override {
            EXPECT_EQ(2, mIndex++);
        }
        void startFrame(uint32_t width, uint32_t height, const Rect& repaintRect) override {
            EXPECT_EQ(3, mIndex++);
        }
        void onLayerOp(const LayerOp& op, const BakedOpState& state) override {
            EXPECT_EQ(4, mIndex++);
        }
        void endFrame(const Rect& repaintRect) override {
            EXPECT_EQ(5, mIndex++);
        }
    };

    auto node = TestUtils::createNode(10, 10, 110, 110,
            [](RenderProperties& props, RecordingCanvas& canvas) {
        props.mutateLayerProperties().setType(LayerType::RenderLayer);
        SkPaint paint;
        paint.setColor(SK_ColorWHITE);
        canvas.drawRect(0, 0, 100, 100, paint);
    });
    OffscreenBuffer** layerHandle = node->getLayerHandle();

    // create RenderNode's layer here in same way prepareTree would
    OffscreenBuffer layer(renderThread.renderState(), Caches::getInstance(), 100, 100);
    *layerHandle = &layer;

    auto syncedNodeList = TestUtils::createSyncedNodeList(node);

    // only enqueue partial damage
    LayerUpdateQueue layerUpdateQueue; // Note: enqueue damage post-sync, so bounds are valid
    layerUpdateQueue.enqueueLayerWithDamage(node.get(), Rect(25, 25, 75, 75));

    FrameBuilder frameBuilder(layerUpdateQueue, SkRect::MakeWH(200, 200), 200, 200,
            syncedNodeList, sLightCenter);
    HwLayerSimpleTestRenderer renderer;
    frameBuilder.replayBakedOps<TestDispatcher>(renderer);
    EXPECT_EQ(6, renderer.getIndex());

    // clean up layer pointer, so we can safely destruct RenderNode
    *layerHandle = nullptr;
}

RENDERTHREAD_TEST(FrameBuilder, hwLayer_complex) {
    /* parentLayer { greyRect, saveLayer { childLayer { whiteRect } } } will play back as:
     * - startRepaintLayer(child), rect(grey), endLayer
     * - startTemporaryLayer, drawLayer(child), endLayer
     * - startRepaintLayer(parent), rect(white), drawLayer(saveLayer), endLayer
     * - startFrame, drawLayer(parent), endLayerb
     */
    class HwLayerComplexTestRenderer : public TestRendererBase {
    public:
        OffscreenBuffer* startTemporaryLayer(uint32_t width, uint32_t height) {
            EXPECT_EQ(3, mIndex++); // savelayer first
            return (OffscreenBuffer*)0xabcd;
        }
        void startRepaintLayer(OffscreenBuffer* offscreenBuffer, const Rect& repaintRect) override {
            int index = mIndex++;
            if (index == 0) {
                // starting inner layer
                EXPECT_EQ(100u, offscreenBuffer->viewportWidth);
                EXPECT_EQ(100u, offscreenBuffer->viewportHeight);
            } else if (index == 6) {
                // starting outer layer
                EXPECT_EQ(200u, offscreenBuffer->viewportWidth);
                EXPECT_EQ(200u, offscreenBuffer->viewportHeight);
            } else { ADD_FAILURE(); }
        }
        void onRectOp(const RectOp& op, const BakedOpState& state) override {
            int index = mIndex++;
            if (index == 1) {
                // inner layer's rect (white)
                EXPECT_EQ(SK_ColorWHITE, op.paint->getColor());
            } else if (index == 7) {
                // outer layer's rect (grey)
                EXPECT_EQ(SK_ColorDKGRAY, op.paint->getColor());
            } else { ADD_FAILURE(); }
        }
        void endLayer() override {
            int index = mIndex++;
            EXPECT_TRUE(index == 2 || index == 5 || index == 9);
        }
        void startFrame(uint32_t width, uint32_t height, const Rect& repaintRect) override {
            EXPECT_EQ(10, mIndex++);
        }
        void onLayerOp(const LayerOp& op, const BakedOpState& state) override {
            OffscreenBuffer* layer = *op.layerHandle;
            int index = mIndex++;
            if (index == 4) {
                EXPECT_EQ(100u, layer->viewportWidth);
                EXPECT_EQ(100u, layer->viewportHeight);
            } else if (index == 8) {
                EXPECT_EQ((OffscreenBuffer*)0xabcd, *op.layerHandle);
            } else if (index == 11) {
                EXPECT_EQ(200u, layer->viewportWidth);
                EXPECT_EQ(200u, layer->viewportHeight);
            } else { ADD_FAILURE(); }
        }
        void endFrame(const Rect& repaintRect) override {
            EXPECT_EQ(12, mIndex++);
        }
    };

    auto child = TestUtils::createNode(50, 50, 150, 150,
            [](RenderProperties& props, RecordingCanvas& canvas) {
        props.mutateLayerProperties().setType(LayerType::RenderLayer);
        SkPaint paint;
        paint.setColor(SK_ColorWHITE);
        canvas.drawRect(0, 0, 100, 100, paint);
    });
    OffscreenBuffer childLayer(renderThread.renderState(), Caches::getInstance(), 100, 100);
    *(child->getLayerHandle()) = &childLayer;

    RenderNode* childPtr = child.get();
    auto parent = TestUtils::createNode(0, 0, 200, 200,
            [childPtr](RenderProperties& props, RecordingCanvas& canvas) {
        props.mutateLayerProperties().setType(LayerType::RenderLayer);
        SkPaint paint;
        paint.setColor(SK_ColorDKGRAY);
        canvas.drawRect(0, 0, 200, 200, paint);

        canvas.saveLayerAlpha(50, 50, 150, 150, 128, SkCanvas::kClipToLayer_SaveFlag);
        canvas.drawRenderNode(childPtr);
        canvas.restore();
    });
    OffscreenBuffer parentLayer(renderThread.renderState(), Caches::getInstance(), 200, 200);
    *(parent->getLayerHandle()) = &parentLayer;

    auto syncedList = TestUtils::createSyncedNodeList(parent);

    LayerUpdateQueue layerUpdateQueue; // Note: enqueue damage post-sync, so bounds are valid
    layerUpdateQueue.enqueueLayerWithDamage(child.get(), Rect(100, 100));
    layerUpdateQueue.enqueueLayerWithDamage(parent.get(), Rect(200, 200));

    FrameBuilder frameBuilder(layerUpdateQueue, SkRect::MakeWH(200, 200), 200, 200,
            syncedList, sLightCenter);
    HwLayerComplexTestRenderer renderer;
    frameBuilder.replayBakedOps<TestDispatcher>(renderer);
    EXPECT_EQ(13, renderer.getIndex());

    // clean up layer pointers, so we can safely destruct RenderNodes
    *(child->getLayerHandle()) = nullptr;
    *(parent->getLayerHandle()) = nullptr;
}

static void drawOrderedRect(RecordingCanvas* canvas, uint8_t expectedDrawOrder) {
    SkPaint paint;
    paint.setColor(SkColorSetARGB(256, 0, 0, expectedDrawOrder)); // order put in blue channel
    canvas->drawRect(0, 0, 100, 100, paint);
}
static void drawOrderedNode(RecordingCanvas* canvas, uint8_t expectedDrawOrder, float z) {
    auto node = TestUtils::createNode(0, 0, 100, 100,
            [expectedDrawOrder](RenderProperties& props, RecordingCanvas& canvas) {
        drawOrderedRect(&canvas, expectedDrawOrder);
    });
    node->mutateStagingProperties().setTranslationZ(z);
    node->setPropertyFieldsDirty(RenderNode::TRANSLATION_Z);
    canvas->drawRenderNode(node.get()); // canvas takes reference/sole ownership
}
TEST(FrameBuilder, zReorder) {
    class ZReorderTestRenderer : public TestRendererBase {
    public:
        void onRectOp(const RectOp& op, const BakedOpState& state) override {
            int expectedOrder = SkColorGetB(op.paint->getColor()); // extract order from blue channel
            EXPECT_EQ(expectedOrder, mIndex++) << "An op was drawn out of order";
        }
    };

    auto parent = TestUtils::createNode(0, 0, 100, 100,
            [](RenderProperties& props, RecordingCanvas& canvas) {
        drawOrderedNode(&canvas, 0, 10.0f); // in reorder=false at this point, so played inorder
        drawOrderedRect(&canvas, 1);
        canvas.insertReorderBarrier(true);
        drawOrderedNode(&canvas, 6, 2.0f);
        drawOrderedRect(&canvas, 3);
        drawOrderedNode(&canvas, 4, 0.0f);
        drawOrderedRect(&canvas, 5);
        drawOrderedNode(&canvas, 2, -2.0f);
        drawOrderedNode(&canvas, 7, 2.0f);
        canvas.insertReorderBarrier(false);
        drawOrderedRect(&canvas, 8);
        drawOrderedNode(&canvas, 9, -10.0f); // in reorder=false at this point, so played inorder
    });
    FrameBuilder frameBuilder(sEmptyLayerUpdateQueue, SkRect::MakeWH(100, 100), 100, 100,
            TestUtils::createSyncedNodeList(parent), sLightCenter);
    ZReorderTestRenderer renderer;
    frameBuilder.replayBakedOps<TestDispatcher>(renderer);
    EXPECT_EQ(10, renderer.getIndex());
};

TEST(FrameBuilder, projectionReorder) {
    static const int scrollX = 5;
    static const int scrollY = 10;
    class ProjectionReorderTestRenderer : public TestRendererBase {
    public:
        void onRectOp(const RectOp& op, const BakedOpState& state) override {
            const int index = mIndex++;

            Matrix4 expectedMatrix;
            switch (index) {
            case 0:
                EXPECT_EQ(Rect(100, 100), op.unmappedBounds);
                EXPECT_EQ(SK_ColorWHITE, op.paint->getColor());
                expectedMatrix.loadIdentity();
                break;
            case 1:
                EXPECT_EQ(Rect(-10, -10, 60, 60), op.unmappedBounds);
                EXPECT_EQ(SK_ColorDKGRAY, op.paint->getColor());
                expectedMatrix.loadTranslate(50, 50, 0); // TODO: should scroll be respected here?
                break;
            case 2:
                EXPECT_EQ(Rect(100, 50), op.unmappedBounds);
                EXPECT_EQ(SK_ColorBLUE, op.paint->getColor());
                expectedMatrix.loadTranslate(-scrollX, 50 - scrollY, 0);
                break;
            default:
                ADD_FAILURE();
            }
            EXPECT_MATRIX_APPROX_EQ(expectedMatrix, state.computedState.transform);
        }
    };

    /**
     * Construct a tree of nodes, where the root (A) has a receiver background (B), and a child (C)
     * with a projecting child (P) of its own. P would normally draw between B and C's "background"
     * draw, but because it is projected backwards, it's drawn in between B and C.
     *
     * The parent is scrolled by scrollX/scrollY, but this does not affect the background
     * (which isn't affected by scroll).
     */
    auto receiverBackground = TestUtils::createNode(0, 0, 100, 100,
            [](RenderProperties& properties, RecordingCanvas& canvas) {
        properties.setProjectionReceiver(true);
        // scroll doesn't apply to background, so undone via translationX/Y
        // NOTE: translationX/Y only! no other transform properties may be set for a proj receiver!
        properties.setTranslationX(scrollX);
        properties.setTranslationY(scrollY);

        SkPaint paint;
        paint.setColor(SK_ColorWHITE);
        canvas.drawRect(0, 0, 100, 100, paint);
    });
    auto projectingRipple = TestUtils::createNode(50, 0, 100, 50,
            [](RenderProperties& properties, RecordingCanvas& canvas) {
        properties.setProjectBackwards(true);
        properties.setClipToBounds(false);
        SkPaint paint;
        paint.setColor(SK_ColorDKGRAY);
        canvas.drawRect(-10, -10, 60, 60, paint);
    });
    auto child = TestUtils::createNode(0, 50, 100, 100,
            [&projectingRipple](RenderProperties& properties, RecordingCanvas& canvas) {
        SkPaint paint;
        paint.setColor(SK_ColorBLUE);
        canvas.drawRect(0, 0, 100, 50, paint);
        canvas.drawRenderNode(projectingRipple.get());
    });
    auto parent = TestUtils::createNode(0, 0, 100, 100,
            [&receiverBackground, &child](RenderProperties& properties, RecordingCanvas& canvas) {
        canvas.save(SkCanvas::kMatrix_SaveFlag | SkCanvas::kClip_SaveFlag);
        canvas.translate(-scrollX, -scrollY); // Apply scroll (note: bg undoes this internally)
        canvas.drawRenderNode(receiverBackground.get());
        canvas.drawRenderNode(child.get());
        canvas.restore();
    });

    FrameBuilder frameBuilder(sEmptyLayerUpdateQueue, SkRect::MakeWH(100, 100), 100, 100,
            TestUtils::createSyncedNodeList(parent), sLightCenter);
    ProjectionReorderTestRenderer renderer;
    frameBuilder.replayBakedOps<TestDispatcher>(renderer);
    EXPECT_EQ(3, renderer.getIndex());
}

// creates a 100x100 shadow casting node with provided translationZ
static sp<RenderNode> createWhiteRectShadowCaster(float translationZ) {
    return TestUtils::createNode(0, 0, 100, 100,
            [translationZ](RenderProperties& properties, RecordingCanvas& canvas) {
        properties.setTranslationZ(translationZ);
        properties.mutableOutline().setRoundRect(0, 0, 100, 100, 0.0f, 1.0f);
        SkPaint paint;
        paint.setColor(SK_ColorWHITE);
        canvas.drawRect(0, 0, 100, 100, paint);
    });
}

TEST(FrameBuilder, shadow) {
    class ShadowTestRenderer : public TestRendererBase {
    public:
        void onShadowOp(const ShadowOp& op, const BakedOpState& state) override {
            EXPECT_EQ(0, mIndex++);
            EXPECT_FLOAT_EQ(1.0f, op.casterAlpha);
            EXPECT_TRUE(op.casterPath->isRect(nullptr));
            EXPECT_MATRIX_APPROX_EQ(Matrix4::identity(), op.shadowMatrixXY);

            Matrix4 expectedZ;
            expectedZ.loadTranslate(0, 0, 5);
            EXPECT_MATRIX_APPROX_EQ(expectedZ, op.shadowMatrixZ);
        }
        void onRectOp(const RectOp& op, const BakedOpState& state) override {
            EXPECT_EQ(1, mIndex++);
        }
    };

    auto parent = TestUtils::createNode(0, 0, 200, 200,
            [](RenderProperties& props, RecordingCanvas& canvas) {
        canvas.insertReorderBarrier(true);
        canvas.drawRenderNode(createWhiteRectShadowCaster(5.0f).get());
    });

    FrameBuilder frameBuilder(sEmptyLayerUpdateQueue, SkRect::MakeWH(200, 200), 200, 200,
            TestUtils::createSyncedNodeList(parent), sLightCenter);
    ShadowTestRenderer renderer;
    frameBuilder.replayBakedOps<TestDispatcher>(renderer);
    EXPECT_EQ(2, renderer.getIndex());
}

TEST(FrameBuilder, shadowSaveLayer) {
    class ShadowSaveLayerTestRenderer : public TestRendererBase {
    public:
        OffscreenBuffer* startTemporaryLayer(uint32_t width, uint32_t height) override {
            EXPECT_EQ(0, mIndex++);
            return nullptr;
        }
        void onShadowOp(const ShadowOp& op, const BakedOpState& state) override {
            EXPECT_EQ(1, mIndex++);
            EXPECT_FLOAT_EQ(50, op.lightCenter.x);
            EXPECT_FLOAT_EQ(40, op.lightCenter.y);
        }
        void onRectOp(const RectOp& op, const BakedOpState& state) override {
            EXPECT_EQ(2, mIndex++);
        }
        void endLayer() override {
            EXPECT_EQ(3, mIndex++);
        }
        void onLayerOp(const LayerOp& op, const BakedOpState& state) override {
            EXPECT_EQ(4, mIndex++);
        }
    };

    auto parent = TestUtils::createNode(0, 0, 200, 200,
            [](RenderProperties& props, RecordingCanvas& canvas) {
        // save/restore outside of reorderBarrier, so they don't get moved out of place
        canvas.translate(20, 10);
        int count = canvas.saveLayerAlpha(30, 50, 130, 150, 128, SkCanvas::kClipToLayer_SaveFlag);
        canvas.insertReorderBarrier(true);
        canvas.drawRenderNode(createWhiteRectShadowCaster(5.0f).get());
        canvas.insertReorderBarrier(false);
        canvas.restoreToCount(count);
    });

    FrameBuilder frameBuilder(sEmptyLayerUpdateQueue, SkRect::MakeWH(200, 200), 200, 200,
            TestUtils::createSyncedNodeList(parent), (Vector3) { 100, 100, 100 });
    ShadowSaveLayerTestRenderer renderer;
    frameBuilder.replayBakedOps<TestDispatcher>(renderer);
    EXPECT_EQ(5, renderer.getIndex());
}

RENDERTHREAD_TEST(FrameBuilder, shadowHwLayer) {
    class ShadowHwLayerTestRenderer : public TestRendererBase {
    public:
        void startRepaintLayer(OffscreenBuffer* offscreenBuffer, const Rect& repaintRect) override {
            EXPECT_EQ(0, mIndex++);
        }
        void onShadowOp(const ShadowOp& op, const BakedOpState& state) override {
            EXPECT_EQ(1, mIndex++);
            EXPECT_FLOAT_EQ(50, op.lightCenter.x);
            EXPECT_FLOAT_EQ(40, op.lightCenter.y);
        }
        void onRectOp(const RectOp& op, const BakedOpState& state) override {
            EXPECT_EQ(2, mIndex++);
        }
        void endLayer() override {
            EXPECT_EQ(3, mIndex++);
        }
        void onLayerOp(const LayerOp& op, const BakedOpState& state) override {
            EXPECT_EQ(4, mIndex++);
        }
    };

    auto parent = TestUtils::createNode(50, 60, 150, 160,
            [](RenderProperties& props, RecordingCanvas& canvas) {
        props.mutateLayerProperties().setType(LayerType::RenderLayer);
        canvas.insertReorderBarrier(true);
        canvas.save(SkCanvas::kMatrix_SaveFlag | SkCanvas::kClip_SaveFlag);
        canvas.translate(20, 10);
        canvas.drawRenderNode(createWhiteRectShadowCaster(5.0f).get());
        canvas.restore();
    });
    OffscreenBuffer** layerHandle = parent->getLayerHandle();

    // create RenderNode's layer here in same way prepareTree would, setting windowTransform
    OffscreenBuffer layer(renderThread.renderState(), Caches::getInstance(), 100, 100);
    Matrix4 windowTransform;
    windowTransform.loadTranslate(50, 60, 0); // total transform of layer's origin
    layer.setWindowTransform(windowTransform);
    *layerHandle = &layer;

    auto syncedList = TestUtils::createSyncedNodeList(parent);
    LayerUpdateQueue layerUpdateQueue; // Note: enqueue damage post-sync, so bounds are valid
    layerUpdateQueue.enqueueLayerWithDamage(parent.get(), Rect(100, 100));
    FrameBuilder frameBuilder(layerUpdateQueue, SkRect::MakeWH(200, 200), 200, 200,
            syncedList, (Vector3) { 100, 100, 100 });
    ShadowHwLayerTestRenderer renderer;
    frameBuilder.replayBakedOps<TestDispatcher>(renderer);
    EXPECT_EQ(5, renderer.getIndex());

    // clean up layer pointer, so we can safely destruct RenderNode
    *layerHandle = nullptr;
}

TEST(FrameBuilder, shadowLayering) {
    class ShadowLayeringTestRenderer : public TestRendererBase {
    public:
        void onShadowOp(const ShadowOp& op, const BakedOpState& state) override {
            int index = mIndex++;
            EXPECT_TRUE(index == 0 || index == 1);
        }
        void onRectOp(const RectOp& op, const BakedOpState& state) override {
            int index = mIndex++;
            EXPECT_TRUE(index == 2 || index == 3);
        }
    };
    auto parent = TestUtils::createNode(0, 0, 200, 200,
            [](RenderProperties& props, RecordingCanvas& canvas) {
        canvas.insertReorderBarrier(true);
        canvas.drawRenderNode(createWhiteRectShadowCaster(5.0f).get());
        canvas.drawRenderNode(createWhiteRectShadowCaster(5.0001f).get());
    });

    FrameBuilder frameBuilder(sEmptyLayerUpdateQueue, SkRect::MakeWH(200, 200), 200, 200,
            TestUtils::createSyncedNodeList(parent), sLightCenter);
    ShadowLayeringTestRenderer renderer;
    frameBuilder.replayBakedOps<TestDispatcher>(renderer);
    EXPECT_EQ(4, renderer.getIndex());
}

static void testProperty(std::function<void(RenderProperties&)> propSetupCallback,
        std::function<void(const RectOp&, const BakedOpState&)> opValidateCallback) {
    class PropertyTestRenderer : public TestRendererBase {
    public:
        PropertyTestRenderer(std::function<void(const RectOp&, const BakedOpState&)> callback)
                : mCallback(callback) {}
        void onRectOp(const RectOp& op, const BakedOpState& state) override {
            EXPECT_EQ(mIndex++, 0);
            mCallback(op, state);
        }
        std::function<void(const RectOp&, const BakedOpState&)> mCallback;
    };

    auto node = TestUtils::createNode(0, 0, 100, 100,
            [propSetupCallback](RenderProperties& props, RecordingCanvas& canvas) {
        propSetupCallback(props);
        SkPaint paint;
        paint.setColor(SK_ColorWHITE);
        canvas.drawRect(0, 0, 100, 100, paint);
    });

    FrameBuilder frameBuilder(sEmptyLayerUpdateQueue, SkRect::MakeWH(100, 100), 200, 200,
            TestUtils::createSyncedNodeList(node), sLightCenter);
    PropertyTestRenderer renderer(opValidateCallback);
    frameBuilder.replayBakedOps<TestDispatcher>(renderer);
    EXPECT_EQ(1, renderer.getIndex()) << "Should have seen one op";
}

TEST(FrameBuilder, renderPropOverlappingRenderingAlpha) {
    testProperty([](RenderProperties& properties) {
        properties.setAlpha(0.5f);
        properties.setHasOverlappingRendering(false);
    }, [](const RectOp& op, const BakedOpState& state) {
        EXPECT_EQ(0.5f, state.alpha) << "Alpha should be applied directly to op";
    });
}

TEST(FrameBuilder, renderPropClipping) {
    testProperty([](RenderProperties& properties) {
        properties.setClipToBounds(true);
        properties.setClipBounds(Rect(10, 20, 300, 400));
    }, [](const RectOp& op, const BakedOpState& state) {
        EXPECT_EQ(Rect(10, 20, 100, 100), state.computedState.clippedBounds)
                << "Clip rect should be intersection of node bounds and clip bounds";
    });
}

TEST(FrameBuilder, renderPropRevealClip) {
    testProperty([](RenderProperties& properties) {
        properties.mutableRevealClip().set(true, 50, 50, 25);
    }, [](const RectOp& op, const BakedOpState& state) {
        ASSERT_NE(nullptr, state.roundRectClipState);
        EXPECT_TRUE(state.roundRectClipState->highPriority);
        EXPECT_EQ(25, state.roundRectClipState->radius);
        EXPECT_EQ(Rect(50, 50, 50, 50), state.roundRectClipState->innerRect);
    });
}

TEST(FrameBuilder, renderPropOutlineClip) {
    testProperty([](RenderProperties& properties) {
        properties.mutableOutline().setShouldClip(true);
        properties.mutableOutline().setRoundRect(10, 20, 30, 40, 5.0f, 0.5f);
    }, [](const RectOp& op, const BakedOpState& state) {
        ASSERT_NE(nullptr, state.roundRectClipState);
        EXPECT_FALSE(state.roundRectClipState->highPriority);
        EXPECT_EQ(5, state.roundRectClipState->radius);
        EXPECT_EQ(Rect(15, 25, 25, 35), state.roundRectClipState->innerRect);
    });
}

TEST(FrameBuilder, renderPropTransform) {
    testProperty([](RenderProperties& properties) {
        properties.setLeftTopRightBottom(10, 10, 110, 110);

        SkMatrix staticMatrix = SkMatrix::MakeScale(1.2f, 1.2f);
        properties.setStaticMatrix(&staticMatrix);

        // ignored, since static overrides animation
        SkMatrix animationMatrix = SkMatrix::MakeTrans(15, 15);
        properties.setAnimationMatrix(&animationMatrix);

        properties.setTranslationX(10);
        properties.setTranslationY(20);
        properties.setScaleX(0.5f);
        properties.setScaleY(0.7f);
    }, [](const RectOp& op, const BakedOpState& state) {
        Matrix4 matrix;
        matrix.loadTranslate(10, 10, 0); // left, top
        matrix.scale(1.2f, 1.2f, 1); // static matrix
        // ignore animation matrix, since static overrides it

        // translation xy
        matrix.translate(10, 20);

        // scale xy (from default pivot - center)
        matrix.translate(50, 50);
        matrix.scale(0.5f, 0.7f, 1);
        matrix.translate(-50, -50);
        EXPECT_MATRIX_APPROX_EQ(matrix, state.computedState.transform)
                << "Op draw matrix must match expected combination of transformation properties";
    });
}

struct SaveLayerAlphaData {
    uint32_t layerWidth = 0;
    uint32_t layerHeight = 0;
    Rect rectClippedBounds;
    Matrix4 rectMatrix;
};
/**
 * Constructs a view to hit the temporary layer alpha property implementation:
 *     a) 0 < alpha < 1
 *     b) too big for layer (larger than maxTextureSize)
 *     c) overlapping rendering content
 * returning observed data about layer size and content clip/transform.
 *
 * Used to validate clipping behavior of temporary layer, where requested layer size is reduced
 * (for efficiency, and to fit in layer size constraints) based on parent clip.
 */
void testSaveLayerAlphaClip(SaveLayerAlphaData* outObservedData,
        std::function<void(RenderProperties&)> propSetupCallback) {
    class SaveLayerAlphaClipTestRenderer : public TestRendererBase {
    public:
        SaveLayerAlphaClipTestRenderer(SaveLayerAlphaData* outData)
                : mOutData(outData) {}

        OffscreenBuffer* startTemporaryLayer(uint32_t width, uint32_t height) override {
            EXPECT_EQ(0, mIndex++);
            mOutData->layerWidth = width;
            mOutData->layerHeight = height;
            return nullptr;
        }
        void onRectOp(const RectOp& op, const BakedOpState& state) override {
            EXPECT_EQ(1, mIndex++);

            mOutData->rectClippedBounds = state.computedState.clippedBounds;
            mOutData->rectMatrix = state.computedState.transform;
        }
        void endLayer() override {
            EXPECT_EQ(2, mIndex++);
        }
        void onLayerOp(const LayerOp& op, const BakedOpState& state) override {
            EXPECT_EQ(3, mIndex++);
        }
    private:
        SaveLayerAlphaData* mOutData;
    };

    ASSERT_GT(10000, DeviceInfo::get()->maxTextureSize())
            << "Node must be bigger than max texture size to exercise saveLayer codepath";
    auto node = TestUtils::createNode(0, 0, 10000, 10000,
            [&propSetupCallback](RenderProperties& properties, RecordingCanvas& canvas) {
        properties.setHasOverlappingRendering(true);
        properties.setAlpha(0.5f); // force saveLayer, since too big for HW layer
        // apply other properties
        propSetupCallback(properties);

        SkPaint paint;
        paint.setColor(SK_ColorWHITE);
        canvas.drawRect(0, 0, 10000, 10000, paint);
    });
    auto nodes = TestUtils::createSyncedNodeList(node); // sync before querying height

    FrameBuilder frameBuilder(sEmptyLayerUpdateQueue, SkRect::MakeWH(200, 200), 200, 200, nodes, sLightCenter);
    SaveLayerAlphaClipTestRenderer renderer(outObservedData);
    frameBuilder.replayBakedOps<TestDispatcher>(renderer);

    // assert, since output won't be valid if we haven't seen a save layer triggered
    ASSERT_EQ(4, renderer.getIndex()) << "Test must trigger saveLayer alpha behavior.";
}

TEST(FrameBuilder, renderPropSaveLayerAlphaClipBig) {
    SaveLayerAlphaData observedData;
    testSaveLayerAlphaClip(&observedData, [](RenderProperties& properties) {
        properties.setTranslationX(10); // offset rendering content
        properties.setTranslationY(-2000); // offset rendering content
    });
    EXPECT_EQ(190u, observedData.layerWidth);
    EXPECT_EQ(200u, observedData.layerHeight);
    EXPECT_EQ(Rect(190, 200), observedData.rectClippedBounds)
            << "expect content to be clipped to screen area";
    Matrix4 expected;
    expected.loadTranslate(0, -2000, 0);
    EXPECT_MATRIX_APPROX_EQ(expected, observedData.rectMatrix)
            << "expect content to be translated as part of being clipped";
}

TEST(FrameBuilder, renderPropSaveLayerAlphaRotate) {
    SaveLayerAlphaData observedData;
    testSaveLayerAlphaClip(&observedData, [](RenderProperties& properties) {
        // Translate and rotate the view so that the only visible part is the top left corner of
        // the view. It will form an isosceles right triangle with a long side length of 200 at the
        // bottom of the viewport.
        properties.setTranslationX(100);
        properties.setTranslationY(100);
        properties.setPivotX(0);
        properties.setPivotY(0);
        properties.setRotation(45);
    });
    // ceil(sqrt(2) / 2 * 200) = 142
    EXPECT_EQ(142u, observedData.layerWidth);
    EXPECT_EQ(142u, observedData.layerHeight);
    EXPECT_EQ(Rect(142, 142), observedData.rectClippedBounds);
    EXPECT_MATRIX_APPROX_EQ(Matrix4::identity(), observedData.rectMatrix);
}

TEST(FrameBuilder, renderPropSaveLayerAlphaScale) {
    SaveLayerAlphaData observedData;
    testSaveLayerAlphaClip(&observedData, [](RenderProperties& properties) {
        properties.setPivotX(0);
        properties.setPivotY(0);
        properties.setScaleX(2);
        properties.setScaleY(0.5f);
    });
    EXPECT_EQ(100u, observedData.layerWidth);
    EXPECT_EQ(400u, observedData.layerHeight);
    EXPECT_EQ(Rect(100, 400), observedData.rectClippedBounds);
    EXPECT_MATRIX_APPROX_EQ(Matrix4::identity(), observedData.rectMatrix);
}

} // namespace uirenderer
} // namespace android
