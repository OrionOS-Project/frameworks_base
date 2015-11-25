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

#include <RecordedOp.h>
#include <RecordingCanvas.h>
#include <utils/TestUtils.h>

namespace android {
namespace uirenderer {

static void playbackOps(const DisplayList& displayList,
        std::function<void(const RecordedOp&)> opReceiver) {
    for (auto& chunk : displayList.getChunks()) {
        for (size_t opIndex = chunk.beginOpIndex; opIndex < chunk.endOpIndex; opIndex++) {
            RecordedOp* op = displayList.getOps()[opIndex];
            opReceiver(*op);
        }
    }
}

TEST(RecordingCanvas, emptyPlayback) {
    auto dl = TestUtils::createDisplayList<RecordingCanvas>(100, 200, [](RecordingCanvas& canvas) {
        canvas.save(SkCanvas::kMatrix_SaveFlag | SkCanvas::kClip_SaveFlag);
        canvas.restore();
    });
    playbackOps(*dl, [](const RecordedOp& op) { ADD_FAILURE(); });
}

TEST(RecordingCanvas, drawLines) {
    auto dl = TestUtils::createDisplayList<RecordingCanvas>(100, 200, [](RecordingCanvas& canvas) {
        SkPaint paint;
        paint.setStrokeWidth(20);
        float points[] = { 0, 0, 20, 10, 30, 40, 90 }; // NB: only 1 valid line
        canvas.drawLines(&points[0], 7, paint);
    });

    ASSERT_EQ(1u, dl->getOps().size()) << "Must be exactly one op";
    auto op = dl->getOps()[0];
    ASSERT_EQ(RecordedOpId::LinesOp, op->opId);
    EXPECT_EQ(4, ((LinesOp*)op)->floatCount)
            << "float count must be rounded down to closest multiple of 4";
    EXPECT_EQ(Rect(-10, -10, 30, 20), op->unmappedBounds)
            << "unmapped bounds must be size of line, outset by 1/2 stroke width";
}

TEST(RecordingCanvas, drawRect) {
    auto dl = TestUtils::createDisplayList<RecordingCanvas>(100, 200, [](RecordingCanvas& canvas) {
        canvas.drawRect(10, 20, 90, 180, SkPaint());
    });

    ASSERT_EQ(1u, dl->getOps().size()) << "Must be exactly one op";
    auto op = *(dl->getOps()[0]);
    ASSERT_EQ(RecordedOpId::RectOp, op.opId);
    EXPECT_EQ(Rect(0, 0, 100, 200), op.localClipRect);
    EXPECT_EQ(Rect(10, 20, 90, 180), op.unmappedBounds);
}

TEST(RecordingCanvas, drawText) {
    auto dl = TestUtils::createDisplayList<RecordingCanvas>(200, 200, [](RecordingCanvas& canvas) {
        SkPaint paint;
        paint.setAntiAlias(true);
        paint.setTextSize(20);
        paint.setTextEncoding(SkPaint::kGlyphID_TextEncoding);
        TestUtils::drawTextToCanvas(&canvas, "test text", paint, 25, 25);
    });

    int count = 0;
    playbackOps(*dl, [&count](const RecordedOp& op) {
        count++;
        ASSERT_EQ(RecordedOpId::TextOp, op.opId);
        EXPECT_EQ(Rect(0, 0, 200, 200), op.localClipRect);
        EXPECT_TRUE(op.localMatrix.isIdentity());
        EXPECT_TRUE(op.unmappedBounds.contains(25, 15, 50, 25))
                << "Op expected to be 25+ pixels wide, 10+ pixels tall";
    });
    ASSERT_EQ(1, count);
}

TEST(RecordingCanvas, drawText_strikeThruAndUnderline) {
    auto dl = TestUtils::createDisplayList<RecordingCanvas>(200, 200, [](RecordingCanvas& canvas) {
        SkPaint paint;
        paint.setAntiAlias(true);
        paint.setTextSize(20);
        paint.setTextEncoding(SkPaint::kGlyphID_TextEncoding);
        for (int i = 0; i < 2; i++) {
            for (int j = 0; j < 2; j++) {
                paint.setUnderlineText(i != 0);
                paint.setStrikeThruText(j != 0);
                TestUtils::drawTextToCanvas(&canvas, "test text", paint, 25, 25);
            }
        }
    });

    auto ops = dl->getOps();
    ASSERT_EQ(8u, ops.size());

    int index = 0;
    EXPECT_EQ(RecordedOpId::TextOp, ops[index++]->opId); // no underline or strikethrough

    EXPECT_EQ(RecordedOpId::TextOp, ops[index++]->opId);
    EXPECT_EQ(RecordedOpId::RectOp, ops[index++]->opId); // strikethrough only

    EXPECT_EQ(RecordedOpId::TextOp, ops[index++]->opId);
    EXPECT_EQ(RecordedOpId::RectOp, ops[index++]->opId); // underline only

    EXPECT_EQ(RecordedOpId::TextOp, ops[index++]->opId);
    EXPECT_EQ(RecordedOpId::RectOp, ops[index++]->opId); // underline
    EXPECT_EQ(RecordedOpId::RectOp, ops[index++]->opId); // strikethrough
}

TEST(RecordingCanvas, drawText_forceAlignLeft) {
    auto dl = TestUtils::createDisplayList<RecordingCanvas>(200, 200, [](RecordingCanvas& canvas) {
        SkPaint paint;
        paint.setAntiAlias(true);
        paint.setTextSize(20);
        paint.setTextEncoding(SkPaint::kGlyphID_TextEncoding);
        paint.setTextAlign(SkPaint::kLeft_Align);
        TestUtils::drawTextToCanvas(&canvas, "test text", paint, 25, 25);
        paint.setTextAlign(SkPaint::kCenter_Align);
        TestUtils::drawTextToCanvas(&canvas, "test text", paint, 25, 25);
        paint.setTextAlign(SkPaint::kRight_Align);
        TestUtils::drawTextToCanvas(&canvas, "test text", paint, 25, 25);
    });

    int count = 0;
    float lastX = FLT_MAX;
    playbackOps(*dl, [&count, &lastX](const RecordedOp& op) {
        count++;
        ASSERT_EQ(RecordedOpId::TextOp, op.opId);
        EXPECT_EQ(SkPaint::kLeft_Align, op.paint->getTextAlign())
                << "recorded drawText commands must force kLeft_Align on their paint";

        // verify TestUtils alignment offsetting (TODO: move asserts to Canvas base class)
        EXPECT_GT(lastX, ((const TextOp&)op).x)
                << "x coordinate should reduce across each of the draw commands, from alignment";
        lastX = ((const TextOp&)op).x;
    });
    ASSERT_EQ(3, count);
}

TEST(RecordingCanvas, backgroundAndImage) {
    auto dl = TestUtils::createDisplayList<RecordingCanvas>(100, 200, [](RecordingCanvas& canvas) {
        SkBitmap bitmap;
        bitmap.setInfo(SkImageInfo::MakeUnknown(25, 25));
        SkPaint paint;
        paint.setColor(SK_ColorBLUE);

        canvas.save(SkCanvas::kMatrix_SaveFlag | SkCanvas::kClip_SaveFlag);
        {
            // a background!
            canvas.save(SkCanvas::kMatrix_SaveFlag | SkCanvas::kClip_SaveFlag);
            canvas.drawRect(0, 0, 100, 200, paint);
            canvas.restore();
        }
        {
            // an image!
            canvas.save(SkCanvas::kMatrix_SaveFlag | SkCanvas::kClip_SaveFlag);
            canvas.translate(25, 25);
            canvas.scale(2, 2);
            canvas.drawBitmap(bitmap, 0, 0, nullptr);
            canvas.restore();
        }
        canvas.restore();
    });

    int count = 0;
    playbackOps(*dl, [&count](const RecordedOp& op) {
        if (count == 0) {
            ASSERT_EQ(RecordedOpId::RectOp, op.opId);
            ASSERT_NE(nullptr, op.paint);
            EXPECT_EQ(SK_ColorBLUE, op.paint->getColor());
            EXPECT_EQ(Rect(0, 0, 100, 200), op.unmappedBounds);
            EXPECT_EQ(Rect(0, 0, 100, 200), op.localClipRect);

            Matrix4 expectedMatrix;
            expectedMatrix.loadIdentity();
            EXPECT_MATRIX_APPROX_EQ(expectedMatrix, op.localMatrix);
        } else {
            ASSERT_EQ(RecordedOpId::BitmapOp, op.opId);
            EXPECT_EQ(nullptr, op.paint);
            EXPECT_EQ(Rect(0, 0, 25, 25), op.unmappedBounds);
            EXPECT_EQ(Rect(0, 0, 100, 200), op.localClipRect);

            Matrix4 expectedMatrix;
            expectedMatrix.loadTranslate(25, 25, 0);
            expectedMatrix.scale(2, 2, 1);
            EXPECT_MATRIX_APPROX_EQ(expectedMatrix, op.localMatrix);
        }
        count++;
    });
    ASSERT_EQ(2, count);
}

TEST(RecordingCanvas, saveLayer_simple) {
    auto dl = TestUtils::createDisplayList<RecordingCanvas>(200, 200, [](RecordingCanvas& canvas) {
        canvas.saveLayerAlpha(10, 20, 190, 180, 128, SkCanvas::kARGB_ClipLayer_SaveFlag);
        canvas.drawRect(10, 20, 190, 180, SkPaint());
        canvas.restore();
    });
    int count = 0;
    playbackOps(*dl, [&count](const RecordedOp& op) {
        Matrix4 expectedMatrix;
        switch(count++) {
        case 0:
            EXPECT_EQ(RecordedOpId::BeginLayerOp, op.opId);
            EXPECT_EQ(Rect(10, 20, 190, 180), op.unmappedBounds);
            EXPECT_EQ(Rect(0, 0, 200, 200), op.localClipRect);
            EXPECT_TRUE(op.localMatrix.isIdentity());
            break;
        case 1:
            EXPECT_EQ(RecordedOpId::RectOp, op.opId);
            EXPECT_EQ(Rect(0, 0, 180, 160), op.localClipRect);
            EXPECT_EQ(Rect(10, 20, 190, 180), op.unmappedBounds);
            expectedMatrix.loadTranslate(-10, -20, 0);
            EXPECT_MATRIX_APPROX_EQ(expectedMatrix, op.localMatrix);
            break;
        case 2:
            EXPECT_EQ(RecordedOpId::EndLayerOp, op.opId);
            // Don't bother asserting recording state data - it's not used
            break;
        default:
            ADD_FAILURE();
        }
    });
    EXPECT_EQ(3, count);
}

TEST(RecordingCanvas, saveLayer_viewportCrop) {
    auto dl = TestUtils::createDisplayList<RecordingCanvas>(200, 200, [](RecordingCanvas& canvas) {
        // shouldn't matter, since saveLayer will clip to its bounds
        canvas.clipRect(-1000, -1000, 1000, 1000, SkRegion::kReplace_Op);

        canvas.saveLayerAlpha(100, 100, 300, 300, 128, SkCanvas::kARGB_ClipLayer_SaveFlag);
        canvas.drawRect(0, 0, 400, 400, SkPaint());
        canvas.restore();
    });
    int count = 0;
    playbackOps(*dl, [&count](const RecordedOp& op) {
        if (count++ == 1) {
            Matrix4 expectedMatrix;
            EXPECT_EQ(RecordedOpId::RectOp, op.opId);
            EXPECT_EQ(Rect(0, 0, 100, 100), op.localClipRect) << "Recorded clip rect should be"
                    " intersection of viewport and saveLayer bounds, in layer space";
            EXPECT_EQ(Rect(0, 0, 400, 400), op.unmappedBounds);
            expectedMatrix.loadTranslate(-100, -100, 0);
            EXPECT_MATRIX_APPROX_EQ(expectedMatrix, op.localMatrix);
        }
    });
    EXPECT_EQ(3, count);
}

TEST(RecordingCanvas, saveLayer_rotateUnclipped) {
    auto dl = TestUtils::createDisplayList<RecordingCanvas>(200, 200, [](RecordingCanvas& canvas) {
        canvas.save(SkCanvas::kMatrix_SaveFlag | SkCanvas::kClip_SaveFlag);
        canvas.translate(100, 100);
        canvas.rotate(45);
        canvas.translate(-50, -50);

        canvas.saveLayerAlpha(0, 0, 100, 100, 128, SkCanvas::kARGB_ClipLayer_SaveFlag);
        canvas.drawRect(0, 0, 100, 100, SkPaint());
        canvas.restore();

        canvas.restore();
    });
    int count = 0;
    playbackOps(*dl, [&count](const RecordedOp& op) {
        if (count++ == 1) {
            EXPECT_EQ(RecordedOpId::RectOp, op.opId);
            EXPECT_EQ(Rect(0, 0, 100, 100), op.localClipRect);
            EXPECT_EQ(Rect(0, 0, 100, 100), op.unmappedBounds);
            EXPECT_MATRIX_APPROX_EQ(Matrix4::identity(), op.localMatrix)
                    << "Recorded op shouldn't see any canvas transform before the saveLayer";
        }
    });
    EXPECT_EQ(3, count);
}

TEST(RecordingCanvas, saveLayer_rotateClipped) {
    auto dl = TestUtils::createDisplayList<RecordingCanvas>(200, 200, [](RecordingCanvas& canvas) {
        canvas.save(SkCanvas::kMatrix_SaveFlag | SkCanvas::kClip_SaveFlag);
        canvas.translate(100, 100);
        canvas.rotate(45);
        canvas.translate(-200, -200);

        // area of saveLayer will be clipped to parent viewport, so we ask for 400x400...
        canvas.saveLayerAlpha(0, 0, 400, 400, 128, SkCanvas::kARGB_ClipLayer_SaveFlag);
        canvas.drawRect(0, 0, 400, 400, SkPaint());
        canvas.restore();

        canvas.restore();
    });
    int count = 0;
    playbackOps(*dl, [&count](const RecordedOp& op) {
        if (count++ == 1) {
            Matrix4 expectedMatrix;
            EXPECT_EQ(RecordedOpId::RectOp, op.opId);

            // ...and get about 58.6, 58.6, 341.4 341.4, because the bounds are clipped by
            // the parent 200x200 viewport, but prior to rotation
            EXPECT_RECT_APPROX_EQ(Rect(58.57864, 58.57864, 341.42136, 341.42136), op.localClipRect);
            EXPECT_EQ(Rect(0, 0, 400, 400), op.unmappedBounds);
            expectedMatrix.loadIdentity();
            EXPECT_MATRIX_APPROX_EQ(expectedMatrix, op.localMatrix);
        }
    });
    EXPECT_EQ(3, count);
}

TEST(RecordingCanvas, drawRenderNode_projection) {
    sp<RenderNode> background = TestUtils::createNode(50, 50, 150, 150,
            [](RenderProperties& props, RecordingCanvas& canvas) {
        SkPaint paint;
        paint.setColor(SK_ColorWHITE);
        canvas.drawRect(0, 0, 100, 100, paint);
    });
    {
        background->mutateStagingProperties().setProjectionReceiver(false);

        // NO RECEIVER PRESENT
        auto dl = TestUtils::createDisplayList<RecordingCanvas>(200, 200,
                    [&background](RecordingCanvas& canvas) {
            canvas.drawRect(0, 0, 100, 100, SkPaint());
            canvas.drawRenderNode(background.get());
            canvas.drawRect(0, 0, 100, 100, SkPaint());
        });
        EXPECT_EQ(-1, dl->projectionReceiveIndex)
                << "no projection receiver should have been observed";
    }
    {
        background->mutateStagingProperties().setProjectionReceiver(true);

        // RECEIVER PRESENT
        auto dl = TestUtils::createDisplayList<RecordingCanvas>(200, 200,
                    [&background](RecordingCanvas& canvas) {
            canvas.drawRect(0, 0, 100, 100, SkPaint());
            canvas.drawRenderNode(background.get());
            canvas.drawRect(0, 0, 100, 100, SkPaint());
        });

        ASSERT_EQ(3u, dl->getOps().size()) << "Must be three ops";
        auto op = dl->getOps()[1];
        EXPECT_EQ(RecordedOpId::RenderNodeOp, op->opId);
        EXPECT_EQ(1, dl->projectionReceiveIndex)
                << "correct projection receiver not identified";

        // verify the behavior works even though projection receiver hasn't been sync'd yet
        EXPECT_TRUE(background->stagingProperties().isProjectionReceiver());
        EXPECT_FALSE(background->properties().isProjectionReceiver());
    }
}

TEST(RecordingCanvas, insertReorderBarrier) {
    auto dl = TestUtils::createDisplayList<RecordingCanvas>(200, 200, [](RecordingCanvas& canvas) {
        canvas.drawRect(0, 0, 400, 400, SkPaint());
        canvas.insertReorderBarrier(true);
        canvas.insertReorderBarrier(false);
        canvas.insertReorderBarrier(false);
        canvas.insertReorderBarrier(true);
        canvas.drawRect(0, 0, 400, 400, SkPaint());
        canvas.insertReorderBarrier(false);
    });

    auto chunks = dl->getChunks();
    EXPECT_EQ(0u, chunks[0].beginOpIndex);
    EXPECT_EQ(1u, chunks[0].endOpIndex);
    EXPECT_FALSE(chunks[0].reorderChildren);

    EXPECT_EQ(1u, chunks[1].beginOpIndex);
    EXPECT_EQ(2u, chunks[1].endOpIndex);
    EXPECT_TRUE(chunks[1].reorderChildren);
}

TEST(RecordingCanvas, refPaint) {
    SkPaint paint;
    paint.setAntiAlias(true);
    paint.setTextSize(20);
    paint.setTextAlign(SkPaint::kLeft_Align);
    paint.setTextEncoding(SkPaint::kGlyphID_TextEncoding);

    auto dl = TestUtils::createDisplayList<RecordingCanvas>(200, 200, [&paint](RecordingCanvas& canvas) {
        paint.setColor(SK_ColorBLUE);
        // first three should use same paint
        canvas.drawRect(0, 0, 200, 10, paint);
        SkPaint paintCopy(paint);
        canvas.drawRect(0, 10, 200, 20, paintCopy);
        TestUtils::drawTextToCanvas(&canvas, "helloworld", paint, 50, 25);

        // only here do we use different paint ptr
        paint.setColor(SK_ColorRED);
        canvas.drawRect(0, 20, 200, 30, paint);
    });
    auto ops = dl->getOps();
    ASSERT_EQ(4u, ops.size());

    // first three are the same
    EXPECT_NE(nullptr, ops[0]->paint);
    EXPECT_NE(&paint, ops[0]->paint);
    EXPECT_EQ(ops[0]->paint, ops[1]->paint);
    EXPECT_EQ(ops[0]->paint, ops[2]->paint);

    // last is different, but still copied / non-null
    EXPECT_NE(nullptr, ops[3]->paint);
    EXPECT_NE(ops[0]->paint, ops[3]->paint);
    EXPECT_NE(&paint, ops[3]->paint);
}

} // namespace uirenderer
} // namespace android
