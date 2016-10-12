/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.util.proto;

import android.annotation.TestApi;
import android.util.Log;

import java.io.UnsupportedEncodingException;
import java.util.List;

/**
 * Class to write to a protobuf stream.
 *
 * Each write method takes an ID code from the protoc generated classes
 * and the value to write.  To make a nested object, call startObject
 * and then endObject when you are done.
 *
 * The ID codes have type information embedded into them, so if you call
 * the incorrect function you will get an IllegalArgumentException.
 *
 * To retrieve the encoded protobuf stream, call getBytes().
 *
 * TODO: Add a constructor that takes an OutputStream and write to that
 * stream as the top-level objects are finished.
 *
 * @hide
 */

/* IMPLEMENTATION NOTES
 *
 * Because protobuf has inner values, and they are length prefixed, and
 * those sizes themselves are stored with a variable length encoding, it
 * is impossible to know how big an object will be in a single pass.
 *
 * The traditional way is to copy the in-memory representation of an object
 * into the generated proto Message objects, do a traversal of those to
 * cache the size, and then write the size-prefixed buffers.
 *
 * We are trying to avoid too much generated code here, but this class still
 * needs to have a somewhat sane API.  We can't have the multiple passes be
 * done by the calling code.  In addition, we want to avoid the memory high
 * water mark of duplicating all of the values into the traditional in-memory
 * Message objects. We need to find another way.
 *
 * So what we do here is to let the calling code write the data into a
 * byte[] (actually a collection of them wrapped in the EncodedBuffer) class,
 * but not do the varint encoding of the sub-message sizes.  Then, we do a
 * recursive traversal of the buffer itself, calculating the sizes (which are
 * then knowable, although still not the actual sizes in the buffer because of
 * possible further nesting).  Then we do a third pass, compacting the
 * buffer and varint encoding the sizes.
 *
 * This gets us a relatively small number number of fixed-size allocations,
 * which is less likely to cause memory fragmentation or churn the GC, and
 * the same number of data copies as would have gotten with setting it
 * field-by-field in generated code, and no code bloat from generated code.
 * The final data copy is also done with System.arraycopy, which will be
 * more efficient, in general, than doing the individual fields twice (as in
 * the traditional way).
 *
 * To accomplish the multiple passes, whenever we write a
 * WIRE_TYPE_LENGTH_DELIMITED field, we write the size occupied in our
 * buffer as a fixed 32 bit int (called childRawSize), not variable length
 * one. We reserve another 32 bit slot for the computed size (called
 * childEncodedSize).  If we know the size up front, as we do for strings
 * and byte[], then we also put that into childEncodedSize, if we don't, we
 * write the negative of childRawSize, as a sentiel that we need to
 * compute it during the second pass and recursively compact it during the
 * third pass.
 *
 * Unsgigned size varints can be up to five bytes long, but we reserve eight
 * bytes for overhead, so we know that when we compact the buffer, there
 * will always be space for the encoded varint.
 *
 * When we can figure out the size ahead of time, we do, in order
 * to save overhead with recalculating it, and with the later arraycopy.
 *
 * During the period between when the caller has called startObject, but
 * not yet called endObject, we maintain a linked list of the tokens
 * returned by startObject, stored in those 8 bytes of size storage space.
 * We use that linked list of tokens to ensure that the caller has
 * correctly matched pairs of startObject and endObject calls, and issue
 * errors if they are not matched.
 */
@TestApi
public final class ProtoOutputStream {
    public static final String TAG = "ProtoOutputStream";

    public static final int FIELD_ID_SHIFT = 3;
    public static final int WIRE_TYPE_MASK = (1<<FIELD_ID_SHIFT)-1;
    public static final int FIELD_ID_MASK = ~WIRE_TYPE_MASK;

    public static final int WIRE_TYPE_VARINT = 0;
    public static final int WIRE_TYPE_FIXED64 = 1;
    public static final int WIRE_TYPE_LENGTH_DELIMITED = 2;
    public static final int WIRE_TYPE_START_GROUP = 3;
    public static final int WIRE_TYPE_END_GROUP = 4;
    public static final int WIRE_TYPE_FIXED32 = 5;

    /**
     * Position of the field type in a (long) fieldId.
     */
    public static final int FIELD_TYPE_SHIFT = 32;

    /**
     * Mask for the field types stored in a fieldId.  Leaves a whole
     * byte for future expansion, even though there are currently only 17 types.
     */
    public static final long FIELD_TYPE_MASK = 0x0ffL << FIELD_TYPE_SHIFT;

    public static final long FIELD_TYPE_UNKNOWN = 0;

    public static final long FIELD_TYPE_DOUBLE = 1L << FIELD_TYPE_SHIFT;
    public static final long FIELD_TYPE_FLOAT = 2L << FIELD_TYPE_SHIFT;
    public static final long FIELD_TYPE_INT32 = 3L << FIELD_TYPE_SHIFT;
    public static final long FIELD_TYPE_INT64 = 4L << FIELD_TYPE_SHIFT;
    public static final long FIELD_TYPE_UINT32 = 5L << FIELD_TYPE_SHIFT;
    public static final long FIELD_TYPE_UINT64 = 6L << FIELD_TYPE_SHIFT;
    public static final long FIELD_TYPE_SINT32 = 7L << FIELD_TYPE_SHIFT;
    public static final long FIELD_TYPE_SINT64 = 8L << FIELD_TYPE_SHIFT;
    public static final long FIELD_TYPE_FIXED32 = 9L << FIELD_TYPE_SHIFT;
    public static final long FIELD_TYPE_FIXED64 = 10L << FIELD_TYPE_SHIFT;
    public static final long FIELD_TYPE_SFIXED32 = 11L << FIELD_TYPE_SHIFT;
    public static final long FIELD_TYPE_SFIXED64 = 12L << FIELD_TYPE_SHIFT;
    public static final long FIELD_TYPE_BOOL = 13L << FIELD_TYPE_SHIFT;
    public static final long FIELD_TYPE_STRING = 14L << FIELD_TYPE_SHIFT;
    public static final long FIELD_TYPE_BYTES = 15L << FIELD_TYPE_SHIFT;
    public static final long FIELD_TYPE_ENUM = 16L << FIELD_TYPE_SHIFT;
    public static final long FIELD_TYPE_OBJECT = 17L << FIELD_TYPE_SHIFT;

    private static final String[] FIELD_TYPE_NAMES = new String[] {
        "Double",
        "Float",
        "Int32",
        "Int64",
        "UInt32",
        "UInt64",
        "SInt32",
        "SInt64",
        "Fixed32",
        "Fixed64",
        "SFixed32",
        "SFixed64",
        "Bool",
        "String",
        "Bytes",
        "Enum",
        "Object",
    };

