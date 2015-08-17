/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.mtp;

/**
 * This class encapsulates information about an object on an MTP device.
 * This corresponds to the ObjectInfo Dataset described in
 * section 5.3.1 of the MTP specification.
 */
public final class MtpObjectInfo {
    private int mHandle;
    private int mStorageId;
    private int mFormat;
    private int mProtectionStatus;
    private int mCompressedSize;
    private int mThumbFormat;
    private int mThumbCompressedSize;
    private int mThumbPixWidth;
    private int mThumbPixHeight;
    private int mImagePixWidth;
    private int mImagePixHeight;
    private int mImagePixDepth;
    private int mParent;
    private int mAssociationType;
    private int mAssociationDesc;
    private int mSequenceNumber;
    private String mName;
    private long mDateCreated;
    private long mDateModified;
    private String mKeywords;

    // only instantiated via JNI or via a builder
    private MtpObjectInfo() {
    }

    /**
     * Returns the object handle for the MTP object
     *
     * @return the object handle
     */
    public final int getObjectHandle() {
        return mHandle;
    }

    /**
     * Returns the storage ID for the MTP object's storage unit
     *
     * @return the storage ID
     */
    public final int getStorageId() {
        return mStorageId;
    }

    /**
     * Returns the format code for the MTP object
     *
     * @return the format code
     */
    public final int getFormat() {
        return mFormat;
    }

    /**
     * Returns the protection status for the MTP object
     * Possible values are:
     *
     * <ul>
     * <li> {@link android.mtp.MtpConstants#PROTECTION_STATUS_NONE}
     * <li> {@link android.mtp.MtpConstants#PROTECTION_STATUS_READ_ONLY}
     * <li> {@link android.mtp.MtpConstants#PROTECTION_STATUS_NON_TRANSFERABLE_DATA}
     * </ul>
     *
     * @return the protection status
     */
    public final int getProtectionStatus() {
        return mProtectionStatus;
    }

    /**
     * Returns the size of the MTP object
     *
     * @return the object size
     */
    public final int getCompressedSize() {
        return mCompressedSize;
    }

    /**
     * Returns the format code for the MTP object's thumbnail
     * Will be zero for objects with no thumbnail
     *
     * @return the thumbnail format code
     */
    public final int getThumbFormat() {
        return mThumbFormat;
    }

    /**
     * Returns the size of the MTP object's thumbnail
     * Will be zero for objects with no thumbnail
     *
     * @return the thumbnail size
     */
    public final int getThumbCompressedSize() {
        return mThumbCompressedSize;
    }

    /**
     * Returns the width of the MTP object's thumbnail in pixels
     * Will be zero for objects with no thumbnail
     *
     * @return the thumbnail width
     */
    public final int getThumbPixWidth() {
        return mThumbPixWidth;
    }

    /**
     * Returns the height of the MTP object's thumbnail in pixels
     * Will be zero for objects with no thumbnail
     *
     * @return the thumbnail height
     */
    public final int getThumbPixHeight() {
        return mThumbPixHeight;
    }

    /**
     * Returns the width of the MTP object in pixels
     * Will be zero for non-image objects
     *
     * @return the image width
     */
    public final int getImagePixWidth() {
        return mImagePixWidth;
    }

    /**
     * Returns the height of the MTP object in pixels
     * Will be zero for non-image objects
     *
     * @return the image height
     */
    public final int getImagePixHeight() {
        return mImagePixHeight;
    }

    /**
     * Returns the depth of the MTP object in bits per pixel
     * Will be zero for non-image objects
     *
     * @return the image depth
     */
    public final int getImagePixDepth() {
        return mImagePixDepth;
    }

    /**
     * Returns the object handle for the object's parent
     * Will be zero for the root directory of a storage unit
     *
     * @return the object's parent
     */
    public final int getParent() {
        return mParent;
    }

    /**
     * Returns the association type for the MTP object
     * Will be zero objects that are not of format
     * {@link android.mtp.MtpConstants#FORMAT_ASSOCIATION}
     * For directories the association type is typically
     * {@link android.mtp.MtpConstants#ASSOCIATION_TYPE_GENERIC_FOLDER}
     *
     * @return the object's association type
     */
    public final int getAssociationType() {
        return mAssociationType;
    }

    /**
     * Returns the association description for the MTP object
     * Will be zero objects that are not of format
     * {@link android.mtp.MtpConstants#FORMAT_ASSOCIATION}
     *
     * @return the object's association description
     */
    public final int getAssociationDesc() {
        return mAssociationDesc;
    }

   /**
     * Returns the sequence number for the MTP object
     * This field is typically not used for MTP devices,
     * but is sometimes used to define a sequence of photos
     * on PTP cameras.
     *
     * @return the object's sequence number
     */
    public final int getSequenceNumber() {
        return mSequenceNumber;
    }

   /**
     * Returns the name of the MTP object
     *
     * @return the object's name
     */
    public final String getName() {
        return mName;
    }

   /**
     * Returns the creation date of the MTP object
     * The value is represented as milliseconds since January 1, 1970
     *
     * @return the object's creation date
     */
    public final long getDateCreated() {
        return mDateCreated;
    }

