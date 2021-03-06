/*
 * Copyright 2014 - 2017 Real Logic Ltd.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package io.aeron.archiver.workloads;

import io.aeron.*;
import io.aeron.archiver.*;
import io.aeron.archiver.client.ArchiveClient;
import io.aeron.archiver.client.RecordingEventsListener;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import io.aeron.logbuffer.*;
import io.aeron.protocol.DataHeaderFlyweight;
import org.agrona.*;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.*;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;

import java.io.File;
import java.io.IOException;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static io.aeron.archiver.TestUtil.*;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;

@Ignore
public class ArchiveReplayLoadTest
{
    private static final int TIMEOUT = 5000;
    private static final double MEGABYTE = 1024.0d * 1024.0d;

    static final String REPLY_URI = "aeron:udp?endpoint=127.0.0.1:54327";
    static final int REPLY_STREAM_ID = 100;
    private static final String REPLAY_URI = "aeron:udp?endpoint=127.0.0.1:54326";
    private static final String PUBLISH_URI = "aeron:ipc?endpoint=127.0.0.1:54325";
    //        +"|" + CommonContext.TERM_LENGTH_PARAM_NAME + "=4194304|" + CommonContext.MTU_LENGTH_PARAM_NAME + "=4096";
    private static final int PUBLISH_STREAM_ID = 1;
    private static final int MAX_FRAGMENT_SIZE = 1024;
    private static final int MESSAGE_COUNT = 2000000;
    private static final int TEST_DURATION_SEC = 30;

    private final MediaDriver.Context driverCtx = new MediaDriver.Context();
    private final Archiver.Context archiverCtx = new Archiver.Context();
    private Aeron publishingClient;
    private Archiver archiver;
    private MediaDriver driver;
    private UnsafeBuffer buffer = new UnsafeBuffer(new byte[4096]);
    private File archiveDir;
    private final long recordingId = 0;
    private long remaining;
    private int fragmentCount;
    private int[] fragmentLength;
    private long totalDataLength;
    private long totalRecordingLength;
    private long recorded;
    private volatile int lastTermId = -1;
    private Throwable trackerError;
    private Random rnd = new Random();
    private long seed;

    @Rule
    public TestWatcher testWatcher = new TestWatcher()
    {
        protected void failed(final Throwable t, final Description description)
        {
            System.err.println(
                "ArchiveAndReplaySystemTest failed with random seed: " + ArchiveReplayLoadTest.this.seed);
        }
    };
    private Subscription reply;
    private long correlationId;
    private long joinPosition;
    private FragmentHandler validateFragmentHandler = this::validateFragment;

    @Before
    public void setUp() throws Exception
    {
        seed = System.nanoTime();
        rnd.setSeed(seed);

        driverCtx
            .termBufferSparseFile(true)
            .threadingMode(ThreadingMode.DEDICATED)
            .errorHandler(LangUtil::rethrowUnchecked)
            .dirsDeleteOnStart(true);

        driver = MediaDriver.launch(driverCtx);
        archiveDir = TestUtil.makeTempDir();
        archiverCtx.archiveDir(archiveDir)
            .forceDataWrites(false)
            .threadingMode(ArchiverThreadingMode.DEDICATED);

        archiver = Archiver.launch(archiverCtx);
        println("Archiver started, dir: " + archiverCtx.archiveDir().getAbsolutePath());
        publishingClient = Aeron.connect();
    }

    @After
    public void closeEverything() throws Exception
    {
        CloseHelper.close(publishingClient);
        CloseHelper.close(archiver);
        CloseHelper.close(driver);

        if (null != archiveDir)
        {
            IoUtil.delete(archiveDir, false);
        }

        driverCtx.deleteAeronDirectory();
    }

    @Test(timeout = 180000)
    public void replay() throws IOException, InterruptedException
    {
        try (Publication archiverServiceRequest = publishingClient.addPublication(
                archiverCtx.controlChannel(), archiverCtx.controlStreamId());
             Subscription archiverNotifications = publishingClient.addSubscription(
                archiverCtx.recordingEventsChannel(), archiverCtx.recordingEventsStreamId()))
        {
            final ArchiveClient client = new ArchiveClient(archiverServiceRequest, archiverNotifications);

            awaitPublicationIsConnected(archiverServiceRequest);
            awaitSubscriptionIsConnected(archiverNotifications);
            println("Archive service connected");

            reply = publishingClient.addSubscription(REPLY_URI, REPLY_STREAM_ID);
            client.connect(REPLY_URI, REPLY_STREAM_ID);
            awaitSubscriptionIsConnected(reply);
            println("Client connected");

            final long startRecordingCorrelationId = this.correlationId++;
            waitFor(() -> client.startRecording(PUBLISH_URI, PUBLISH_STREAM_ID, startRecordingCorrelationId));
            println("Recording requested");
            waitForOk(client, reply, startRecordingCorrelationId);

            final Publication publication = publishingClient.addPublication(PUBLISH_URI, PUBLISH_STREAM_ID);
            awaitPublicationIsConnected(publication);
            final int messageCount = prepAndSendMessages(client, publication);

            assertNull(trackerError);
            println("All data arrived");

            println("Request stop recording");
            final long requestStopCorrelationId = this.correlationId++;
            waitFor(() -> client.stopRecording(recordingId, requestStopCorrelationId));
            waitForOk(client, reply, requestStopCorrelationId);
            final long duration = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(TEST_DURATION_SEC);
            int i = 0;
            while (System.currentTimeMillis() < duration)
            {
                final long start = System.currentTimeMillis();
                validateReplay(client, messageCount);
                final long delta = System.currentTimeMillis() - start;
                final double rate = (totalDataLength * 1000.0) / (MEGABYTE * delta);
                System.out.printf("Replay[%d] rate %.02f MB/s %n", ++i, rate);
            }
        }
    }

    private int prepAndSendMessages(
        final ArchiveClient client,
        final Publication publication)
        throws InterruptedException
    {
        final int messageCount = MESSAGE_COUNT;
        fragmentLength = new int[messageCount];
        for (int i = 0; i < messageCount; i++)
        {
            final int messageLength = 64 + rnd.nextInt(MAX_FRAGMENT_SIZE - 64) - DataHeaderFlyweight.HEADER_LENGTH;
            fragmentLength[i] = messageLength + DataHeaderFlyweight.HEADER_LENGTH;
            totalDataLength += fragmentLength[i];
        }

        final CountDownLatch waitForData = new CountDownLatch(1);
        System.out.printf("Sending %,d messages with a total length of %,d bytes %n", messageCount, totalDataLength);

        trackRecordingProgress(client, waitForData);
        publishDataToRecorded(publication, messageCount);
        waitForData.await();

        return messageCount;
    }

    private void publishDataToRecorded(final Publication publication, final int messageCount)
    {
        final int positionBitsToShift = Integer.numberOfTrailingZeros(publication.termBufferLength());
        joinPosition = publication.position();
        final int initialTermOffset = LogBufferDescriptor.computeTermOffsetFromPosition(
            joinPosition, positionBitsToShift);

        buffer.setMemory(0, 1024, (byte)'z');
        buffer.putStringAscii(32, "TEST");

        for (int i = 0; i < messageCount; i++)
        {
            final int dataLength = fragmentLength[i] - DataHeaderFlyweight.HEADER_LENGTH;
            buffer.putInt(0, i);
            printf("Sending: index=%d length=%d %n", i, dataLength);
            offer(publication, buffer, dataLength);
        }

        final int lastTermOffset = LogBufferDescriptor.computeTermOffsetFromPosition(
            publication.position(), positionBitsToShift);
        final int termIdFromPosition = LogBufferDescriptor.computeTermIdFromPosition(
            publication.position(), positionBitsToShift, publication.initialTermId());
        totalRecordingLength =
            (termIdFromPosition - publication.initialTermId()) * publication.termBufferLength() +
                (lastTermOffset - initialTermOffset);

        assertThat(publication.position() - joinPosition, is(totalRecordingLength));
        lastTermId = termIdFromPosition;
    }

    private void validateReplay(final ArchiveClient client, final int messageCount)
    {
        final int replayStreamId = (int)correlationId;

        try (Subscription replay = publishingClient.addSubscription(REPLAY_URI, replayStreamId))
        {
            // request replay
            final long correlationId = this.correlationId++;

            TestUtil.waitFor(() -> client.replay(
                recordingId,
                joinPosition,
                totalRecordingLength,
                REPLAY_URI,
                replayStreamId,
                correlationId));

            awaitSubscriptionIsConnected(replay);

            fragmentCount = 0;
            remaining = totalDataLength;

            while (fragmentCount < messageCount && remaining > 0 && !replay.isClosed() && !replay.hasNoImages())
            {
                replay.poll(validateFragmentHandler, 128);
            }

            assertThat(fragmentCount, is(messageCount));
            assertThat(remaining, is(0L));
        }
    }

    private void validateFragment(
        final DirectBuffer buffer,
        final int offset, final int length,
        @SuppressWarnings("unused") final Header header)
    {
        assertThat(length, is(fragmentLength[fragmentCount] - DataHeaderFlyweight.HEADER_LENGTH));
        assertThat(buffer.getInt(offset), is(fragmentCount));
        assertThat(buffer.getByte(offset + 4), is((byte)'z'));
        remaining -= fragmentLength[fragmentCount];
        fragmentCount++;
    }

    private void trackRecordingProgress(final ArchiveClient client, final CountDownLatch waitForData)
    {
        final Thread t = new Thread(
            () ->
            {
                try
                {
                    recorded = 0;
                    long start = System.currentTimeMillis();
                    long startBytes = remaining;
                    // each message is fragmentLength[fragmentCount]
                    while (lastTermId == -1 || recorded < totalRecordingLength)
                    {
                        TestUtil.waitFor(() -> (client.pollEvents(new RecordingEventsListener()
                        {
                            public void onStart(
                                final long recordingId,
                                final long joinPosition,
                                final int sessionId,
                                final int streamId,
                                final String channel,
                                final String sourceIdentity)
                            {
                            }

                            public void onProgress(
                                final long recordingId0,
                                final long joinPosition,
                                final long position)
                            {
                                assertThat(recordingId0, is(recordingId));
                                recorded = position - joinPosition;
                                printf("a=%d total=%d %n", recorded, totalRecordingLength);
                            }

                            public void onStop(final long recordingId0, final long joinPosition, final long endPosition)
                            {
                            }
                        }, 1)) != 0);


                        final long end = System.currentTimeMillis();
                        final long deltaTime = end - start;
                        if (deltaTime > TIMEOUT)
                        {
                            start = end;
                            final long deltaBytes = remaining - startBytes;
                            startBytes = remaining;
                            final double rate = ((deltaBytes * 1000.0) / deltaTime) / MEGABYTE;
                            printf("Archive reported rate: %.02f MB/s %n", rate);
                        }
                    }
                    final long end = System.currentTimeMillis();
                    final long deltaTime = end - start;

                    final long deltaBytes = remaining - startBytes;
                    final double rate = ((deltaBytes * 1000.0) / deltaTime) / MEGABYTE;
                    printf("Archive reported rate: %.02f MB/s %n", rate);
                }
                catch (final Throwable throwable)
                {
                    trackerError = throwable;
                }

                waitForData.countDown();
            });

        t.setDaemon(true);
        t.start();
    }
}