    //
    // FieldId flags for whether the field is single, repeated or packed.
    //
    public static final int FIELD_COUNT_SHIFT = 40;
    public static final long FIELD_COUNT_MASK = 0x0fL << FIELD_COUNT_SHIFT;

    public static final long FIELD_COUNT_UNKNOWN = 0;
    public static final long FIELD_COUNT_SINGLE = 1L << FIELD_COUNT_SHIFT;
    public static final long FIELD_COUNT_REPEATED = 2L << FIELD_COUNT_SHIFT;
    public static final long FIELD_COUNT_PACKED = 5L << FIELD_COUNT_SHIFT;

    /**
     * Our buffer.
     */
    private EncodedBuffer mBuffer;

    /**
     * Current nesting depth of startObject calls.
     */
    private int mDepth;

    /**
     * An ID given to objects and returned in the token from startObject
     * and stored in the buffer until endObject is called, where the two
     * are checked.  Starts at -1 and becomes more negative, so the values
     * aren't likely to alias with the size it will be overwritten with,
     * which tend to be small, and we will be more likely to catch when
     * the caller of endObject uses a stale token that they didn't intend
     * to (e.g. copy and paste error).
     */
    private int mNextObjectId = -1;

    /**
     * The object token we are expecting in endObject.  If another call to
     * startObject happens, this is written to that location, which gives
     * us a stack, stored in the space for the as-yet unused size fields.
     */
    private long mExpectedObjectToken;

    /**
     * Index in mBuffer that we should start copying from on the next
     * pass of compaction.
     */
    private int mCopyBegin;

    /**
     * Whether we've already compacted
     */
    private boolean mCompacted;

    /**
     * Construct a ProtoOutputStream with the default chunk size.
     */
    public ProtoOutputStream() {
        this(0);
    }

    /**
     * Construct a ProtoOutputStream with the given chunk size.
     */
    public ProtoOutputStream(int chunkSize) {
        mBuffer = new EncodedBuffer(chunkSize);
    }

    //
    // proto3 type: double
    // java type: double
    // encoding: fixed64
    // wire type: WIRE_TYPE_FIXED64
    //

    /**
     * Write a single proto "double" type field value.
     */
    public void writeDouble(long fieldId, double val) {
        assertNotCompacted();
        final int id = checkFieldId(fieldId, FIELD_COUNT_SINGLE | FIELD_TYPE_DOUBLE);

        if (val != 0) {
            writeTag(id, WIRE_TYPE_FIXED64);
            mBuffer.writeRawFixed64(Double.doubleToLongBits(val));
        }
    }

    /**
     * Write a single repeated proto "double" type field value.
     */
    public void writeRepeatedDouble(long fieldId, double val) {
        assertNotCompacted();
        final int id = checkFieldId(fieldId, FIELD_COUNT_REPEATED | FIELD_TYPE_DOUBLE);

        writeTag(id, WIRE_TYPE_FIXED64);
        mBuffer.writeRawFixed64(Double.doubleToLongBits(val));
    }

    /**
     * Write a list of packed proto "double" type field values.
     */
    public void writePackedDouble(long fieldId, double[] val) {
        assertNotCompacted();
        final int id = checkFieldId(fieldId, FIELD_COUNT_PACKED | FIELD_TYPE_DOUBLE);

        final int N = val != null ? val.length : 0;
        if (N > 0) {
            writeKnownLengthHeader(id, N * 8);
            for (int i=0; i<N; i++) {
                mBuffer.writeRawFixed64(Double.doubleToLongBits(val[i]));
            }
        }
    }

    //
    // proto3 type: float
    // java type: float
    // encoding: fixed32
    // wire type: WIRE_TYPE_FIXED32
    //

    /**
     * Write a single proto "float" type field value.
     */
    public void writeFloat(long fieldId, float val) {
        assertNotCompacted();
        final int id = checkFieldId(fieldId, FIELD_COUNT_SINGLE | FIELD_TYPE_FLOAT);

        if (val != 0) {
            writeTag(id, WIRE_TYPE_FIXED32);
            mBuffer.writeRawFixed32(Float.floatToIntBits(val));
        }
    }

    /**
     * Write a single repeated proto "float" type field value.
     */
    public void writeRepeatedFloat(long fieldId, float val) {
        assertNotCompacted();
        final int id = checkFieldId(fieldId, FIELD_COUNT_REPEATED | FIELD_TYPE_FLOAT);

        writeTag(id, WIRE_TYPE_FIXED32);
        mBuffer.writeRawFixed32(Float.floatToIntBits(val));
    }

    /**
     * Write a list of packed proto "float" type field value.
     */
    public void writePackedFloat(long fieldId, float[] val) {
        assertNotCompacted();
        final int id = checkFieldId(fieldId, FIELD_COUNT_PACKED | FIELD_TYPE_FLOAT);

        final int N = val != null ? val.length : 0;
        if (N > 0) {
            writeKnownLengthHeader(id, N * 4);
            for (int i=0; i<N; i++) {
                mBuffer.writeRawFixed32(Float.floatToIntBits(val[i]));
            }
        }
    }

    //
    // proto3 type: int32
    // java type: int
    // signed/unsigned: signed
    // encoding: varint
    // wire type: WIRE_TYPE_VARINT
    //

    /**
     * Writes a java int as an usigned varint.
     *
     * The unadorned int32 type in protobuf is unfortunate because it
     * is stored in memory as a signed value, but encodes as unsigned
     * varints, which are formally always longs.  So here, we encode
     * negative values as 64 bits, which will get the sign-extension,
     * and positive values as 32 bits, which saves a marginal amount
     * of work in that it processes ints instead of longs.
     */
    private void writeUnsignedVarintFromSignedInt(int val) {
        if (val >= 0) {
            mBuffer.writeRawVarint32(val);
        } else {
            mBuffer.writeRawVarint64(val);
        }
    }

    /**
     * Write a single proto "int32" type field value.
     *
     * Note that these are stored in memory as signed values and written as unsigned
     * varints, which if negative, are 10 bytes long. If you know the data is likely
     * to be negative, use "sint32".
     */
    public void writeInt32(long fieldId, int val) {
        assertNotCompacted();
        final int id = checkFieldId(fieldId, FIELD_COUNT_SINGLE | FIELD_TYPE_INT32);

        if (val != 0) {
            writeTag(id, WIRE_TYPE_VARINT);
            writeUnsignedVarintFromSignedInt(val);
        }
    }

    /**
     * Write a single repeated proto "int32" type field value.
     *
     * Note that these are stored in memory as signed values and written as unsigned
     * varints, which if negative, are 10 bytes long. If you know the data is likely
     * to be negative, use "sint32".
     */
    public void writeRepeatedInt32(long fieldId, int val) {
        assertNotCompacted();
        final int id = checkFieldId(fieldId, FIELD_COUNT_REPEATED | FIELD_TYPE_INT32);

        writeTag(id, WIRE_TYPE_VARINT);
        writeUnsignedVarintFromSignedInt(val);
    }

