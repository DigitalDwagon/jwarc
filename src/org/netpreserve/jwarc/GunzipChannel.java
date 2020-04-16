/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright (C) 2018 National Library of Australia and the jwarc contributors
 */

package org.netpreserve.jwarc;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ReadableByteChannel;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import java.util.zip.ZipException;

class GunzipChannel implements ReadableByteChannel {
    private static final int FHCRC = 2;
    private static final int FEXTRA = 4;
    private static final int FNAME = 8;
    private static final int FCOMMENT = 16;
    private static final int CM_DEFLATE = GzipChannel.CM_DEFLATE;
    private static final short GZIP_MAGIC = GzipChannel.GZIP_MAGIC;

    private final ReadableByteChannel channel;
    private final ByteBuffer buffer;
    private final Inflater inflater = new Inflater(true);
    private long inputPosition;
    private boolean seenHeader;
    private CRC32 crc; //= new CRC32();

    public GunzipChannel(ReadableByteChannel channel, ByteBuffer buffer) throws IllegalArgumentException {
        this.channel = channel;
        this.buffer = buffer;
        if (!buffer.hasArray()) {
            throw new IllegalArgumentException("ByteBuffer must be array-backed and writable");
        }
        buffer.order(ByteOrder.LITTLE_ENDIAN);
    }

    @Override
    public int read(ByteBuffer dest) throws IOException {
        if (!seenHeader) {
            if (!readHeader()) {
                return -1;
            }
            seenHeader = true;
        }

        if (inflater.needsInput()) {
            if (!readAtLeast(1)) {
                throw new EOFException("unexpected end of gzip stream");
            }
            inflater.setInput(buffer.array(), buffer.arrayOffset() + buffer.position(), buffer.remaining());
        }

        try {
            int n = inflater.inflate(dest.array(), dest.arrayOffset() + dest.position(), dest.remaining());
            if (crc != null) {
                crc.update(dest.array(), dest.arrayOffset() + dest.position(), n);
            }
            dest.position(dest.position() + n);

            int newBufferPosition = buffer.limit() - inflater.getRemaining();
            inputPosition += newBufferPosition - buffer.position();
            buffer.position(newBufferPosition);

            if (inflater.finished()) {
                readTrailer();
                inflater.reset();
                if (crc != null) {
                    crc.reset();
                }
                seenHeader = false;
            }
            return n;
        } catch (DataFormatException e) {
            throw new ZipException(e.getMessage());
        }
    }

    private void readTrailer() throws IOException {
        if (!readAtLeast(8)) {
            throw new EOFException("reading gzip trailer");
        }
        long expectedCrc = buffer.getInt() & 0xffffffffL;
        int isize = buffer.getInt();
        inputPosition += 8;

        if ((isize & 0xfffffffffL) != (inflater.getBytesWritten() & 0xfffffffffL)) {
            throw new ZipException("gzip uncompressed size mismatch");
        }
        if (crc != null && expectedCrc != crc.getValue()) {
            throw new ZipException("bad gzip crc32: expected " + Long.toHexString(expectedCrc) + " actual " +
                    Long.toHexString(crc.getValue()));
        }
    }

    private boolean readHeader() throws IOException {
        if (!readAtLeast(10)) {
            if (buffer.hasRemaining()) {
                throw new EOFException("partial gzip header");
            }
            return false;
        }
        short magic = buffer.getShort();
        if (magic != GZIP_MAGIC) {
            throw new ZipException("not in gzip format (magic=" + Integer.toHexString(magic) + ")");
        }
        int cm = buffer.get();
        if (cm != CM_DEFLATE) {
            throw new ZipException("unsupported compression method: " + cm);
        }
        int flg = buffer.get();
        int mtime = buffer.getInt();
        int xfl = buffer.get();
        int os = buffer.get();
        inputPosition += 10;
        if ((flg & FEXTRA) == FEXTRA) {
            if (!readAtLeast(2)) {
                throw new EOFException("reading gzip extra");
            }
            inputPosition += 2;
            int xlen = buffer.getShort();
            ByteBuffer extra = ByteBuffer.allocate(xlen);
            while (extra.hasRemaining()) {
                for (int i = 0; i < xlen; i++) {
                    if (!readAtLeast(1)) {
                        throw new EOFException("reading gzip extra");
                    }
                    extra.put(buffer.get());
                }
                inputPosition += xlen;
            }
        }
        if ((flg & FNAME) == FNAME) {
            do {
                if (!readAtLeast(1)) {
                    throw new EOFException("reading gzip name");
                }
                inputPosition++;
            } while (buffer.get() != '\0');
        }
        if ((flg & FCOMMENT) == FCOMMENT) {
            do {
                if (!readAtLeast(1)) {
                    throw new EOFException("reading gzip comment");
                }
                inputPosition++;
            } while (buffer.get() != '\0');
        }
        if ((flg & FHCRC) == FHCRC) {
            if (!readAtLeast(2)) {
                throw new EOFException("reading gzip header crc");
            }
            int crc16 = buffer.getShort();
            inputPosition += 2;
        }
        return true;
    }

    private boolean readAtLeast(int n) throws IOException {
        while (buffer.remaining() < n) {
            buffer.compact();
            int actual = channel.read(buffer);
            buffer.flip();
            if (actual < 0) return false;
        }
        return true;
    }

    @Override
    public boolean isOpen() {
        return channel.isOpen();
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }

    public long inputPosition() {
        return inputPosition;
    }
}
