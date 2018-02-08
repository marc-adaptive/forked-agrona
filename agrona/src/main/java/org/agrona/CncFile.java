/*
 * Copyright 2014-2018 Real Logic Ltd.
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
package org.agrona;

import org.agrona.concurrent.EpochClock;
import org.agrona.concurrent.UnsafeBuffer;

import java.io.File;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import static java.nio.file.StandardOpenOption.*;
import static org.agrona.BitUtil.SIZE_OF_INT;
import static org.agrona.BitUtil.SIZE_OF_LONG;

/**
 * Interface for managing Command-n-Control files.
 *
 * The assumptions are: (1) the version field is an int in size, (2) the timestamp field is a long in size,
 * and (3) the version field comes before the timestamp field.
 */
public class CncFile implements AutoCloseable
{
    private final int versionFieldOffset;
    private final int timestampFieldOffset;

    private final File cncDir;
    private final File cncFile;
    private final MappedByteBuffer mappedCncBuffer;
    private final UnsafeBuffer cncBuffer;

    private volatile boolean isClosed = false;

    /**
     * Create a CnC directory and file if none present. Checking if an active CnC file exists and is active. Old CnC
     * file is deleted and recreated if not active.
     *
     * Total length of CnC file will be mapped until {@link #close()} is called.
     *
     * @param directory             for the CnC file
     * @param filename              of the CnC file
     * @param warnIfDirectoryExists for logging purposes
     * @param dirDeleteOnStart      if desired
     * @param versionFieldOffset    to use for version field access
     * @param timestampFieldOffset  to use for timestamp field access
     * @param totalFileLength       to allocate when creating new CnC file
     * @param timeoutMs             for the activity check (in milliseconds)
     * @param epochClock            to use for time checks
     * @param versionCheck          to use for existing CnC file and version field
     * @param logger                to use to signal progress or null
     */
    public CncFile(
        final File directory,
        final String filename,
        final boolean warnIfDirectoryExists,
        final boolean dirDeleteOnStart,
        final int versionFieldOffset,
        final int timestampFieldOffset,
        final int totalFileLength,
        final long timeoutMs,
        final EpochClock epochClock,
        final IntConsumer versionCheck,
        final Consumer<String> logger)
    {
        validateOffsets(versionFieldOffset, timestampFieldOffset);

        ensureDirectoryExists(
            directory,
            filename,
            warnIfDirectoryExists,
            dirDeleteOnStart,
            versionFieldOffset,
            timestampFieldOffset,
            timeoutMs,
            epochClock,
            versionCheck,
            logger);

        this.cncDir = directory;
        this.cncFile = new File(directory, filename);
        this.mappedCncBuffer = mapNewFile(cncFile, totalFileLength);
        this.cncBuffer = new UnsafeBuffer(mappedCncBuffer);
        this.versionFieldOffset = versionFieldOffset;
        this.timestampFieldOffset = timestampFieldOffset;
    }

    /**
     * Create a CnC file if none present. Checking if an active CnC file exists and is active. Existing CnC file
     * is used if not active.
     *
     * Total length of CnC file will be mapped until {@link #close()} is called.
     *
     * @param cncFile               to use
     * @param shouldPreExist        or not
     * @param versionFieldOffset    to use for version field access
     * @param timestampFieldOffset  to use for timestamp field access
     * @param totalFileLength       to allocate when creating new CnC file
     * @param timeoutMs             for the activity check (in milliseconds)
     * @param epochClock            to use for time checks
     * @param versionCheck          to use for existing CnC file and version field
     * @param logger                to use to signal progress or null
     */