    /**
     * Write a list of packed proto "int32" type field value.
     *
     * Note that these are stored in memory as signed values and written as unsigned
     * varints, which if negative, are 10 bytes long. If you know the data is likely
     * to be negative, use "sint32".
     */
    public void writePackedInt32(long fieldId, int[] val) {
        assertNotCompacted();
        final int id = checkFieldId(fieldId, FIELD_COUNT_PACKED | FIELD_TYPE_INT32);

        final int N = val != null ? val.length : 0;
        if (N > 0) {
            int size = 0;
            for (int i=0; i<N; i++) {
                final int v = val[i];
                size += v >= 0 ? EncodedBuffer.getRawVarint32Size(v) : 10;
            }
            writeKnownLengthHeader(id, size);
            for (int i=0; i<N; i++) {
                writeUnsignedVarintFromSignedInt(val[i]);
            }
        }
    }

    //
    // proto3 type: int64
    // java type: int
    // signed/unsigned: signed
    // encoding: varint
    // wire type: WIRE_TYPE_VARINT
    //

    /**
     * Write a single proto "int64" type field value.
     */
    public void writeInt64(long fieldId, long val) {
        assertNotCompacted();
        final int id = checkFieldId(fieldId, FIELD_COUNT_SINGLE | FIELD_TYPE_INT64);

        if (val != 0) {
            writeTag(id, WIRE_TYPE_VARINT);
            mBuffer.writeRawVarint64(val);
        }
    }

    /**
     * Write a single repeated proto "int64" type field value.
     */
    public void writeRepeatedInt64(long fieldId, long val) {
        assertNotCompacted();
        final int id = checkFieldId(fieldId, FIELD_COUNT_REPEATED | FIELD_TYPE_INT64);

        writeTag(id, WIRE_TYPE_VARINT);
        mBuffer.writeRawVarint64(val);
    }

    /**
     * Write a list of packed proto "int64" type field value.
     */
    public void writePackedInt64(long fieldId, long[] val) {
        assertNotCompacted();
        final int id = checkFieldId(fieldId, FIELD_COUNT_PACKED | FIELD_TYPE_INT64);

        final int N = val != null ? val.length : 0;
        if (N > 0) {
            int size = 0;
            for (int i=0; i<N; i++) {
                size += EncodedBuffer.getRawVarint64Size(val[i]);
            }
            writeKnownLengthHeader(id, size);
            for (int i=0; i<N; i++) {
                mBuffer.writeRawVarint64(val[i]);
            }
        }
    }

    //
    // proto3 type: uint32
    // java type: int
    // signed/unsigned: unsigned
    // encoding: varint
    // wire type: WIRE_TYPE_VARINT
    //

    /**
     * Write a single proto "uint32" type field value.
     */
    public void writeUInt32(long fieldId, int val) {
        assertNotCompacted();
        final int id = checkFieldId(fieldId, FIELD_COUNT_SINGLE | FIELD_TYPE_UINT32);

        if (val != 0) {
            writeTag(id, WIRE_TYPE_VARINT);
            mBuffer.writeRawVarint32(val);
        }
    }

    /**
     * Write a single repeated proto "uint32" type field value.
     */
    public void writeRepeatedUInt32(long fieldId, int val) {
        assertNotCompacted();
        final int id = checkFieldId(fieldId, FIELD_COUNT_REPEATED | FIELD_TYPE_UINT32);

        writeTag(id, WIRE_TYPE_VARINT);
        mBuffer.writeRawVarint32(val);
    }

    /**
     * Write a list of packed proto "uint32" type field value.
     */
    public void writePackedUInt32(long fieldId, int[] val) {
        assertNotCompacted();
        final int id = checkFieldId(fieldId, FIELD_COUNT_PACKED | FIELD_TYPE_UINT32);

        final int N = val != null ? val.length : 0;
        if (N > 0) {
            int size = 0;
            for (int i=0; i<N; i++) {
                size += EncodedBuffer.getRawVarint32Size(val[i]);
            }
            writeKnownLengthHeader(id, size);
            for (int i=0; i<N; i++) {
                mBuffer.writeRawVarint32(val[i]);
            }
        }
    }

    //
    // proto3 type: uint64
    // java type: int
    // signed/unsigned: unsigned
    // encoding: varint
    // wire type: WIRE_TYPE_VARINT
    //

    /**
     * Write a single proto "uint64" type field value.
     */
    public void writeUInt64(long fieldId, long val) {
        assertNotCompacted();
        final int id = checkFieldId(fieldId, FIELD_COUNT_SINGLE | FIELD_TYPE_UINT64);

        if (val != 0) {
            writeTag(id, WIRE_TYPE_VARINT);
            mBuffer.writeRawVarint64(val);
        }
    }

    /**
     * Write a single proto "uint64" type field value.
     */
    public void writeRepeatedUInt64(long fieldId, long val) {
        assertNotCompacted();
        final int id = checkFieldId(fieldId, FIELD_COUNT_REPEATED | FIELD_TYPE_UINT64);

        writeTag(id, WIRE_TYPE_VARINT);
        mBuffer.writeRawVarint64(val);
    }

    /**
     * Write a single proto "uint64" type field value.
     */
    public void writePackedUInt64(long fieldId, long[] val) {
        assertNotCompacted();
        final int id = checkFieldId(fieldId, FIELD_COUNT_PACKED | FIELD_TYPE_UINT64);

        final int N = val != null ? val.length : 0;
        if (N > 0) {
            int size = 0;
            for (int i=0; i<N; i++) {
                size += EncodedBuffer.getRawVarint64Size(val[i]);
            }
            writeKnownLengthHeader(id, size);
            for (int i=0; i<N; i++) {
                mBuffer.writeRawVarint64(val[i]);
            }
        }
    }

    //
    // proto3 type: sint32
    // java type: int
    // signed/unsigned: signed
    // encoding: zig-zag
    // wire type: WIRE_TYPE_VARINT
    //

    /**
     * Write a single proto "sint32" type field value.
     */
    public void writeSInt32(long fieldId, int val) {
        assertNotCompacted();
        final int id = checkFieldId(fieldId, FIELD_COUNT_SINGLE | FIELD_TYPE_SINT32);

        if (val != 0) {
            writeTag(id, WIRE_TYPE_VARINT);
            mBuffer.writeRawZigZag32(val);
        }
    }

