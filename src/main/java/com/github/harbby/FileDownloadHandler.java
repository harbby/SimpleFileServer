package com.github.harbby;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import sun.misc.Unsafe;

import java.io.File;
import java.io.FileInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class FileDownloadHandler
        implements HttpHandler
{
    private final String template;
    private final String notFoundError;
    private final MailHandler mailHandler;
    private final FileUploadHandler fileUploadHandler;

    private static final long wrappedFieldOffset;
    private static final long outFieldOffset;
    private static final long channelFieldOffset;

    static {
        Unsafe unsafe = IOUtils.getUnsafe();
        long wrappedFieldOffset0 = -1;
        long outFieldOffset0 = -1;
        long channelFieldOffset0 = -1;
        try {
            Class<?> tClass = Class.forName("sun.net.httpserver.PlaceholderOutputStream");
            wrappedFieldOffset0 = unsafe.objectFieldOffset(tClass.getDeclaredField("wrapped"));
            outFieldOffset0 = unsafe.objectFieldOffset(FilterOutputStream.class.getDeclaredField("out"));
            tClass = Class.forName("sun.net.httpserver.Request$WriteStream");
            channelFieldOffset0 = unsafe.objectFieldOffset(tClass.getDeclaredField("channel"));
            System.out.println("enable zero copy mode succeed.");
        }
        catch (ClassNotFoundException | NoSuchFieldException | ClassCastException e) {
            System.out.println("enable zero copy mode failed.");
            e.printStackTrace();
        }
        wrappedFieldOffset = wrappedFieldOffset0;
        outFieldOffset = outFieldOffset0;
        channelFieldOffset = channelFieldOffset0;
    }

    public FileDownloadHandler(String template, String notFoundError, MailHandler mailHandler, FileUploadHandler fileUploadHandler)
    {
        this.template = template;
        this.notFoundError = notFoundError;
        this.mailHandler = mailHandler;
        this.fileUploadHandler = fileUploadHandler;
    }

    private void downloadDir(HttpExchange t, File inputPath)
            throws IOException
    {
        t.sendResponseHeaders(200, 0);
        try (OutputStream out = t.getResponseBody();
                ZipOutputStream zout = new ZipOutputStream(out)) {
            downloadDir0(zout, inputPath, "");
        }
    }

    private void downloadDir0(ZipOutputStream zout, File inputDir, String parent)
            throws IOException
    {
        File[] files = inputDir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            String path = parent + "/" + file.getName();
            if (file.isFile()) {
                ZipEntry zipEntry = new ZipEntry(path);
                zipEntry.setSize(file.length());
                zipEntry.setTime(file.lastModified());
                zout.putNextEntry(zipEntry);
                try (FileInputStream in = new FileInputStream(file)) {
                    IOUtils.transferTo(in, zout);
                }
                zout.closeEntry();
            }
            else if (file.isDirectory()) {
                ZipEntry zipEntry = new ZipEntry(path + "/");
                zout.putNextEntry(zipEntry);
                downloadDir0(zout, file, path);
                zout.closeEntry();
            }
        }
    }

    private void downloadFile(HttpExchange t, File inputPath)
            throws IOException
    {
        long fileLength = inputPath.length();
        t.sendResponseHeaders(200, fileLength == 0 ? -1 : fileLength);
        long count;
        try (OutputStream os = t.getResponseBody();
                FileInputStream fileInputStream = new FileInputStream(inputPath)) {
            if (channelFieldOffset == -1) {
                logInfo(t, "DOWNLOAD_FILE_BY_BIO", 200);
                count = IOUtils.transferTo(fileInputStream, os);
            }
            else {
                // doZeroCopy
                SocketChannel channel = getSocketChannel(os);
                logInfo(t, "DOWNLOAD_FILE_BY_ZeroCopy", 200);
                count = IOUtils.transferTo(fileInputStream.getChannel(), 0, fileLength, channel);
            }
        }
        if (count != fileLength) {
            System.out.println("download file " + inputPath.getPath() +
                    " failed. transferTo count is " + count + " but file length is " + fileLength);
        }
    }

    private SocketChannel getSocketChannel(OutputStream os)
    {
        Unsafe unsafe = IOUtils.getUnsafe();
        Object wrapped = unsafe.getObject(os, wrappedFieldOffset);
        Object outStream = unsafe.getObject(wrapped, outFieldOffset);
        return (SocketChannel) unsafe.getObject(outStream, channelFieldOffset);
    }

    private void listDirs(HttpExchange t, File inputPath)
            throws IOException
    {
        StringBuilder builder = new StringBuilder();
        File[] files = inputPath.listFiles();
        if (files != null && files.length > 0) {
            Arrays.sort(files, (f1, f2) -> {
                int cmp = Boolean.compare(f2.isDirectory(), f1.isDirectory());
                return cmp == 0 ? f1.getName().compareTo(f2.getName()) : cmp;
            });
            for (File file : files) {
                String encodeName = URLEncoder.encode(file.getName(), "UTF-8").replace("+", "%20");
                String name = file.getName();
                if (file.isDirectory()) {
                    encodeName += "/";
                    name += "/";
                }
                builder.append(String.format("<li><a href=\"%s\">%s</a></li>\n", encodeName, name));
            }
        }
        String response = template.replace("${files}", builder);
        response = response.replace("${path}", t.getRequestURI().getPath());
        response = response.replace("${upath}", t.getRequestURI().getRawPath());
        String zipPath = t.getRequestURI().getRawPath();
        zipPath = zipPath.substring(0, zipPath.length() - 1) + ".zip?&download_dir";
        response = response.replace("${zip_path}", zipPath);
        response = response.replace("${history}", mailHandler.getAllHistory());
        byte[] rs = response.getBytes(StandardCharsets.UTF_8);
        t.sendResponseHeaders(200, rs.length);
        try (OutputStream os = t.getResponseBody()) {
            os.write(rs);
        }
    }

    private void logInfo(HttpExchange t, String action, int status)
    {
        String resPath = t.getRequestURI().getPath();
        System.out.printf("%s - [%s] - %s - %s - %s - %s%n",
                t.getRemoteAddress().getAddress().getHostAddress(),
                LocalDateTime.now(), t.getRequestMethod(), action, resPath, status);
    }

    private void send404(HttpExchange t)
            throws IOException
    {
        logInfo(t, "UNKNOWN", 404);
        t.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        t.getResponseHeaders().set("Server", "SimpleHTTPFileServer Java");
        byte[] bytes = notFoundError.getBytes(StandardCharsets.UTF_8);
        t.sendResponseHeaders(404, bytes.length);
        t.getResponseBody().write(bytes);
        t.getResponseBody().close();
    }

    private void doGet(HttpExchange t)
            throws IOException
    {
        URI requestURI = t.getRequestURI();
        String resPath = requestURI.getPath();
        String query = requestURI.getQuery();
        if ("&download_dir".equals(query) && resPath.endsWith(".zip")) {
            resPath = resPath.substring(0, resPath.length() - ".zip".length());
            File inputPath = new File(".", resPath);
            if (!inputPath.exists()) {
                send404(t);
                return;
            }
            logInfo(t, "DOWNLOAD_DIR", 200);
            downloadDir(t, new File(".", resPath));
            return;
        }

        File inputPath = new File(".", resPath);
        if (!inputPath.exists()) {
            send404(t);
            return;
        }
        if (inputPath.isFile()) {
            downloadFile(t, inputPath);
        }
        else {
            t.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            t.getResponseHeaders().set("Server", "SimpleHTTPFileServer Java");
            logInfo(t, "LIST_DIR", 200);
            listDirs(t, inputPath);
        }
    }

    @Override
    public void handle(HttpExchange t)
            throws IOException
    {
        String method = t.getRequestMethod();
        switch (method) {
            case "GET":
                doGet(t);
                return;
            case "POST":
                URI requestURI = t.getRequestURI();
                String query = requestURI.getQuery();
                if ("&upload".equals(query)) {
                    fileUploadHandler.handle(t);
                    return;
                }
                else if ("&mail".equals(query)) {
                    mailHandler.handle(t);
                    return;
                }
        }
        t.sendResponseHeaders(405, -1);
        t.getResponseBody().close();
    }
}