    public CncFile(
        final File cncFile,
        final boolean shouldPreExist,
        final int versionFieldOffset,
        final int timestampFieldOffset,
        final int totalFileLength,
        final long timeoutMs,
        final EpochClock epochClock,
        final IntConsumer versionCheck,
        final Consumer<String> logger)
    {
        validateOffsets(versionFieldOffset, timestampFieldOffset);

        this.cncDir = cncFile.getParentFile();
        this.cncFile = cncFile;
        this.mappedCncBuffer = mapNewOrExistingCncFile(
            cncFile,
            shouldPreExist,
            versionFieldOffset,
            timestampFieldOffset,
            totalFileLength,
            timeoutMs,
            epochClock,
            versionCheck,
            logger);

        this.cncBuffer = new UnsafeBuffer(mappedCncBuffer);
        this.versionFieldOffset = versionFieldOffset;
        this.timestampFieldOffset = timestampFieldOffset;
    }

    /**
     * Map a pre-existing CnC file if one present and is active.
     *
     * Total length of CnC file will be mapped until {@link #close()} is called.
     *
     * @param directory             for the CnC file
     * @param filename              of the CnC file
     * @param versionFieldOffset    to use for version field access
     * @param timestampFieldOffset  to use for timestamp field access
     * @param timeoutMs             for the activity check (in milliseconds) and for how long to wait for file to exist
     * @param epochClock            to use for time checks
     * @param versionCheck          to use for existing CnC file and version field
     * @param logger                to use to signal progress or null
     */
    public CncFile(
        final File directory,
        final String filename,
        final int versionFieldOffset,
        final int timestampFieldOffset,
        final long timeoutMs,
        final EpochClock epochClock,
        final IntConsumer versionCheck,
        final Consumer<String> logger)
    {
        validateOffsets(versionFieldOffset, timestampFieldOffset);

        this.cncDir = directory;
        this.cncFile = new File(directory, filename);
        this.mappedCncBuffer = mapExistingCncFile(
            cncFile, versionFieldOffset, timestampFieldOffset, timeoutMs, epochClock, versionCheck, logger);
        this.cncBuffer = new UnsafeBuffer(mappedCncBuffer);
        this.versionFieldOffset = versionFieldOffset;
        this.timestampFieldOffset = timestampFieldOffset;
    }

    /**
     * Manage a CnC file given a mapped file and offsets of version and timestamp.
     *
     * If mappedCncBuffer is not null, then it will be unmapped upon {@link #close()}.
     *
     * @param mappedCncBuffer      for the CnC fields
     * @param versionFieldOffset   for the version field
     * @param timestampFieldOffset for the timestamp field
     */
    public CncFile(
        final MappedByteBuffer mappedCncBuffer,
        final int versionFieldOffset,
        final int timestampFieldOffset)
    {
        validateOffsets(versionFieldOffset, timestampFieldOffset);

        this.cncDir = null;
        this.cncFile = null;
        this.mappedCncBuffer = mappedCncBuffer;
        this.cncBuffer = new UnsafeBuffer(mappedCncBuffer);
        this.versionFieldOffset = versionFieldOffset;
        this.timestampFieldOffset = timestampFieldOffset;
    }

    /**
     * Manage a CnC file given a buffer and offsets of version and timestamp.
     *
     * @param cncBuffer            for the CnC fields
     * @param versionFieldOffset   for the version field
     * @param timestampFieldOffset for the timestamp field
     */
    public CncFile(
        final UnsafeBuffer cncBuffer,
        final int versionFieldOffset,
        final int timestampFieldOffset)
    {
        validateOffsets(versionFieldOffset, timestampFieldOffset);

        this.cncDir = null;
        this.cncFile = null;
        this.mappedCncBuffer = null;
        this.cncBuffer = cncBuffer;
        this.versionFieldOffset = versionFieldOffset;
        this.timestampFieldOffset = timestampFieldOffset;
    }

    public boolean isClosed()
    {
        return isClosed;
    }

    public void close()
    {
        if (!isClosed)
        {
            if (null != mappedCncBuffer)
            {
                IoUtil.unmap(mappedCncBuffer);
            }

            isClosed = true;
        }
    }

    public void signalCncReady(final int version)
    {
        cncBuffer.putIntOrdered(versionFieldOffset, version);
    }

    public int versionVolatile()
    {
        return cncBuffer.getIntVolatile(versionFieldOffset);
    }

