package com.github.harbby;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import sun.misc.Unsafe;

import java.io.File;
import java.io.FileInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static java.util.Objects.requireNonNull;

public class SimpleServer
{
    public static void main(String[] args)
            throws Exception
    {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        int parallelism = args.length > 1 ? Integer.parseInt(args[1]) : 100;

        Enumeration<NetworkInterface> enumeration = NetworkInterface.getNetworkInterfaces();
        while (enumeration.hasMoreElements()) {
            NetworkInterface networkInterface = enumeration.nextElement();
            Enumeration<InetAddress> addressEnumeration = networkInterface.getInetAddresses();
            while (addressEnumeration.hasMoreElements()) {
                InetAddress addr = addressEnumeration.nextElement();
                if (addr instanceof Inet4Address) {
                    System.out.println("your ipv4 address probably is " + addr.getHostAddress());
                }
            }
        }

        String template = loadTemplate("response.template");
        String notFoundError = loadTemplate("FileNotFound.template");
        InetSocketAddress address = new InetSocketAddress(port);
        HttpServer server = HttpServer.create(address, 0);
        server.createContext("/", new FileDownloadHandler(template, notFoundError));
        server.setExecutor(Executors.newFixedThreadPool(parallelism)); // creates a default executor
        String hostName = address.getHostName();
        System.out.printf("Serving HTTP on %s port %s (http://%s:%s/) ...%n", hostName, port, hostName, port);
        server.start();
    }

    private static String loadTemplate(String path)
            throws IOException
    {
        try (InputStream in = SimpleServer.class.getClassLoader().getResourceAsStream(path)) {
            requireNonNull(in, "resource file " + path + " not found");
            return new String(IOUtils.readAllBytes(in), StandardCharsets.UTF_8);
        }
    }

    static class FileDownloadHandler
            implements HttpHandler
    {
        private final String template;
        private final String notFoundError;
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

        public FileDownloadHandler(String template, String notFoundError)
        {
            this.template = template;
            this.notFoundError = notFoundError;
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
                    count = IOUtils.transferTo(fileInputStream, os);
                }
                else {
                    // doZeroCopy
                    SocketChannel channel = getSocketChannel(os);
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
                    String name = file.isDirectory() ? file.getName() + "/" : file.getName();
                    builder.append(String.format("<li><a href=\"%s\">%s</a></li>\n", file.getPath().substring(1), name));
                }
            }
            String response = template.replace("${files}", builder);
            response = response.replace("${path}", t.getRequestURI().getPath());
            byte[] rs = response.getBytes(StandardCharsets.UTF_8);
            t.sendResponseHeaders(200, rs.length);
            try (OutputStream os = t.getResponseBody()) {
                os.write(rs);
            }
        }

        private void logInfo(HttpExchange t, int status)
        {
            String resPath = t.getRequestURI().getPath();
            System.out.printf("%s - [%s] - %s - %s - %s%n",
                    t.getRemoteAddress().getAddress().getHostAddress(),
                    LocalDateTime.now(), t.getRequestMethod(), resPath, status);
        }

        private void send404(HttpExchange t)
                throws IOException
        {
            logInfo(t, 404);
            t.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            t.getResponseHeaders().set("Server", "SimpleHTTPFileServer Java");
            byte[] bytes = notFoundError.getBytes(StandardCharsets.UTF_8);
            t.sendResponseHeaders(404, bytes.length);
            t.getResponseBody().write(bytes);
            t.getResponseBody().close();
        }

        @Override
        public void handle(HttpExchange t)
                throws IOException
        {
            URI requestURI = t.getRequestURI();
            String resPath = requestURI.getPath();
            File inputPath = new File("." + resPath);
            if (!inputPath.exists()) {
                send404(t);
                return;
            }
            logInfo(t, 200);
            if (inputPath.isFile()) {
                downloadFile(t, inputPath);
            }
            else {
                String query = requestURI.getQuery();
                if (query != null) {
                    for (String q : query.split("&")) {
                        if ("zip".equals(q.trim())) {
                            downloadDir(t, inputPath);
                            return;
                        }
                    }
                }
                t.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                t.getResponseHeaders().set("Server", "SimpleHTTPFileServer Java");
                listDirs(t, inputPath);
            }
        }
    }
}