    /**
     * Write a single repeated proto "sint32" type field value.
     */
    public void writeRepeatedSInt32(long fieldId, int val) {
        assertNotCompacted();
        final int id = checkFieldId(fieldId, FIELD_COUNT_REPEATED | FIELD_TYPE_SINT32);

        writeTag(id, WIRE_TYPE_VARINT);
        mBuffer.writeRawZigZag32(val);
    }

    /**
     * Write a list of packed proto "sint32" type field value.
     */
    public void writePackedSInt32(long fieldId, int[] val) {
        assertNotCompacted();
        final int id = checkFieldId(fieldId, FIELD_COUNT_PACKED | FIELD_TYPE_SINT32);

        final int N = val != null ? val.length : 0;
        if (N > 0) {
            int size = 0;
            for (int i=0; i<N; i++) {
                size += EncodedBuffer.getRawZigZag32Size(val[i]);
            }
            writeKnownLengthHeader(id, size);
            for (int i=0; i<N; i++) {
                mBuffer.writeRawZigZag32(val[i]);
            }
        }
    }

    //
    // proto3 type: sint64
    // java type: int
    // signed/unsigned: signed
    // encoding: zig-zag
    // wire type: WIRE_TYPE_VARINT
    //

    /**
     * Write a single proto "sint64" type field value.
     */
    public void writeSInt64(long fieldId, long val) {
        assertNotCompacted();
        final int id = checkFieldId(fieldId, FIELD_COUNT_SINGLE | FIELD_TYPE_SINT64);

        if (val != 0) {
            writeTag(id, WIRE_TYPE_VARINT);
            mBuffer.writeRawZigZag64(val);
        }
    }

    /**
     * Write a single repeated proto "sint64" type field value.
     */
    public void writeRepeatedSInt64(long fieldId, long val) {
        assertNotCompacted();
        final int id = checkFieldId(fieldId, FIELD_COUNT_REPEATED | FIELD_TYPE_SINT64);

        writeTag(id, WIRE_TYPE_VARINT);
        mBuffer.writeRawZigZag64(val);
    }

    /**
     * Write a list of packed proto "sint64" type field value.
     */
    public void writePackedSInt64(long fieldId, long[] val) {
        assertNotCompacted();
        final int id = checkFieldId(fieldId, FIELD_COUNT_PACKED | FIELD_TYPE_SINT64);

        final int N = val != null ? val.length : 0;
        if (N > 0) {
            int size = 0;
            for (int i=0; i<N; i++) {
                size += EncodedBuffer.getRawZigZag64Size(val[i]);
            }
            writeKnownLengthHeader(id, size);
            for (int i=0; i<N; i++) {
                mBuffer.writeRawZigZag64(val[i]);
            }
        }
    }

    //
    // proto3 type: fixed32
    // java type: int
    // encoding: little endian
    // wire type: WIRE_TYPE_FIXED32
    //

    /**
     * Write a single proto "fixed32" type field value.
     */
    public void writeFixed32(long fieldId, int val) {
        assertNotCompacted();
        final int id = checkFieldId(fieldId, FIELD_COUNT_SINGLE | FIELD_TYPE_FIXED32);

        if (val != 0) {
            writeTag(id, WIRE_TYPE_FIXED32);
            mBuffer.writeRawFixed32(val);
        }
    }

    /**
     * Write a single repeated proto "fixed32" type field value.
     */
    public void writeRepeatedFixed32(long fieldId, int val) {
        assertNotCompacted();
        final int id = checkFieldId(fieldId, FIELD_COUNT_REPEATED | FIELD_TYPE_FIXED32);

        writeTag(id, WIRE_TYPE_FIXED32);
        mBuffer.writeRawFixed32(val);
    }

    /**
     * Write a list of packed proto "fixed32" type field value.
     */
    public void writePackedFixed32(long fieldId, int[] val) {
        assertNotCompacted();
        final int id = checkFieldId(fieldId, FIELD_COUNT_PACKED | FIELD_TYPE_FIXED32);

        final int N = val != null ? val.length : 0;
        if (N > 0) {
            writeKnownLengthHeader(id, N * 4);
            for (int i=0; i<N; i++) {
                mBuffer.writeRawFixed32(val[i]);
            }
        }
    }

    //
    // proto3 type: fixed64
    // java type: long
    // encoding: fixed64
    // wire type: WIRE_TYPE_FIXED64
    //

    /**
     * Write a single proto "fixed64" type field value.
     */
    public void writeFixed64(long fieldId, long val) {
        assertNotCompacted();
        final int id = checkFieldId(fieldId, FIELD_COUNT_SINGLE | FIELD_TYPE_FIXED64);

        if (val != 0) {
            writeTag(id, WIRE_TYPE_FIXED64);
            mBuffer.writeRawFixed64(val);
        }
    }

    /**
     * Write a single repeated proto "fixed64" type field value.
     */
    public void writeRepeatedFixed64(long fieldId, long val) {
        assertNotCompacted();
        final int id = checkFieldId(fieldId, FIELD_COUNT_REPEATED | FIELD_TYPE_FIXED64);

        writeTag(id, WIRE_TYPE_FIXED64);
        mBuffer.writeRawFixed64(val);
    }

    /**
     * Write a list of packed proto "fixed64" type field value.
     */
    public void writePackedFixed64(long fieldId, long[] val) {
        assertNotCompacted();
        final int id = checkFieldId(fieldId, FIELD_COUNT_PACKED | FIELD_TYPE_FIXED64);

        final int N = val != null ? val.length : 0;
        if (N > 0) {
            writeKnownLengthHeader(id, N * 8);
            for (int i=0; i<N; i++) {
                mBuffer.writeRawFixed64(val[i]);
            }
        }
    }

    //
    // proto3 type: sfixed32
    // java type: int
    // encoding: little endian
    // wire type: WIRE_TYPE_FIXED32
    //
    /**
     * Write a single proto "sfixed32" type field value.
     */
    public void writeSFixed32(long fieldId, int val) {
        assertNotCompacted();
        final int id = checkFieldId(fieldId, FIELD_COUNT_SINGLE | FIELD_TYPE_SFIXED32);

        if (val != 0) {
            writeTag(id, WIRE_TYPE_FIXED32);
            mBuffer.writeRawFixed32(val);
        }
    }

    /**
     * Write a single repeated proto "sfixed32" type field value.
     */
    public void writeRepeatedSFixed32(long fieldId, int val) {
        assertNotCompacted();
        final int id = checkFieldId(fieldId, FIELD_COUNT_REPEATED | FIELD_TYPE_SFIXED32);

        writeTag(id, WIRE_TYPE_FIXED32);
        mBuffer.writeRawFixed32(val);
    }

