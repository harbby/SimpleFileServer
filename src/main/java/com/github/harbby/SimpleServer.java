package com.github.harbby;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
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
        InetSocketAddress address = new InetSocketAddress(port);
        HttpServer server = HttpServer.create(address, 0);
        server.createContext("/", new FileDownloadHandler(template));
        server.setExecutor(Executors.newFixedThreadPool(parallelism)); // creates a default executor
        String hostName = address.getHostName();
        System.out.println(String.format("Serving HTTP on %s port %s (http://%s:%s/) ...", hostName, port, hostName, port));
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

        public FileDownloadHandler(String template)
        {
            this.template = template;
        }

        private void downloadFile(HttpExchange t, File inputPath)
                throws IOException
        {
            t.sendResponseHeaders(200, inputPath.length());
            try (OutputStream os = t.getResponseBody();
                    FileInputStream fileInputStream = new FileInputStream(inputPath)) {
                IOUtils.transferTo(fileInputStream, os);
            }
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
            if (inputPath.isFile()) {
                downloadFile(t, inputPath);
            }
            else {
                t.getResponseHeaders().set("Content-Type", "text/html;charset=utf-8");
                listDirs(t, inputPath);
            }
        }
    }
}