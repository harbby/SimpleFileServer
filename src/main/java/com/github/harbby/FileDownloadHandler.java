package com.github.harbby;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.File;
import java.io.FileInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
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

    private static VarHandle WRAPPED, ROS, OUT, CHANNEL;

    private static VarHandle lookupVarHandle(Class<?> jClass, String fieldName, Class<?> type)
            throws IllegalAccessException, NoSuchFieldException
    {
        return MethodHandles.privateLookupIn(jClass, MethodHandles.lookup()).findVarHandle(
                jClass,
                fieldName,
                type
        );
    }

    static {
        try {
            WRAPPED = lookupVarHandle(Class.forName("sun.net.httpserver.HttpExchangeImpl"),
                    "impl",
                    Class.forName("sun.net.httpserver.ExchangeImpl")
            );
            ROS = lookupVarHandle(
                    Class.forName("sun.net.httpserver.ExchangeImpl"),
                    "ros",
                    java.io.OutputStream.class
            );
            OUT = lookupVarHandle(
                    java.io.FilterOutputStream.class,
                    "out",
                    java.io.OutputStream.class
            );
            CHANNEL = lookupVarHandle(
                    Class.forName("sun.net.httpserver.Request$WriteStream"),
                    "channel",
                    java.nio.channels.SocketChannel.class
            );
            System.out.println("enable zero copy mode succeed.");
        }
        catch (ClassNotFoundException | NoSuchFieldException | ClassCastException | IllegalAccessException e) {
            e.printStackTrace();
            System.out.println("enable zero copy mode failed.");
        }
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
        long count = 0;
        if (CHANNEL == null) {
            try (OutputStream os = t.getResponseBody();
                    FileInputStream fileInputStream = new FileInputStream(inputPath)) {
                t.sendResponseHeaders(200, fileLength == 0 ? -1 : fileLength);
                t.getResponseHeaders().add("Content-Type", "application/octet-stream");
                logInfo(t, "DOWNLOAD_FILE_BY_BIO", 200);
                count = IOUtils.transferTo(fileInputStream, os);
            }
        }
        else {
            try (SocketChannel channel = getSocketChannel(t);
                    FileInputStream fileInputStream = new FileInputStream(inputPath)) {
                // doZeroCopy
                String statusLine = "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: %s\r\n\r\n".formatted(fileLength);
                channel.write(ByteBuffer.wrap(statusLine.getBytes(StandardCharsets.UTF_8)));
                logInfo(t, "DOWNLOAD_FILE_BY_ZeroCopy", 200);
                count = IOUtils.transferTo(fileInputStream.getChannel(), 0, fileLength, channel);
            }
        }
        if (count != fileLength) {
            System.out.println("download file " + inputPath.getPath() +
                    " failed. transferTo count is " + count + " but file length is " + fileLength);
        }
    }

    private SocketChannel getSocketChannel(HttpExchange httpExchange)
    {
        Object wrapped = WRAPPED.get(httpExchange);
        Object outStream = ROS.get(wrapped);
        if (outStream instanceof FilterOutputStream) {
            outStream = OUT.get(outStream);
        }
        return (SocketChannel) CHANNEL.get(outStream);
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
    {
        try (t) {
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
            t.sendResponseHeaders(405, 0);
            t.getResponseBody().close();
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }
}