    /**
     * Write a list of packed proto "sfixed32" type field value.
     */
    public void writePackedSFixed32(long fieldId, int[] val) {
        assertNotCompacted();
        final int id = checkFieldId(fieldId, FIELD_COUNT_PACKED | FIELD_TYPE_SFIXED32);

        final int N = val != null ? val.length : 0;
        if (N > 0) {
            writeKnownLengthHeader(id, N * 4);
            for (int i=0; i<N; i++) {
                mBuffer.writeRawFixed32(val[i]);
            }
        }
    }

    //
    // proto3 type: sfixed64
    // java type: long
    // encoding: little endian
    // wire type: WIRE_TYPE_FIXED64
    //

    /**
     * Write a single proto "sfixed64" type field value.
     */
    public void writeSFixed64(long fieldId, long val) {
        assertNotCompacted();
        final int id = checkFieldId(fieldId, FIELD_COUNT_SINGLE | FIELD_TYPE_SFIXED64);

        if (val != 0) {
            writeTag(id, WIRE_TYPE_FIXED64);
            mBuffer.writeRawFixed64(val);
        }
    }

    /**
     * Write a single repeated proto "sfixed64" type field value.
     */
    public void writeRepeatedSFixed64(long fieldId, long val) {
        assertNotCompacted();
        final int id = checkFieldId(fieldId, FIELD_COUNT_REPEATED | FIELD_TYPE_SFIXED64);

        writeTag(id, WIRE_TYPE_FIXED64);
        mBuffer.writeRawFixed64(val);
    }

    /**
     * Write a list of packed proto "sfixed64" type field value.
     */
    public void writePackedSFixed64(long fieldId, long[] val) {
        assertNotCompacted();
        final int id = checkFieldId(fieldId, FIELD_COUNT_PACKED | FIELD_TYPE_SFIXED64);

        final int N = val != null ? val.length : 0;
        if (N > 0) {
            writeKnownLengthHeader(id, N * 8);
            for (int i=0; i<N; i++) {
                mBuffer.writeRawFixed64(val[i]);
            }
        }
    }

    //
    // proto3 type: bool
    // java type: boolean
    // encoding: varint
    // wire type: WIRE_TYPE_VARINT
    //

    /**
     * Write a single proto "bool" type field value.
     */
    public void writeBool(long fieldId, boolean val) {
        assertNotCompacted();
        final int id = checkFieldId(fieldId, FIELD_COUNT_SINGLE | FIELD_TYPE_BOOL);

        if (val) {
            writeTag(id, WIRE_TYPE_VARINT);
            // 0 and 1 are the same as their varint counterparts
            mBuffer.writeRawByte((byte)1);
        }
    }

    /**
     * Write a single repeated proto "bool" type field value.
     */
    public void writeRepeatedBool(long fieldId, boolean val) {
        assertNotCompacted();
        final int id = checkFieldId(fieldId, FIELD_COUNT_REPEATED | FIELD_TYPE_BOOL);

        writeTag(id, WIRE_TYPE_VARINT);
        mBuffer.writeRawByte((byte)(val ? 1 : 0));
    }

    /**
     * Write a list of packed proto "bool" type field value.
     */
    public void writePackedBool(long fieldId, boolean[] val) {
        assertNotCompacted();
        final int id = checkFieldId(fieldId, FIELD_COUNT_PACKED | FIELD_TYPE_BOOL);

        final int N = val != null ? val.length : 0;
        if (N > 0) {
            // Write the header
            writeKnownLengthHeader(id, N);

            // Write the data
            for (int i=0; i<N; i++) {
                // 0 and 1 are the same as their varint counterparts
                mBuffer.writeRawByte((byte)(val[i] ? 1 : 0));
            }
        }
    }

    //
    // proto3 type: string
    // java type: String
    // encoding: utf-8
    // wire type: WIRE_TYPE_LENGTH_DELIMITED
    //

    /**
     * Write a single proto "string" type field value.
     */
    public void writeString(long fieldId, String val) {
        assertNotCompacted();
        final int id = checkFieldId(fieldId, FIELD_COUNT_SINGLE | FIELD_TYPE_STRING);

        if (val != null && val.length() > 0) {
            writeUtf8String(id, val);
        }
    }

    /**
     * Write a single repeated proto "string" type field value.
     */
    public void writeRepeatedString(long fieldId, String val) {
        assertNotCompacted();
        final int id = checkFieldId(fieldId, FIELD_COUNT_REPEATED | FIELD_TYPE_STRING);

        if (val == null || val.length() == 0) {
            writeKnownLengthHeader(id, 0);
        } else {
            writeUtf8String(id, val);
        }
    }

    /**
     * Write a list of packed proto "string" type field value.
     */
    private void writeUtf8String(int id, String val) {
        // TODO: Is it worth converting by hand in order to not allocate?
        try {
            final byte[] buf = val.getBytes("UTF-8");
            writeKnownLengthHeader(id, buf.length);
            mBuffer.writeRawBuffer(buf);
        } catch (UnsupportedEncodingException ex) {
            throw new RuntimeException("not possible");
        }
    }

    //
    // proto3 type: bytes
    // java type: byte[]
    // encoding: varint
    // wire type: WIRE_TYPE_VARINT
    //

    /**
     * Write a single proto "bytes" type field value.
     */
    public void writeBytes(long fieldId, byte[] val) {
        assertNotCompacted();
        final int id = checkFieldId(fieldId, FIELD_COUNT_SINGLE | FIELD_TYPE_BYTES);

        if (val != null && val.length > 0) {
            writeKnownLengthHeader(id, val.length);
            mBuffer.writeRawBuffer(val);
        }
    }

    /**
     * Write a single repeated proto "bytes" type field value.
     */
    public void writeRepeatedBytes(long fieldId, byte[] val) {
        assertNotCompacted();
        final int id = checkFieldId(fieldId, FIELD_COUNT_REPEATED | FIELD_TYPE_BYTES);

        writeKnownLengthHeader(id, val == null ? 0 : val.length);
        mBuffer.writeRawBuffer(val);
    }

    //
    // proto3 type: enum
    // java type: int
    // signed/unsigned: unsigned
    // encoding: varint
    // wire type: WIRE_TYPE_VARINT
    //

    /**
     * Write a single proto enum type field value.
     */
    public void writeEnum(long fieldId, int val) {
        assertNotCompacted();
        final int id = checkFieldId(fieldId, FIELD_COUNT_SINGLE | FIELD_TYPE_ENUM);

        if (val != 0) {
            writeTag(id, WIRE_TYPE_VARINT);
            writeUnsignedVarintFromSignedInt(val);
        }
    }

    /**
     * Write a single repeated proto enum type field value.
     */
    public void writeRepeatedEnum(long fieldId, int val) {
        assertNotCompacted();
        final int id = checkFieldId(fieldId, FIELD_COUNT_REPEATED | FIELD_TYPE_ENUM);

        writeTag(id, WIRE_TYPE_VARINT);
        writeUnsignedVarintFromSignedInt(val);
    }