    public int versionWeak()
    {
        return cncBuffer.getInt(versionFieldOffset);
    }

    public void timestampOrdered(final long timestamp)
    {
        cncBuffer.putLongOrdered(timestampFieldOffset, timestamp);
    }

    public long timestampVolatile()
    {
        return cncBuffer.getLongVolatile(timestampFieldOffset);
    }

    public long timestampWeak()
    {
        return cncBuffer.getLong(timestampFieldOffset);
    }

    public void deleteDirectory(final boolean ignoreFailures)
    {
        IoUtil.delete(cncDir, ignoreFailures);
    }

    public File cncDirectory()
    {
        return cncDir;
    }

    public File cncFile()
    {
        return cncFile;
    }

    public MappedByteBuffer mappedByteBuffer()
    {
        return mappedCncBuffer;
    }

    public UnsafeBuffer buffer()
    {
        return cncBuffer;
    }

    public static void ensureDirectoryExists(
        final File directory,
        final String filename,
        final boolean warnIfDirectoryExists,
        final boolean dirDeleteOnStart,
        final int versionFieldOffset,
        final int timestampFieldOffset,
        final long timeoutMs,
        final EpochClock epochClock,
        final IntConsumer versionCheck,
        final Consumer<String> logger)
    {
        final File cncFile =  new File(directory, filename);

        if (directory.isDirectory())
        {
            if (warnIfDirectoryExists && null != logger)
            {
                logger.accept("WARNING: " + directory + " already exists.");
            }

            if (!dirDeleteOnStart)
            {
                final int offset = Math.min(versionFieldOffset, timestampFieldOffset);
                final int length = Math.max(versionFieldOffset, timestampFieldOffset) + SIZE_OF_LONG - offset;
                final MappedByteBuffer cncByteBuffer = mapExistingFile(cncFile, logger, offset, length);

                try
                {
                    if (isActive(
                        cncByteBuffer,
                        epochClock,
                        timeoutMs,
                        versionFieldOffset,
                        timestampFieldOffset,
                        versionCheck,
                        logger))
                    {
                        throw new IllegalStateException("Active CnC file detected");
                    }
                }
                finally
                {
                    IoUtil.unmap(cncByteBuffer);
                }
            }

            IoUtil.delete(directory, false);
        }

        IoUtil.ensureDirectoryExists(directory, directory.toString());
    }

    public static MappedByteBuffer mapExistingCncFile(
        final File cncFile,
        final int versionFieldOffset,
        final int timestampFieldOffset,
        final long timeoutMs,
        final EpochClock epochClock,
        final IntConsumer versionCheck,
        final Consumer<String> logger)
    {
        final long startTimeMs = epochClock.time();

        while (true)
        {
            while (!cncFile.exists())
            {
                if (epochClock.time() > (startTimeMs + timeoutMs))
                {
                    throw new IllegalStateException("CnC file not found: " + cncFile.getName());
                }

                sleep(16);
            }

            final MappedByteBuffer cncByteBuffer = mapExistingFile(cncFile, logger);
            final UnsafeBuffer cncBuffer = new UnsafeBuffer(cncByteBuffer);

            int cncVersion;
            while (0 == (cncVersion = cncBuffer.getIntVolatile(versionFieldOffset)))
            {
                if (epochClock.time() > (startTimeMs + timeoutMs))
                {
                    throw new IllegalStateException("CnC file is created but not initialised.");
                }

                sleep(1);
            }

            versionCheck.accept(cncVersion);

            while (0 == cncBuffer.getLongVolatile(timestampFieldOffset))
            {
                if (epochClock.time() > (startTimeMs + timeoutMs))
                {
                    throw new IllegalStateException("No non-0 timestamp detected.");
                }

                sleep(1);
            }

            return cncByteBuffer;
        }
    }

