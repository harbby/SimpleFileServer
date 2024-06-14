package com.github.harbby;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class MailHandler
        implements HttpHandler
{
    private final BlockingQueue<Message> buffer = new LinkedBlockingQueue<>(256);

    static class Message
    {
        private final String message;
        private final Date sendtime;
        private final String hostname;

        private Message(String message, Date sendtime, String hostname)
        {
            this.message = message;
            this.sendtime = sendtime;
            this.hostname = hostname;
        }

        public static Message of(String message, String hostname)
        {
            return new Message(message, new Date(), hostname);
        }
    }

    @Override
    public void handle(HttpExchange exchange)
            throws IOException
    {
        if (!"POST".equals(exchange.getRequestMethod())) {
            // 返回405 Method Not Allowed，只接受POST请求
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        byte[] bytes = IOUtils.readAllBytes(exchange.getRequestBody());
        if (bytes.length > 0) {
            String str = new String(bytes, StandardCharsets.UTF_8);
            Message message = Message.of(str, exchange.getRemoteAddress().getHostName());
            while (!buffer.offer(message)) {
                buffer.poll();
            }
            String history = getAllHistory();
            try (OutputStream out = exchange.getResponseBody()) {
                byte[] resBody = history.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, resBody.length);
                out.write(resBody);
            }
        }
        else {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().close();
        }
    }

    String getAllHistory()
    {
        StringBuilder builder = new StringBuilder();
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        buffer.stream().sorted((m1, m2) -> m2.sendtime.compareTo(m1.sendtime)).forEach(message -> {
            builder.append(String.format("<li>%s at %s<pre>%s</pre></li><hr>\n",
                    message.hostname, simpleDateFormat.format(message.sendtime), message.message));
        });
        return builder.toString();
    }
}