    /**
     * Write a list of packed proto enum type field value.
     */
    public void writePackedEnum(long fieldId, int[] val) {
        assertNotCompacted();
        final int id = checkFieldId(fieldId, FIELD_COUNT_PACKED | FIELD_TYPE_ENUM);

        final int N = val != null ? val.length : 0;
        if (N > 0) {
            int size = 0;
            for (int i=0; i<N; i++) {
                final int v = val[i];
                size += v >= 0 ? EncodedBuffer.getRawVarint32Size(v) : 10;
            }
            writeKnownLengthHeader(id, size);
            for (int i=0; i<N; i++) {
                writeUnsignedVarintFromSignedInt(val[i]);
            }
        }
    }

    //
    // Child objects
    //

    /**
     * Make a token.
     *  Bits 61-63 - tag size (So we can go backwards later if the object had not data)
     *                - 3 bits, max value 7, max value needed 5
     *  Bit  60    - true if the object is repeated (lets us require endObject or endRepeatedObject)
     *  Bits 59-51 - depth (For error checking)
     *                - 9 bits, max value 512, when checking, value is masked (if we really
     *                  are more than 512 levels deep)
     *  Bits 32-50 - objectId (For error checking)
     *                - 19 bits, max value 524,288. that's a lot of objects. IDs will wrap
     *                  because of the overflow, and only the tokens are compared.
     *  Bits  0-31 - offset of the first size field in the buffer.
     */
    // VisibleForTesting
    public static long makeToken(int tagSize, boolean repeated, int depth, int objectId,
            int sizePos) {
        return ((0x07L & (long)tagSize) << 61)
                | (repeated ? (1L << 60) : 0)
                | (0x01ffL & (long)depth) << 51
                | (0x07ffffL & (long)objectId) << 32
                | (0x0ffffffffL & (long)sizePos);
    }

    /**
     * Get the encoded tag size from the token.
     */
    public static int getTagSizeFromToken(long token) {
        return (int)(0x7 & (token >> 61));
    }

    /**
     * Get whether this is a call to startObject (false) or startRepeatedObject (true).
     */
    public static boolean getRepeatedFromToken(long token) {
        return (0x1 & (token >> 60)) != 0;
    }

    /**
     * Get the nesting depth of startObject calls from the token.
     */
    public static int getDepthFromToken(long token) {
        return (int)(0x01ff & (token >> 51));
    }

    /**
     * Get the object ID from the token. The object ID is a serial number for the
     * startObject calls that have happened on this object.  The values are truncated
     * to 9 bits, but that is sufficient for error checking.
     */
    public static int getObjectIdFromToken(long token) {
        return (int)(0x07ffff & (token >> 32));
    }

    /**
     * Get the location of the childRawSize (the first 32 bit size field) in this object.
     */
    public static int getSizePosFromToken(long token) {
        return (int)token;
    }

    /**
     * Convert the object ID to the ordinal value -- the n-th call to startObject.
     * The object IDs start at -1 and count backwards, so that the value is unlikely
     * to alias with an actual size field that had been written.
     */
    public static int convertObjectIdToOrdinal(int objectId) {
        return (-1 & 0x07ffff) - objectId;
    }

    /**
     * Return a debugging string of a token.
     */
    public static String token2String(long token) {
        if (token == 0L) {
            return "Token(0)";
        } else {
            return "Token(val=0x" + Long.toHexString(token)
                    + " depth=" + getDepthFromToken(token)
                    + " object=" + convertObjectIdToOrdinal(getObjectIdFromToken(token))
                    + " tagSize=" + getTagSizeFromToken(token)
                    + " sizePos=" + getSizePosFromToken(token)
                    + ')';
        }
    }

    /**
     * Start a child object.
     *
     * Returns a token which should be passed to endObject.  Calls to endObject must be
     * nested properly.
     */
    public long startObject(long fieldId) {
        assertNotCompacted();
        final int id = checkFieldId(fieldId, FIELD_COUNT_SINGLE | FIELD_TYPE_OBJECT);

        return startObjectImpl(id, false);
    }

    /**
     * End a child object. Pass in the token from the correspoinding startObject call.
     */
    public void endObject(long token) {
        assertNotCompacted();

        endObjectImpl(token, false);
    }

    /**
     * Start a repeated child object.
     *
     * Returns a token which should be passed to endObject.  Calls to endObject must be
     * nested properly.
     */
    public long startRepeatedObject(long fieldId) {
        assertNotCompacted();
        final int id = checkFieldId(fieldId, FIELD_COUNT_REPEATED | FIELD_TYPE_OBJECT);

        return startObjectImpl(id, true);
    }

    /**
     * End a child object. Pass in the token from the correspoinding startRepeatedObject call.
     */
    public void endRepeatedObject(long token) {
        assertNotCompacted();

        endObjectImpl(token, true);
    }

    /**
     * Common implementation of startObject and startRepeatedObject.
     */
    private long startObjectImpl(final int id, boolean repeated) {
        writeTag(id, WIRE_TYPE_LENGTH_DELIMITED);
        final int sizePos = mBuffer.getWritePos();
        mDepth++;
        mNextObjectId--;

        // Write the previous token, giving us a stack of expected tokens.
        // After endObject returns, the first fixed32 becomeschildRawSize (set in endObject)
        // and the second one becomes childEncodedSize (set in editEncodedSize).
        mBuffer.writeRawFixed32((int)(mExpectedObjectToken >> 32));
        mBuffer.writeRawFixed32((int)mExpectedObjectToken);

        long old = mExpectedObjectToken;

        mExpectedObjectToken = makeToken(getTagSize(id), repeated, mDepth, mNextObjectId, sizePos);
        return mExpectedObjectToken;
    }

