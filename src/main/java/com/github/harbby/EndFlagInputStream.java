package com.github.harbby;

import java.io.IOException;
import java.io.InputStream;

import static java.util.Objects.requireNonNull;

public class EndFlagInputStream
        extends InputStream
{
    private byte[] endFlag;
    private final InputStream in;
    private int index;
    private boolean matched = false;

    EndFlagInputStream(InputStream in)
    {
        this.in = in;
    }

    public void initEndWith(byte[] endFlag)
    {
        this.endFlag = requireNonNull(endFlag, "endFlag is null");
        this.matched = false;
        this.index = 0;
    }

    @Override
    public int read()
            throws IOException
    {
        if (matched) {
            return -1;
        }
        int i;
        in.mark(endFlag.length - index);
        while ((i = in.read()) != -1) {
            byte b = (byte) i;
            if (endFlag[index] == b) {
                index++;
                if (index == endFlag.length) {
                    matched = true;
                    return -1;
                }
            }
            else {
                index = 0;
                in.reset();
                return in.read();
            }
        }
        matched = false;
        return -1;
    }

    @Override
    public int read(byte[] arr, int off, int len)
            throws IOException
    {
        for (int i = 0; i < len; i++) {
            int b = this.read();
            if (b == -1) {
                return i == 0 ? -1 : i;
            }
            arr[off + i] = (byte) b;
        }
        return len;
    }

//    public static void main(String[] args)
//            throws IOException
//    {
//        String input = "12345678aaa----aaa";
//        EndFlagInputStream inputStream = new EndFlagInputStream(
//                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
//                "aaa".getBytes(StandardCharsets.UTF_8));
//        byte[] bytes = IOUtils.readAllBytes(inputStream);
//        System.out.println(new String(bytes));
//        inputStream.reMatch();
//        byte[] bytes2 = IOUtils.readAllBytes(inputStream);
//        System.out.println(new String(bytes2));
//    }
}
