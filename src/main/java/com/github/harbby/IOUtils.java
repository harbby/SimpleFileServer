package com.github.harbby;

import sun.misc.Unsafe;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static java.util.Objects.requireNonNull;

public class IOUtils
{
    private static final int DEFAULT_BUFFER_SIZE = 8192;
    private static final int MAX_BUFFER_SIZE = Integer.MAX_VALUE - 8;

    public static long transferTo(InputStream in, OutputStream out)
            throws IOException
    {
        Objects.requireNonNull(out, "out");
        long transferred = 0;
        byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];
        int read;
        while ((read = in.read(buffer, 0, DEFAULT_BUFFER_SIZE)) >= 0) {
            out.write(buffer, 0, read);
            transferred += read;
        }
        return transferred;
    }

    public static long transferTo(FileChannel in, long position, long count, WritableByteChannel out)
            throws IOException
    {
        Objects.requireNonNull(out, "out");
        long transferred = position;
        long read;
        while (transferred < count && (read = in.transferTo(transferred, count - transferred, out)) >= 0) {
            transferred += read;
        }
        return transferred - position;
    }

    public static byte[] readAllBytes(InputStream in)
            throws IOException
    {
        return readNBytes(in, Integer.MAX_VALUE);
    }

    public static byte[] readNBytes(InputStream in, int len)
            throws IOException
    {
        if (len < 0) {
            throw new IllegalArgumentException("len < 0");
        }

        List<byte[]> bufs = null;
        byte[] result = null;
        int total = 0;
        int remaining = len;
        int n;
        do {
            byte[] buf = new byte[Math.min(remaining, DEFAULT_BUFFER_SIZE)];
            int nread = 0;

            // read to EOF which may read more or less than buffer size
            while ((n = in.read(buf, nread,
                    Math.min(buf.length - nread, remaining))) > 0) {
                nread += n;
                remaining -= n;
            }

            if (nread > 0) {
                if (MAX_BUFFER_SIZE - total < nread) {
                    throw new OutOfMemoryError("Required array size too large");
                }
                total += nread;
                if (result == null) {
                    result = buf;
                }
                else {
                    if (bufs == null) {
                        bufs = new ArrayList<>();
                        bufs.add(result);
                    }
                    bufs.add(buf);
                }
            }
            // if the last call to read returned -1 or the number of bytes
            // requested have been read then break
        }
        while (n >= 0 && remaining > 0);

        if (bufs == null) {
            if (result == null) {
                return new byte[0];
            }
            return result.length == total ?
                    result : Arrays.copyOf(result, total);
        }

        result = new byte[total];
        int offset = 0;
        remaining = total;
        for (byte[] b : bufs) {
            int count = Math.min(b.length, remaining);
            System.arraycopy(b, 0, result, offset, count);
            offset += count;
            remaining -= count;
        }

        return result;
    }

    public static void readFully(InputStream in, byte[] b)
            throws IOException
    {
        readFully(in, b, 0, b.length);
    }

    public static void readFully(InputStream in, byte[] b, int off, int len)
            throws IOException
    {
        if (len < 0) {
            throw new IndexOutOfBoundsException();
        }
        int n = 0;
        while (n < len) {
            int count = in.read(b, off + n, len - n);
            if (count < 0) {
                throw new EOFException("should be read " + len + " bytes, but read " + n);
            }
            n += count;
        }
    }

    private static final Unsafe unsafe;

    static {
        Unsafe obj;
        try {
            Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            obj = (sun.misc.Unsafe) requireNonNull(unsafeField.get(null));
        }
        catch (Exception cause) {
            throw new IllegalStateException(cause);
        }
        unsafe = obj;
    }

    public static Unsafe getUnsafe()
    {
        return unsafe;
    }
}