    /**
     * Common implementation of endObject and endRepeatedObject.
     */
    private void endObjectImpl(long token, boolean repeated) {
        // The upper 32 bits of the token is the depth of startObject /
        // endObject calls.  We could get aritrarily sophisticated, but
        // that's enough to prevent the common error of missing an
        // endObject somewhere.
        // The lower 32 bits of the token is the offset in the buffer
        // at which to write the size.
        final int depth = getDepthFromToken(token);
        final boolean expectedRepeated = getRepeatedFromToken(token);
        final int sizePos = getSizePosFromToken(token);
        final int childRawSize = mBuffer.getWritePos() - sizePos - 8;

        if (repeated != expectedRepeated) {
            if (repeated) {
                throw new IllegalArgumentException("endRepeatedObject called where endObject should"
                        + " have been");
            } else {
                throw new IllegalArgumentException("endObject called where endRepeatedObject should"
                        + " have been");
            }
        }

        // Check that we're getting the token and depth that we are expecting.
        if ((mDepth & 0x01ff) != depth || mExpectedObjectToken != token) {
            // This text of exception is united tested.  That test also implicity checks
            // that we're tracking the objectIds and depths correctly.
            throw new IllegalArgumentException("Mismatched startObject/endObject calls."
                    + " Current depth " + mDepth
                    + " token=" + token2String(token)
                    + " expectedToken=" + token2String(mExpectedObjectToken));
        }

        // Get the next expected token that we stashed away in the buffer.
        mExpectedObjectToken = (((long)mBuffer.getRawFixed32At(sizePos)) << 32)
                | (0x0ffffffffL & (long)mBuffer.getRawFixed32At(sizePos+4));

        mDepth--;
        if (childRawSize > 0) {
            mBuffer.editRawFixed32(sizePos, -childRawSize);
            mBuffer.editRawFixed32(sizePos+4, -1);
        } else if (repeated) {
            mBuffer.editRawFixed32(sizePos, 0);
            mBuffer.editRawFixed32(sizePos+4, 0);
        } else {
            // The object has no data.  Don't include it.
            mBuffer.rewindWriteTo(sizePos - getTagSizeFromToken(token));
        }
    }

    //
    // Tags
    //

    /**
     * Combine a fieldId (the field keys in the proto file) and the field flags.
     * Mostly useful for testing because the generated code contains the fieldId
     * constants.
     */
    public static long makeFieldId(int id, long fieldFlags) {
        return fieldFlags | (((long)id) & 0x0ffffffffL);
    }

    /**
     * Validates that the fieldId providied is of the type and count from expectedType.
     *
     * The type must match exactly to pass this check.
     *
     * The count must match according to this truth table to pass the check:
     *
     *                  expectedFlags
     *                  UNKNOWN     SINGLE      REPEATED    PACKED
     *    fieldId
     *    UNKNOWN       true        false       false       false
     *    SINGLE        x           true        false       false
     *    REPEATED      x           false       true        false
     *    PACKED        x           false       true        true
     *
     * @throws IllegalArgumentException if it is not.
     *
     * @return The raw ID of that field.
     */
    public static int checkFieldId(long fieldId, long expectedFlags) {
        final long fieldCount = fieldId & FIELD_COUNT_MASK;
        final long fieldType = fieldId & FIELD_TYPE_MASK;
        final long expectedCount = expectedFlags & FIELD_COUNT_MASK;
        final long expectedType = expectedFlags & FIELD_TYPE_MASK;
        if (((int)fieldId) == 0) {
            throw new IllegalArgumentException("Invalid proto field " + (int)fieldId
                    + " fieldId=" + Long.toHexString(fieldId));
        }
        if (fieldType != expectedType
                || !((fieldCount == expectedCount)
                    || (fieldCount == FIELD_COUNT_PACKED
                        && expectedCount == FIELD_COUNT_REPEATED))) {
            final String countString = getFieldCountString(fieldCount);
            final String typeString = getFieldTypeString(fieldType);
            if (typeString != null && countString != null) {
                final StringBuilder sb = new StringBuilder();
                if (expectedType == FIELD_TYPE_OBJECT) {
                    sb.append("start");
                } else {
                    sb.append("write");
                }
                sb.append(getFieldCountString(expectedCount));
                sb.append(getFieldTypeString(expectedType));
                sb.append(" called for field ");
                sb.append((int)fieldId);
                sb.append(" which should be used with ");
                if (fieldType == FIELD_TYPE_OBJECT) {
                    sb.append("start");
                } else {
                    sb.append("write");
                }
                sb.append(countString);
                sb.append(typeString);
                if (fieldCount == FIELD_COUNT_PACKED) {
                    sb.append(" or writeRepeated");
                    sb.append(typeString);
                }
                sb.append('.');
                throw new IllegalArgumentException(sb.toString());
            } else {
                final StringBuilder sb = new StringBuilder();
                if (expectedType == FIELD_TYPE_OBJECT) {
                    sb.append("start");
                } else {
                    sb.append("write");
                }
                sb.append(getFieldCountString(expectedCount));
                sb.append(getFieldTypeString(expectedType));
                sb.append(" called with an invalid fieldId: 0x");
                sb.append(Long.toHexString(fieldId));
                sb.append(". The proto field ID might be ");
                sb.append((int)fieldId);
                sb.append('.');
                throw new IllegalArgumentException(sb.toString());
            }
        }
        return (int)fieldId;
    }

    /**
     * Get the developer-usable name of a field type.
     */
    private static String getFieldTypeString(long fieldType) {
        int index = ((int)((fieldType & FIELD_TYPE_MASK) >>> FIELD_TYPE_SHIFT)) - 1;
        if (index >= 0 && index < FIELD_TYPE_NAMES.length) {
            return FIELD_TYPE_NAMES[index];
        } else {
            return null;
        }
    }

    /**
     * Get the developer-usable name of a field count.
     */
    private static String getFieldCountString(long fieldCount) {
        if (fieldCount == FIELD_COUNT_SINGLE) {
            return "";
        } else if (fieldCount == FIELD_COUNT_REPEATED) {
            return "Repeated";
        } else if (fieldCount == FIELD_COUNT_PACKED) {
            return "Packed";
        } else {
            return null;
        }
    }


    /**
     * Return how many bytes an encoded field tag will require.
     */
    private static int getTagSize(int id) {
        return EncodedBuffer.getRawVarint32Size(id << FIELD_ID_SHIFT);
    }

    /**
     * Write a field tage to the stream.
     */
    public void writeTag(int id, int wireType) {
        mBuffer.writeRawVarint32((id << FIELD_ID_SHIFT) | wireType);
    }

    /**
     * Write the header of a WIRE_TYPE_LENGTH_DELIMITED field for one where
     * we know the size in advance and do not need to compute and compact.
     */
    private void writeKnownLengthHeader(int id, int size) {
        // Write the tag
        writeTag(id, WIRE_TYPE_LENGTH_DELIMITED);
        // Size will be compacted later, but we know the size, so write it,
        // once for the rawSize and once for the encodedSize.
        mBuffer.writeRawFixed32(size);
        mBuffer.writeRawFixed32(size);
    }

    //
    // Getting the buffer and compaction
    //

    /**
     * Assert that the compact call has not already occured.
     *
     * TODO: Will change when we add the OutputStream version of ProtoOutputStream.
     */
    private void assertNotCompacted() {
        if (mCompacted) {
            throw new IllegalArgumentException("write called after compact");
        }
    }

    /**
     * Finish the encoding of the data, and return a byte[] with
     * the protobuf formatted data.
     *
     * After this call, do not call any of the write* functions. The
     * behavior is undefined.
     */
    public byte[] getBytes() {
        compactIfNecessary();

        return mBuffer.getBytes(mBuffer.getReadableSize());
    }

