/*
 * Copyright 2014-2017 Real Logic Ltd.
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
package io.aeron.archiver;

import io.aeron.archiver.codecs.RecordingDescriptorDecoder;
import io.aeron.logbuffer.FrameDescriptor;
import io.aeron.protocol.DataHeaderFlyweight;
import org.agrona.BitUtil;
import org.agrona.IoUtil;
import org.agrona.concurrent.UnsafeBuffer;

import java.io.File;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

import static io.aeron.archiver.ArchiveUtil.*;
import static io.aeron.logbuffer.FrameDescriptor.FRAME_ALIGNMENT;
import static java.nio.channels.FileChannel.MapMode.READ_ONLY;
import static java.nio.file.StandardOpenOption.READ;

class RecordingFragmentReader implements AutoCloseable
{
    interface SimplifiedControlledPoll
    {
        /**
         * Called by the {@link RecordingFragmentReader}. Implementors need only process DATA fragments.
         *
         * @return true if fragment processed, false to abort.
         */
        boolean onFragment(UnsafeBuffer fragmentBuffer, int fragmentOffset, int fragmentLength);
    }

    private static final long NULL_POSITION = -1;
    private static final long NULL_LENGTH = -1;

    private final long recordingId;
    private final File archiveDir;
    private final int termBufferLength;
    private final long replayLength;
    private final int segmentFileLength;
    private final long fromPosition;

    private int segmentFileIndex;
    private UnsafeBuffer termBuffer = null;
    private int recordingTermStartOffset;
    private int termOffset;
    private long transmitted = 0;
    private MappedByteBuffer mappedByteBuffer;
    private boolean isDone = false;

    RecordingFragmentReader(final long recordingId, final File archiveDir) throws IOException
    {
        this(getDescriptor(recordingId, archiveDir), recordingId, archiveDir, NULL_POSITION, NULL_LENGTH);
    }

    RecordingFragmentReader(
        final long recordingId,
        final File archiveDir,
        final long position,
        final long length) throws IOException
    {
        this(getDescriptor(recordingId, archiveDir), recordingId, archiveDir, position, length);
    }

    public void close()
    {
        closeRecordingFile();
    }

    private RecordingFragmentReader(
        final RecordingDescriptorDecoder metaDecoder,
        final long recordingId,
        final File archiveDir,
        final long position,
        final long length) throws IOException
    {
        this.recordingId = recordingId;
        this.archiveDir = archiveDir;
        termBufferLength = metaDecoder.termBufferLength();
        segmentFileLength = metaDecoder.segmentFileLength();
        final long recordingLength = ArchiveUtil.recordingLength(metaDecoder);
        final long joinPosition = metaDecoder.joinPosition();

        final long replayLength = length == NULL_LENGTH ? recordingLength : length;

        final long fromPosition = position == NULL_POSITION ? joinPosition : position;
        segmentFileIndex = segmentFileIndex(joinPosition, fromPosition, segmentFileLength);
        final long recordingOffset = fromPosition & (segmentFileLength - 1);
        openRecordingFile();

        recordingTermStartOffset = (int)(recordingOffset - (recordingOffset & (termBufferLength - 1)));
        termBuffer = new UnsafeBuffer(mappedByteBuffer, recordingTermStartOffset, termBufferLength);
        termOffset = (int)(recordingOffset & (termBufferLength - 1));

        int frameOffset = 0;
        while (frameOffset < termOffset)
        {
            final int frameLength = FrameDescriptor.frameLength(termBuffer, frameOffset);
            final int alignedLength = BitUtil.align(frameLength, FRAME_ALIGNMENT);
            frameOffset += alignedLength;
        }

        if (frameOffset != termOffset)
        {
            final int alignmentOffset = frameOffset - termOffset;
            this.fromPosition = fromPosition + alignmentOffset;
            this.replayLength = replayLength - alignmentOffset;
        }
        else
        {
            this.fromPosition = fromPosition;
            this.replayLength = replayLength;
        }

        if (frameOffset >= termBufferLength)
        {
            termOffset = 0;
            nextTerm();
        }
        else
        {
            termOffset = frameOffset;
        }
    }

    boolean isDone()
    {
        return isDone;
    }

    long fromPosition()
    {
        return fromPosition;
    }

    int controlledPoll(final SimplifiedControlledPoll fragmentHandler, final int fragmentLimit)
        throws IOException
    {
        if (isDone())
        {
            return 0;
        }

        int polled = 0;

        while (termOffset < termBufferLength && transmitted < replayLength && polled < fragmentLimit)
        {
            final int frameOffset = termOffset;
            final int frameLength = FrameDescriptor.frameLength(termBuffer, frameOffset);
            if (frameLength == RecordingWriter.END_OF_RECORDING_INDICATOR)
            {
                isDone = true;
                return polled;
            }

            if (frameLength == RecordingWriter.END_OF_DATA_INDICATOR)
            {
                return polled;
            }

            final int alignedLength = BitUtil.align(frameLength, FRAME_ALIGNMENT);

            transmitted += alignedLength;
            termOffset += alignedLength;

            final int dataOffset = frameOffset + DataHeaderFlyweight.DATA_OFFSET;
            final int dataLength = frameLength - DataHeaderFlyweight.HEADER_LENGTH;

            if (!fragmentHandler.onFragment(termBuffer, dataOffset, dataLength))
            {
                transmitted -= alignedLength;
                termOffset -= alignedLength;
                return polled;
            }

            polled++;
        }

        if (transmitted >= replayLength)
        {
            isDone = true;
        }
        else if (termOffset == termBufferLength)
        {
            termOffset = 0;
            nextTerm();
        }

        return polled;
    }

    private void nextTerm() throws IOException
    {
        recordingTermStartOffset += termBufferLength;

        if (recordingTermStartOffset == segmentFileLength)
        {
            closeRecordingFile();
            segmentFileIndex++;
            openRecordingFile();
        }

        termBuffer.wrap(mappedByteBuffer, recordingTermStartOffset, termBufferLength);
    }

    private void closeRecordingFile()
    {
        IoUtil.unmap(mappedByteBuffer);
    }

    private void openRecordingFile() throws IOException
    {
        recordingTermStartOffset = 0;
        final String recordingDataFileName = recordingDataFileName(recordingId, segmentFileIndex);
        final File recordingDataFile = new File(archiveDir, recordingDataFileName);

        try (FileChannel fileChannel = FileChannel.open(recordingDataFile.toPath(), READ))
        {
            mappedByteBuffer = fileChannel.map(READ_ONLY, 0, segmentFileLength);
        }
    }

    private static RecordingDescriptorDecoder getDescriptor(final long recordingId, final File archiveDir)
        throws IOException
    {
        final String recordingMetaFileName = recordingMetaFileName(recordingId);
        final File recordingMetaFile = new File(archiveDir, recordingMetaFileName);

        return loadRecordingDescriptor(recordingMetaFile);
    }
}
