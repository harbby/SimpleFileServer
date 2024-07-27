package com.github.harbby;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
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

        String template = loadResourceTemplate();
        String notFoundError = loadFileNotFoundTemplate();
        InetSocketAddress address = new InetSocketAddress(port);
        HttpServer server = HttpServer.create(address, 0);
        MailHandler mailHandler = new MailHandler();
        FileUploadHandler fileUploadHandler = new FileUploadHandler();

        server.createContext("/", new FileDownloadHandler(template, notFoundError, mailHandler, fileUploadHandler));
        server.setExecutor(Executors.newFixedThreadPool(parallelism)); // creates a default executor
        String hostName = address.getHostName();
        System.out.printf("Serving HTTP on %s port %s (http://%s:%s/) ...%n", hostName, port, hostName, port);
        server.start();
    }

    private static String loadFileNotFoundTemplate()
            throws IOException
    {
        try (InputStream in = SimpleServer.class.getResourceAsStream("/FileNotFound.template")) {
            requireNonNull(in, "resource file FileNotFound.template not found");
            return new String(IOUtils.readAllBytes(in), StandardCharsets.UTF_8);
        }
    }

    public static String loadResourceTemplate()
            throws IOException
    {
        try (InputStream in = SimpleServer.class.getResourceAsStream("/response.template")) {
            requireNonNull(in, "resource file response.template not found");
            return new String(IOUtils.readAllBytes(in), StandardCharsets.UTF_8);
        }
    }
}