    public static MappedByteBuffer mapNewOrExistingCncFile(
        final File cncFile,
        final boolean shouldPreExist,
        final int versionFieldOffset,
        final int timestampFieldOffset,
        final long totalFileLength,
        final long timeoutMs,
        final EpochClock epochClock,
        final IntConsumer versionCheck,
        final Consumer<String> logger)
    {
        MappedByteBuffer cncByteBuffer = null;

        try (FileChannel channel = FileChannel.open(cncFile.toPath(), CREATE, READ, WRITE, SPARSE))
        {
            cncByteBuffer = channel.map(FileChannel.MapMode.READ_WRITE, 0, totalFileLength);
            final UnsafeBuffer cncBuffer = new UnsafeBuffer(cncByteBuffer);

            if (shouldPreExist)
            {
                final int cncVersion = cncBuffer.getIntVolatile(versionFieldOffset);

                if (null != logger)
                {
                    logger.accept("INFO: CnC file exists: " + cncFile);
                }

                versionCheck.accept(cncVersion);

                final long timestamp = cncBuffer.getLongVolatile(timestampFieldOffset);
                final long now = epochClock.time();
                final long timestampAge = now - timestamp;

                if (null != logger)
                {
                    logger.accept("INFO: heartbeat is (ms): " + timestampAge);
                }

                if (timestampAge < timeoutMs)
                {
                    throw new IllegalStateException("Active CnC file detected");
                }
            }
        }
        catch (final Exception ex)
        {
            if (null != cncByteBuffer)
            {
                IoUtil.unmap(cncByteBuffer);
            }

            throw new RuntimeException(ex);
        }

        return cncByteBuffer;
    }

    public static MappedByteBuffer mapExistingFile(
        final File cncFile, final Consumer<String> logger, final long offset, final long length)
    {
        if (cncFile.exists())
        {
            if (null != logger)
            {
                logger.accept("INFO: CnC file exists: " + cncFile);
            }

            return IoUtil.mapExistingFile(cncFile, cncFile.toString(), offset, length);
        }

        return null;
    }

    public static MappedByteBuffer mapExistingFile(final File cncFile, final Consumer<String> logger)
    {
        if (cncFile.exists())
        {
            if (null != logger)
            {
                logger.accept("INFO: CnC file exists: " + cncFile);
            }

            return IoUtil.mapExistingFile(cncFile, cncFile.toString());
        }

        return null;
    }

    public static MappedByteBuffer mapNewFile(final File cncFile, final long length)
    {
        return IoUtil.mapNewFile(cncFile, length);
    }

    public static boolean isActive(
        final MappedByteBuffer cncByteBuffer,
        final EpochClock epochClock,
        final long timeoutMs,
        final int versionFieldOffset,
        final int timestampFieldOffset,
        final IntConsumer versionCheck,
        final Consumer<String> logger)
    {
        if (null == cncByteBuffer)
        {
            return false;
        }

        final UnsafeBuffer cncBuffer = new UnsafeBuffer(cncByteBuffer);

        final long startTimeMs = epochClock.time();
        int cncVersion;
        while (0 == (cncVersion = cncBuffer.getIntVolatile(versionFieldOffset)))
        {
            if (epochClock.time() > (startTimeMs + timeoutMs))
            {
                throw new IllegalStateException("CnC file is created but not initialised.");
            }

            sleep(1);
        }

        versionCheck.accept(cncVersion);

        final long timestamp = cncBuffer.getLongVolatile(timestampFieldOffset);
        final long now = epochClock.time();
        final long timestampAge = now - timestamp;

        if (null != logger)
        {
            logger.accept("INFO: heartbeat is (ms): " + timestampAge);
        }

        return timestampAge <= timeoutMs;
    }

    private static void validateOffsets(final int versionFieldOffset, final int timestampFieldOffset)
    {
        if ((versionFieldOffset + SIZE_OF_INT) > timestampFieldOffset)
        {
            throw new IllegalArgumentException("version field must precede the timestamp field");
        }
    }

    static void sleep(final long durationMs)
    {
        try
        {
            Thread.sleep(durationMs);
        }
        catch (final InterruptedException ignore)
        {
            Thread.interrupted();
        }
    }
}