    /**
     * If the buffer hasn't already had the nested object size fields compacted
     * and turned into an actual protobuf format, then do so.
     */
    private void compactIfNecessary() {
        if (!mCompacted) {
            if (mDepth != 0) {
                throw new IllegalArgumentException("Trying to compact with " + mDepth
                        + " missing calls to endObject");
            }

            // The buffer must be compacted.
            mBuffer.startEditing();
            final int readableSize = mBuffer.getReadableSize();

            // Cache the sizes of the objects
            editEncodedSize(readableSize);

            // Re-write the buffer with the sizes as proper varints instead
            // of pairs of uint32s. We know this will always fit in the same
            // buffer because the pair of uint32s is exactly 8 bytes long, and
            // the single varint size will be no more than 5 bytes long.
            mBuffer.rewindRead();
            compactSizes(readableSize);

            // If there is any data left over that wasn't copied yet, copy it.
            if (mCopyBegin < readableSize) {
                mBuffer.writeFromThisBuffer(mCopyBegin, readableSize - mCopyBegin);
            }

            // Set the new readableSize
            mBuffer.startEditing();

            // It's not valid to write to this object anymore. The write
            // pointers are off, and then some of the data would be compacted
            // and some not.
            mCompacted = true;
        }
    }

    /**
     * First compaction pass.  Iterate through the data, and fill in the
     * nested object sizes so the next pass can compact them.
     */
    private int editEncodedSize(int rawSize) {
        int objectStart = mBuffer.getReadPos();
        int objectEnd = objectStart + rawSize;
        int encodedSize = 0;
        int tagPos;

        while ((tagPos = mBuffer.getReadPos()) < objectEnd) {
            int tag = readRawTag();
            encodedSize += EncodedBuffer.getRawVarint32Size(tag);

            final int wireType = tag & WIRE_TYPE_MASK;
            switch (wireType) {
                case WIRE_TYPE_VARINT:
                    encodedSize++;
                    while ((mBuffer.readRawByte() & 0x80) != 0) {
                        encodedSize++;
                    }
                    break;
                case WIRE_TYPE_FIXED64:
                    encodedSize += 8;
                    mBuffer.skipRead(8);
                    break;
                case WIRE_TYPE_LENGTH_DELIMITED: {
                    // This object is not of a fixed-size type.  So we need to figure
                    // out how big it should be.
                    final int childRawSize = mBuffer.readRawFixed32();
                    final int childEncodedSizePos = mBuffer.getReadPos();
                    int childEncodedSize = mBuffer.readRawFixed32();
                    if (childRawSize >= 0) {
                        // We know the size, just skip ahead.
                        if (childEncodedSize != childRawSize) {
                            throw new RuntimeException("Pre-computed size where the"
                                    + " precomputed size and the raw size in the buffer"
                                    + " don't match! childRawSize=" + childRawSize
                                    + " childEncodedSize=" + childEncodedSize
                                    + " childEncodedSizePos=" + childEncodedSizePos);
                        }
                        mBuffer.skipRead(childRawSize);
                    } else {
                        // We need to compute the size.  Recurse.
                        childEncodedSize = editEncodedSize(-childRawSize);
                        mBuffer.editRawFixed32(childEncodedSizePos, childEncodedSize);
                    }
                    encodedSize += EncodedBuffer.getRawVarint32Size(childEncodedSize)
                            + childEncodedSize;
                    break;
                }
                case WIRE_TYPE_START_GROUP:
                case WIRE_TYPE_END_GROUP:
                    throw new RuntimeException("groups not supported at index " + tagPos);
                case WIRE_TYPE_FIXED32:
                    encodedSize += 4;
                    mBuffer.skipRead(4);
                    break;
                default:
                    throw new ProtoParseException("editEncodedSize Bad tag tag=0x"
                            + Integer.toHexString(tag) + " wireType=" + wireType
                            + " -- " + mBuffer.getDebugString());
            }
        }

        return encodedSize;
    }

    /**
     * Second compaction pass.  Iterate through the data, and copy the data
     * forward in the buffer, converting the pairs of uint32s into a single
     * unsigned varint of the size.
     */
    private void compactSizes(int rawSize) {
        int objectStart = mBuffer.getReadPos();
        int objectEnd = objectStart + rawSize;
        int tagPos;
        while ((tagPos = mBuffer.getReadPos()) < objectEnd) {
            int tag = readRawTag();

            // For all the non-length-delimited field types, just skip over them,
            // and we'll just System.arraycopy it later, either in the case for
            // WIRE_TYPE_LENGTH_DELIMITED or at the top of the stack in compactIfNecessary().
            final int wireType = tag & WIRE_TYPE_MASK;
            switch (wireType) {
                case WIRE_TYPE_VARINT:
                    while ((mBuffer.readRawByte() & 0x80) != 0) { }
                    break;
                case WIRE_TYPE_FIXED64:
                    mBuffer.skipRead(8);
                    break;
                case WIRE_TYPE_LENGTH_DELIMITED: {
                    // Copy everything up to now, including the tag for this field.
                    mBuffer.writeFromThisBuffer(mCopyBegin, mBuffer.getReadPos() - mCopyBegin);
                    // Write the new size.
                    final int childRawSize = mBuffer.readRawFixed32();
                    final int childEncodedSize = mBuffer.readRawFixed32();
                    mBuffer.writeRawVarint32(childEncodedSize);
                    // Next time, start copying from here.
                    mCopyBegin = mBuffer.getReadPos();
                    if (childRawSize >= 0) {
                        // This is raw data, not an object. Skip ahead by the size.
                        // Recurse into the child
                        mBuffer.skipRead(childEncodedSize);
                    } else {
                        compactSizes(-childRawSize);
                    }
                    break;
                    // TODO: What does regular proto do if the object would be 0 size
                    // (e.g. if it is all default values).
                }
                case WIRE_TYPE_START_GROUP:
                case WIRE_TYPE_END_GROUP:
                    throw new RuntimeException("groups not supported at index " + tagPos);
                case WIRE_TYPE_FIXED32:
                    mBuffer.skipRead(4);
                    break;
                default:
                    throw new ProtoParseException("compactSizes Bad tag tag=0x"
                            + Integer.toHexString(tag) + " wireType=" + wireType
                            + " -- " + mBuffer.getDebugString());
            }
        }
    }

    /**
     * Read a raw tag from the buffer.
     */
    private int readRawTag() {
        if (mBuffer.getReadPos() == mBuffer.getReadableSize()) {
            return 0;
        }
        return (int)mBuffer.readRawUnsigned();
    }

    /**
     * Dump debugging data about the buffers with the given log tag.
     */
    public void dump(String tag) {
        Log.d(tag, mBuffer.getDebugString());
        mBuffer.dumpBuffers(tag);
    }
}
