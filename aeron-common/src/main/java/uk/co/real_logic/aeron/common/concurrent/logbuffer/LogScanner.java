/*
 * Copyright 2014 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.co.real_logic.aeron.common.concurrent.logbuffer;

import uk.co.real_logic.agrona.concurrent.UnsafeBuffer;

import static uk.co.real_logic.agrona.BitUtil.align;
import static uk.co.real_logic.aeron.common.concurrent.logbuffer.FrameDescriptor.*;

/**
 * Scans a log buffer reading MTU (Maximum Transmission Unit) ranges of messages
 * as they become available due to the tail progressing. This scanner makes the assumption that
 * the buffer is built in an append only fashion with no gaps.
 *
 * <b>Note:</b> An instance of this class is not threadsafe. Each thread must have its own instance.
 */
public class LogScanner extends LogBufferPartition
{
    /**
     * Handler for notifying an available chuck from latest offset.
     */
    @FunctionalInterface
    public interface AvailabilityHandler
    {
        void onAvailable(UnsafeBuffer buffer, int offset, int length);
    }

    private final int alignedHeaderLength;

    private int offset = 0;

    /**
     * Construct a reader that iterates over a log buffer with associated state buffer. Messages are identified as
     * they become available up to the MTU limit.
     *
     * @param termBuffer     containing the framed messages.
     * @param metaDataBuffer containing the state variables indicating the tail progress.
     * @param headerLength   of frame before payload begins.
     */
    public LogScanner(final UnsafeBuffer termBuffer, final UnsafeBuffer metaDataBuffer, final int headerLength)
    {
        super(termBuffer, metaDataBuffer);

        checkHeaderLength(headerLength);

        alignedHeaderLength = align(headerLength, FRAME_ALIGNMENT);
    }

    /**
     * The offset at which the next frame begins.
     *
     * @return the offset at which the next frame begins.
     */
    public int offset()
    {
        return offset;
    }

    /**
     * Is the scanning of the log buffer complete?
     *
     * @return is the scanning of the log buffer complete?
     */
    public boolean isComplete()
    {
        return offset >= capacity();
    }

    /**
     * Number of available bytes remaining in the buffer to be scanned.
     *
     * @return the number of bytes remaining in the buffer to be scanned.
     */
    public int remaining()
    {
        return tailVolatile() - offset;
    }

    /**
     * Scan forward in the buffer for available frames limited by what will fit in mtuLength.
     *
     * @param handler   called back if a frame is available.
     * @param mtuLength in bytes to scan.
     * @return number of bytes notified available
     */
    public int scanNext(final AvailabilityHandler handler, final int mtuLength)
    {
        int length = 0;

        if (!isComplete())
        {
            final int capacity = capacity();
            final int offset = this.offset;
            final UnsafeBuffer termBuffer = termBuffer();

            int padding = 0;

            do
            {
                final int frameLength = frameLengthVolatile(termBuffer, offset + length);
                if (0 == frameLength)
                {
                    break;
                }

                int alignedFrameLength = align(frameLength, FRAME_ALIGNMENT);

                if (isPaddingFrame(termBuffer, offset + length))
                {
                    padding = alignedFrameLength - alignedHeaderLength;
                    alignedFrameLength = alignedHeaderLength;
                }

                length += alignedFrameLength;

                if (length > mtuLength)
                {
                    length -= alignedFrameLength;
                    padding = 0;
                    break;
                }
            }
            while ((offset + length + padding) < capacity);

            if (length > 0)
            {
                this.offset += (length + padding);
                handler.onAvailable(termBuffer, offset, length);
            }
        }

        return length;
    }

    /**
     * Seek within a log buffer and get ready for the next scan.
     *
     * @param offset in the log buffer to seek to for next scan.
     * @throws IllegalStateException if the offset is beyond the tail.
     */
    public void seek(final int offset)
    {
        final int capacity = capacity();
        if (offset < 0 || offset > capacity)
        {
            throw new IndexOutOfBoundsException(String.format("Invalid offset %d: range is 0 - %d", offset, capacity));
        }

        this.offset = offset;
    }
}
