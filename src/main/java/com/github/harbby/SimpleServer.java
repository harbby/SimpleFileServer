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
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Enumeration;
import java.util.concurrent.Executors;

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

        private void downloadFile(HttpExchange t, File inputPath)
                throws IOException
        {
            t.sendResponseHeaders(200, inputPath.length());
            try (OutputStream os = t.getResponseBody();
                    FileInputStream fileInputStream = new FileInputStream(inputPath)) {
                if (channelFieldOffset == -1) {
                    IOUtils.transferTo(fileInputStream, os);
                }
                else {
                    // doZeroCopy
                    SocketChannel channel = getSocketChannel(os);
                    fileInputStream.getChannel().transferTo(0, inputPath.length(), channel);
                }
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
            if (files != null) {
                for (File file : files) {
                    String name = file.isDirectory() ? file.getName() + "/" : file.getName();
                    builder.append(String.format("<li><a href=\"%s\">%s</a></li>\n", name, name));
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

        @Override
        public void handle(HttpExchange t)
                throws IOException
        {
            File inputPath = new File("." + t.getRequestURI().getPath());
            String message = inputPath.exists() ? "200" : "404 - File NotFound";
            System.out.printf("%s - [%s] - %s - %s%n",
                    t.getRemoteAddress().getAddress().getHostAddress(),
                    LocalDateTime.now(), t.getRequestMethod(), message);
            if (!inputPath.exists()) {
                t.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                t.getResponseHeaders().set("Server", "SimpleHTTPFileServer Java");

                byte[] bytes = notFoundError.getBytes(StandardCharsets.UTF_8);
                t.sendResponseHeaders(404, bytes.length);
                t.getResponseBody().write(bytes);
                t.getResponseBody().close();
            }
            else if (inputPath.isFile()) {
                downloadFile(t, inputPath);
            }
            else {
                t.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                t.getResponseHeaders().set("Server", "SimpleHTTPFileServer Java");
                listDirs(t, inputPath);
            }
        }
    }
}