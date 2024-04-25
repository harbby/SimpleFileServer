package com.github.harbby;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class FileUploadHandler
        implements HttpHandler
{
    @Override
    public void handle(HttpExchange exchange)
            throws IOException
    {
        if (!"POST".equals(exchange.getRequestMethod())) {
            // 返回405 Method Not Allowed，只接受POST请求
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        String query = exchange.getRequestURI().getQuery();
        String path = query.split("&path=")[1];
        File savePath = new File(".", path);
        if (!savePath.exists() || !savePath.isDirectory()) {
            exchange.sendResponseHeaders(404, 0);
            exchange.getResponseBody().write("upload dir not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        // 获取请求体的输入流
        try (InputStream inputStream = exchange.getRequestBody()) {
            int fileSize = Integer.parseInt(exchange.getRequestHeaders().getFirst("Content-length"));
            // 解析 multipart/form-data 请求体
            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            Map<String, Long> parts = saveMultipart(inputStream, contentType, savePath);

            // 返回响应
            String response = "File upload successful!";
            exchange.sendResponseHeaders(200, response.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        }
        catch (Exception e) {
            e.printStackTrace();
            // 返回500 Internal Server Error
            exchange.sendResponseHeaders(500, -1);
        }
    }

    private String readFileName(EndFlagInputStream reader)
            throws IOException
    {
        byte[] bytes = IOUtils.readAllBytes(reader);
        String content = new String(bytes, StandardCharsets.UTF_8);
        if (content.startsWith("Content-Disposition:")) {
            return extractFileName(content);
        }
        else {
            throw new IllegalStateException("Protocol error, cannot get file name");
        }
    }

    private String readFileType(EndFlagInputStream reader)
            throws IOException
    {
        byte[] bytes = IOUtils.readAllBytes(reader);
        String line = new String(bytes, StandardCharsets.UTF_8);
        if (line.startsWith("Content-Type:")) {
            return line.substring("Content-Type:".length()).trim();
        }
        else {
            throw new IllegalStateException("Protocol error, cannot get file type");
        }
    }

    private void readTwoBytes(InputStream in, byte[] twoByteArray)
            throws IOException
    {
        IOUtils.readFully(in, twoByteArray, 0, 2);
    }

    private void checkAndSkip(InputStream in, String skip)
            throws IOException
    {
        for (byte b : skip.getBytes(StandardCharsets.UTF_8)) {
            if (b != (byte) in.read()) {
                throw new IllegalStateException(String.format("skip %s failed", skip));
            }
        }
    }

    private Map<String, Long> saveMultipart(InputStream inputStream, String contentType, File savePath)
            throws IOException
    {
        Map<String, Long> parts = new HashMap<>();
        String boundary = extractBoundary(contentType);
        String flag = "--" + boundary;

        checkAndSkip(inputStream, flag);
        // skip \r\n
        checkAndSkip(inputStream, "\r\n");

        byte[] twoByteArray = new byte[2];
        try (BufferedInputStream bin = new BufferedInputStream(inputStream);
                EndFlagInputStream endFlagInputStream = new EndFlagInputStream(bin)) {
            while (true) {
                // 读取 part 头部
                endFlagInputStream.initEndWith("\r\n".getBytes());
                String partName = readFileName(endFlagInputStream);
                endFlagInputStream.initEndWith("\r\n".getBytes());
                String fileType = readFileType(endFlagInputStream);
                // skip \r\n
                checkAndSkip(bin, "\r\n");

                File uploadDir = new File(savePath, "__upload__");
                if (!uploadDir.exists()) {
                    uploadDir.mkdir();
                }
                File saveFile = new File(uploadDir, partName);
                try (FileOutputStream out = new FileOutputStream(saveFile, false)) {
                    endFlagInputStream.initEndWith(("\r\n" + flag).getBytes(StandardCharsets.UTF_8));
                    long size = IOUtils.transferTo(endFlagInputStream, out);
                    System.out.printf("upload file %s [%s] [size=%s] to %s\n", partName, fileType, size, saveFile);
                    parts.put(partName, size);
                }

                readTwoBytes(bin, twoByteArray);
                switch (new String(twoByteArray, StandardCharsets.UTF_8)) {
                    case "\r\n":
                        break; // this found next part file
                    case "--":
                        return parts;  //this is finish
                    default:
                        throw new IllegalStateException("unknow next flag");
                }
            }
        }
    }

    private String extractBoundary(String contentTypeHeader)
    {
        String[] parts = contentTypeHeader.split(";");
        for (String part : parts) {
            if (part.trim().startsWith("boundary=")) {
                return part.trim().substring("boundary=".length());
            }
        }
        return null;
    }

    private String extractFileName(String contentDisposition)
            throws UnsupportedEncodingException
    {
        // 根据 Content-Disposition 中的 filename 提取文件名
        String[] parts = contentDisposition.split("filename=\"");
        assert parts.length == 2;
        String fileURLName = parts[1].substring(0, parts[1].length() - 1);
        String filename = java.net.URLDecoder.decode(fileURLName, "utf8");
        return filename;
    }
}