   /**
     * Returns the modification date of the MTP object
     * The value is represented as milliseconds since January 1, 1970
     *
     * @return the object's modification date
     */
    public final long getDateModified() {
        return mDateModified;
    }

   /**
     * Returns a comma separated list of keywords for the MTP object
     *
     * @return the object's keyword list
     */
    public final String getKeywords() {
        return mKeywords;
    }

    /**
     * Builds a new object info instance.
     */
    public class Builder {
        private MtpObjectInfo mObjectInfo;

        public Builder() {
            mObjectInfo = new MtpObjectInfo();
            mObjectInfo.mHandle = -1;
        }

        /**
         * Creates a builder on a copy of an existing object info.
         * All fields, except the object handle will be copied.
         *
         * @param objectInfo object info of an existing entry
         */
        public Builder(MtpObjectInfo objectInfo) {
            mObjectInfo = new MtpObjectInfo();
            mObjectInfo.mHandle = -1;
            mObjectInfo.mAssociationDesc = mObjectInfo.mAssociationDesc;
            mObjectInfo.mAssociationType = mObjectInfo.mAssociationType;
            mObjectInfo.mCompressedSize = mObjectInfo.mCompressedSize;
            mObjectInfo.mDateCreated = mObjectInfo.mDateCreated;
            mObjectInfo.mDateModified = mObjectInfo.mDateModified;
            mObjectInfo.mFormat = mObjectInfo.mFormat;
            mObjectInfo.mImagePixDepth = mObjectInfo.mImagePixDepth;
            mObjectInfo.mImagePixHeight = mObjectInfo.mImagePixHeight;
            mObjectInfo.mImagePixWidth = mObjectInfo.mImagePixWidth;
            mObjectInfo.mKeywords = mObjectInfo.mKeywords;
            mObjectInfo.mName = mObjectInfo.mName;
            mObjectInfo.mParent = mObjectInfo.mParent;
            mObjectInfo.mProtectionStatus = mObjectInfo.mProtectionStatus;
            mObjectInfo.mSequenceNumber = mObjectInfo.mSequenceNumber;
            mObjectInfo.mStorageId = mObjectInfo.mStorageId;
            mObjectInfo.mThumbCompressedSize = mObjectInfo.mThumbCompressedSize;
            mObjectInfo.mThumbFormat = mObjectInfo.mThumbFormat;
            mObjectInfo.mThumbPixHeight = mObjectInfo.mThumbPixHeight;
            mObjectInfo.mThumbPixWidth = mObjectInfo.mThumbPixWidth;
        }

        public Builder setAssociationDesc(int value) {
            mObjectInfo.mAssociationDesc = value;
            return this;
        }

        public Builder setAssociationType(int value) {
            mObjectInfo.mAssociationType = value;
            return this;
        }

        public Builder setCompressedSize(int value) {
            mObjectInfo.mCompressedSize = value;
            return this;
        }

        public Builder setDateCreated(long value) {
            mObjectInfo.mDateCreated = value;
            return this;
        }

        public Builder setDateModified(long value) {
            mObjectInfo.mDateModified = value;
            return this;
        }

        public Builder setFormat(int value) {
            mObjectInfo.mFormat = value;
            return this;
        }

        public Builder setImagePixDepth(int value) {
            mObjectInfo.mImagePixDepth = value;
            return this;
        }

        public Builder setImagePixHeight(int value) {
            mObjectInfo.mImagePixHeight = value;
            return this;
        }

        public Builder setImagePixWidth(int value) {
            mObjectInfo.mImagePixWidth = value;
            return this;
        }

        public Builder setKeywords(String value) {
            mObjectInfo.mKeywords = value;
            return this;
        }

        public Builder setName(String value) {
            mObjectInfo.mName = value;
            return this;
        }

        public Builder setParent(int value) {
            mObjectInfo.mParent = value;
            return this;
        }

        public Builder setProtectionStatus(int value) {
            mObjectInfo.mProtectionStatus = value;
            return this;
        }

        public Builder setSequenceNumber(int value) {
            mObjectInfo.mSequenceNumber = value;
            return this;
        }

        public Builder setStorageId(int value) {
            mObjectInfo.mStorageId = value;
            return this;
        }

        public Builder setThumbCompressedSize(int value) {
            mObjectInfo.mThumbCompressedSize = value;
            return this;
        }

        public Builder setThumbFormat(int value) {
            mObjectInfo.mThumbFormat = value;
            return this;
        }

        public Builder setThumbPixHeight(int value) {
            mObjectInfo.mThumbPixHeight = value;
            return this;
        }

        public Builder setThumbPixWidth(int value) {
            mObjectInfo.mThumbPixWidth = value;
            return this;
        }

        /**
         * Builds the object info instance. Once called, methods of the builder
         * must not be called anymore.
         *
         * @return the object info of the newly created file, or NULL in case
         *         of an error.
         */
        public MtpObjectInfo build() {
            MtpObjectInfo result = mObjectInfo;
            mObjectInfo = null;
            return result;
        }
    }
}
