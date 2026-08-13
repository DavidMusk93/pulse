package com.bytedance.pulse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.util.CharsetUtil;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.GlobalEventExecutor;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Flow;
import java.util.function.Consumer;
import java.util.function.BiFunction;

/**
 * Non-blocking public transport. Business HTTP handlers remain isolated on a
 * loopback-only server while long-lived streams stay on Netty event loops.
 */
public class CoordinatorHttpServer {
    private static final int MAX_REQUEST_BYTES = 64 * 1024 * 1024;
    private static final int MAX_COMPLETION_ENCODINGS = 16;
    private static final int MAX_CACHED_OUTPUT_BYTES = 8 * 1024 * 1024;
    private static final long OUTPUT_STALL_TIMEOUT_MS = 30_000;
    private static final AttributeKey<Runnable> PENDING_OUTPUT_WRITE =
            AttributeKey.valueOf("pulse.pendingOutputWrite");
    private static final AttributeKey<Long> OUTPUT_STALL_DEADLINE =
            AttributeKey.valueOf("pulse.outputStallDeadline");

    private final CoordinatorService service;
    private final LegacyCoordinatorHttpServer legacy;
    private final ControlStreamHub controlStreamHub;
    private final String bindHost;
    private final int requestedPort;
    private final BiFunction<String, URI, URI> taskRouteResolver;
    private final HttpClient proxyClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();
    private final ThreadPoolExecutor outputExecutor = new ThreadPoolExecutor(
            2,
            4,
            30,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(128),
            runnable -> {
                Thread thread = new Thread(runnable, "pulse-output-encode");
                thread.setDaemon(true);
                return thread;
            });
    private final Map<String, CompletableFuture<byte[]>> completionEncodings =
            new ConcurrentHashMap<>();
    private final ChannelGroup channels =
            new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel publicChannel;

    public CoordinatorHttpServer(CoordinatorService service, int port) throws IOException {
        this(service, "127.0.0.1", port);
    }

    public CoordinatorHttpServer(CoordinatorService service, String bindHost, int port) throws IOException {
        this(service, bindHost, port, PeerForwarder.fromEnvironment(service.coordinatorId()));
    }

    public CoordinatorHttpServer(
            CoordinatorService service,
            String bindHost,
            int port,
            EventBusService eventBusService) throws IOException {
        this(
                service,
                bindHost,
                port,
                PeerForwarder.fromEnvironment(service.coordinatorId()),
                LegacyCoordinatorHttpServer::defaultTaskRouteUri,
                LegacyCoordinatorHttpServer.peerUrlsFromEnvironment(),
                eventBusService);
    }

    CoordinatorHttpServer(
            CoordinatorService service,
            String bindHost,
            int port,
            PeerForwarder peerForwarder) throws IOException {
        this(service, bindHost, port, peerForwarder, LegacyCoordinatorHttpServer::defaultTaskRouteUri);
    }

    CoordinatorHttpServer(
            CoordinatorService service,
            String bindHost,
            int port,
            PeerForwarder peerForwarder,
            BiFunction<String, URI, URI> taskRouteResolver) throws IOException {
        this(service, bindHost, port, peerForwarder, taskRouteResolver,
                LegacyCoordinatorHttpServer.peerUrlsFromEnvironment());
    }

    CoordinatorHttpServer(
            CoordinatorService service,
            String bindHost,
            int port,
            PeerForwarder peerForwarder,
            BiFunction<String, URI, URI> taskRouteResolver,
            List<String> metricPeerUrls) throws IOException {
        this(service, bindHost, port, peerForwarder, taskRouteResolver, metricPeerUrls, null);
    }

    CoordinatorHttpServer(
            CoordinatorService service,
            String bindHost,
            int port,
            PeerForwarder peerForwarder,
            BiFunction<String, URI, URI> taskRouteResolver,
            List<String> metricPeerUrls,
            EventBusService eventBusService) throws IOException {
        this.service = service;
        this.bindHost = bindHost;
        this.requestedPort = port;
        this.taskRouteResolver = taskRouteResolver;
        this.legacy = new LegacyCoordinatorHttpServer(
                service,
                "127.0.0.1",
                0,
                peerForwarder::forward,
                taskRouteResolver,
                metricPeerUrls,
                eventBusService);
        this.controlStreamHub = new ControlStreamHub(
                service,
                eventBusService,
                JsonSupport.objectMapper(),
                legacy::taskSnapshotForControl);
    }

    public synchronized void start() {
        if (publicChannel != null) {
            return;
        }
        legacy.start();
        controlStreamHub.start();
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();
        try {
            publicChannel = new ServerBootstrap()
                    .group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childOption(
                            ChannelOption.WRITE_BUFFER_WATER_MARK,
                            new WriteBufferWaterMark(64 * 1024, 256 * 1024))
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel channel) {
                            channels.add(channel);
                            channel.pipeline()
                                    .addLast(new HttpServerCodec())
                                    .addLast(new HttpObjectAggregator(MAX_REQUEST_BYTES))
                                    .addLast(new PublicRequestHandler());
                        }
                    })
                    .bind(new InetSocketAddress(bindHost, requestedPort))
                    .syncUninterruptibly()
                    .channel();
            channels.add(publicChannel);
        } catch (RuntimeException exception) {
            stop();
            throw exception;
        }
    }

    public synchronized void stop() {
        if (publicChannel != null) {
            publicChannel.close().syncUninterruptibly();
            publicChannel = null;
        }
        controlStreamHub.close();
        channels.close().syncUninterruptibly();
        outputExecutor.shutdownNow();
        completionEncodings.clear();
        if (workerGroup != null) {
            workerGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
            workerGroup = null;
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
            bossGroup = null;
        }
        legacy.stop();
    }

    public int port() {
        Channel channel = publicChannel;
        if (channel == null) {
            return requestedPort;
        }
        return ((InetSocketAddress) channel.localAddress()).getPort();
    }

    private final class PublicRequestHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
        @Override
        protected void channelRead0(ChannelHandlerContext context, FullHttpRequest request) {
            if (request.method().equals(HttpMethod.GET)
                    && writeTaskOutputStreamIfMatched(context, request)) {
                return;
            }
            String path = new QueryStringDecoder(request.uri()).path();
            if (request.method().equals(HttpMethod.GET)
                    && path.equals("/api/control/stream")) {
                writeControlStream(context, request);
                return;
            }
            if (request.method().equals(HttpMethod.GET)
                    && isRetiredControlStream(path)) {
                writeError(context, HttpResponseStatus.GONE, "use /api/control/stream");
                return;
            }
            proxy(context, request);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
            context.close();
        }

        @Override
        public void channelWritabilityChanged(ChannelHandlerContext context) {
            if (context.channel().isWritable()) {
                Runnable pending = context.channel()
                        .attr(PENDING_OUTPUT_WRITE)
                        .getAndSet(null);
                context.channel().attr(OUTPUT_STALL_DEADLINE).set(null);
                if (pending != null) {
                    context.executor().execute(pending);
                }
            }
            context.fireChannelWritabilityChanged();
        }
    }

    private boolean writeTaskOutputStreamIfMatched(
            ChannelHandlerContext context,
            FullHttpRequest request) {
        String[] parts = new QueryStringDecoder(request.uri()).path().split("/", -1);
        if (parts.length == 7
                && "api".equals(parts[1])
                && "agents".equals(parts[2])
                && "tasks".equals(parts[4])
                && "output_stream".equals(parts[6])) {
            String agentId = URLDecoder.decode(parts[3], StandardCharsets.UTF_8);
            String taskId = URLDecoder.decode(parts[5], StandardCharsets.UTF_8);
            TaskStreamSnapshot stream = service.taskSnapshot(agentId).outputStreams().stream()
                    .filter(candidate -> candidate.taskId().equals(taskId))
                    .findFirst()
                    .orElse(null);
            if (stream == null) {
                return writeRemoteTaskOutputStream(context, request, agentId);
            }
            String output = stream.output() == null ? "" : stream.output();
            String requestedOffset = outputOffsetValue(request);
            encodeOutput(context, null, output, outputBytes -> {
                int offset = outputOffset(requestedOffset, outputBytes);
                writeSseHeaders(context);
                writeSse(context, String.valueOf(offset), "task.output_start",
                        JsonSupport.objectMapper().valueToTree(Map.of(
                                "task_id", taskId,
                                "agent_id", agentId,
                                "offset", offset,
                                "stream_bytes", stream.streamBytes())).toString());
                writeRunningOutputChunk(
                        context, agentId, taskId, outputBytes, offset);
            });
            return true;
        }
        if (parts.length != 8
                || !"api".equals(parts[1])
                || !"agents".equals(parts[2])
                || !"tasks".equals(parts[4])
                || !"completions".equals(parts[5])
                || !"output_stream".equals(parts[7])) {
            return false;
        }
        String agentId = URLDecoder.decode(parts[3], StandardCharsets.UTF_8);
        String taskId = URLDecoder.decode(parts[6], StandardCharsets.UTF_8);
        TaskResult task = service.taskCompletion(agentId, taskId).orElse(null);
        if (task == null) {
            return writeRemoteTaskOutputStream(context, request, agentId);
        }
        String output = task.output() == null ? "" : task.output();
        String requestedOffset = outputOffsetValue(request);
        String encodingKey = task.agentId() + '\n'
                + task.taskId() + '\n' + task.outputSha256();
        encodeOutput(context, encodingKey, output, outputBytes -> {
            int offset = outputOffset(requestedOffset, outputBytes);
            writeSseHeaders(context);
            writeSse(context, String.valueOf(offset), "completion.output_start",
                    JsonSupport.objectMapper().valueToTree(Map.of(
                            "task_id", task.taskId(),
                            "agent_id", task.agentId(),
                            "output_bytes", task.outputBytes(),
                            "output_chars", output.length(),
                            "output_sha256", task.outputSha256(),
                            "offset", offset)).toString());
            writeOutputChunk(context, task, output, outputBytes, offset);
        });
        return true;
    }

    private void encodeOutput(
            ChannelHandlerContext context,
            String cacheKey,
            String output,
            Consumer<byte[]> consumer) {
        CompletableFuture<byte[]> encoded;
        try {
            if (cacheKey == null) {
                encoded = CompletableFuture.supplyAsync(
                        () -> output.getBytes(StandardCharsets.UTF_8),
                        outputExecutor);
            } else {
                encoded = completionEncodings.computeIfAbsent(
                        cacheKey,
                        ignored -> CompletableFuture.supplyAsync(
                                () -> output.getBytes(StandardCharsets.UTF_8),
                                outputExecutor));
            }
        } catch (RejectedExecutionException exception) {
            writeError(context, HttpResponseStatus.SERVICE_UNAVAILABLE,
                    "output encoder is saturated");
            return;
        }
        encoded.whenComplete((bytes, failure) -> context.executor().execute(() -> {
            if (!context.channel().isActive()) {
                return;
            }
            if (failure != null) {
                if (cacheKey != null) {
                    completionEncodings.remove(cacheKey, encoded);
                }
                writeError(context, HttpResponseStatus.SERVICE_UNAVAILABLE,
                        failure.getMessage());
                return;
            }
            if (cacheKey != null) {
                trimCompletionEncodings(cacheKey, bytes.length);
            }
            consumer.accept(bytes);
        }));
    }

    private void trimCompletionEncodings(String currentKey, int outputBytes) {
        if (outputBytes > MAX_CACHED_OUTPUT_BYTES) {
            completionEncodings.remove(currentKey);
            return;
        }
        if (completionEncodings.size() <= MAX_COMPLETION_ENCODINGS) {
            return;
        }
        completionEncodings.keySet().stream()
                .filter(key -> !key.equals(currentKey))
                .limit(completionEncodings.size() - MAX_COMPLETION_ENCODINGS)
                .forEach(completionEncodings::remove);
    }

    private void writeRunningOutputChunk(
            ChannelHandlerContext context,
            String agentId,
            String taskId,
            byte[] output,
            int offset) {
        if (!context.channel().isActive()) {
            return;
        }
        if (!context.channel().isWritable()) {
            awaitOutputWritability(
                    context,
                    () -> writeRunningOutputChunk(
                            context, agentId, taskId, output, offset));
            return;
        }
        if (offset >= output.length) {
            writeSseChunk(context, String.valueOf(output.length), "task.output_cursor",
                    JsonSupport.objectMapper().valueToTree(Map.of(
                            "task_id", taskId,
                            "agent_id", agentId,
                            "offset", output.length)).toString())
                    .addListener(ignored -> closeSse(context));
            return;
        }
        int nextOffset = nextUtf8Boundary(output, offset, 32 * 1024);
        writeSseChunk(context, String.valueOf(nextOffset), "task.output_chunk",
                JsonSupport.objectMapper().valueToTree(Map.of(
                        "task_id", taskId,
                        "agent_id", agentId,
                        "offset", offset,
                        "next_offset", nextOffset,
                        "chunk", new String(
                                output, offset, nextOffset - offset, StandardCharsets.UTF_8)))
                        .toString())
                .addListener(future -> {
                    if (future.isSuccess()) {
                        context.executor().execute(() -> writeRunningOutputChunk(
                                context, agentId, taskId, output, nextOffset));
                    } else {
                        context.close();
                    }
                });
    }

    private boolean writeRemoteTaskOutputStream(
            ChannelHandlerContext context,
            FullHttpRequest request,
            String agentId) {
        String owner = service.agentCoordinatorId(agentId).orElse("");
        if (owner.isBlank() || owner.equals(service.coordinatorId())) {
            return false;
        }
        URI target = taskRouteResolver.apply(owner, URI.create(request.uri()));
        HttpRequest.Builder builder = HttpRequest.newBuilder(target)
                .timeout(Duration.ofMinutes(5))
                .GET();
        String lastEventId = request.headers().get("Last-Event-ID");
        if (lastEventId != null && !lastEventId.isBlank()) {
            builder.header("Last-Event-ID", lastEventId);
        }
        CompletableFuture<HttpResponse<Flow.Publisher<List<ByteBuffer>>>> responseFuture =
                proxyClient.sendAsync(
                        builder.build(), HttpResponse.BodyHandlers.ofPublisher());
        context.channel().closeFuture().addListener(
                ignored -> responseFuture.cancel(true));
        responseFuture
                .whenComplete((response, failure) -> context.executor().execute(() -> {
                    if (failure != null) {
                        writeError(context, HttpResponseStatus.BAD_GATEWAY, failure.getMessage());
                        return;
                    }
                    bridgePublisherResponse(context, response, true);
                }));
        return true;
    }

    private static boolean isRetiredControlStream(String path) {
        return path.equals("/api/hosts/stream")
                || path.equals("/api/eventbus/stream")
                || path.equals("/api/metrics/stream")
                || path.equals("/api/tasks/stream")
                || (path.startsWith("/api/agents/")
                        && path.endsWith("/tasks/stream"));
    }

    private void writeOutputChunk(
            ChannelHandlerContext context,
            TaskResult task,
            String outputText,
            byte[] output,
            int offset) {
        if (!context.channel().isActive()) {
            return;
        }
        if (!context.channel().isWritable()) {
            awaitOutputWritability(
                    context,
                    () -> writeOutputChunk(
                            context, task, outputText, output, offset));
            return;
        }
        if (offset >= output.length) {
            writeSseChunk(context, String.valueOf(output.length), "completion.output_end",
                    JsonSupport.objectMapper().valueToTree(Map.of(
                            "task_id", task.taskId(),
                            "agent_id", task.agentId(),
                            "output_bytes", task.outputBytes(),
                            "output_chars", outputText.length(),
                            "output_sha256", task.outputSha256(),
                            "done", true)).toString())
                    .addListener(ignored -> closeSse(context));
            return;
        }
        int nextOffset = nextUtf8Boundary(output, offset, 32 * 1024);
        writeSseChunk(context, String.valueOf(nextOffset), "completion.output_chunk",
                JsonSupport.objectMapper().valueToTree(Map.of(
                        "task_id", task.taskId(),
                        "agent_id", task.agentId(),
                        "offset", offset,
                        "next_offset", nextOffset,
                        "chunk", new String(
                                output, offset, nextOffset - offset, StandardCharsets.UTF_8),
                        "done", nextOffset >= output.length)).toString())
                .addListener(future -> {
                    if (future.isSuccess()) {
                        context.executor().execute(() ->
                                writeOutputChunk(
                                        context, task, outputText, output, nextOffset));
                    } else {
                        context.close();
                    }
                });
    }

    private static void awaitOutputWritability(
            ChannelHandlerContext context,
            Runnable resume) {
        context.channel().attr(PENDING_OUTPUT_WRITE).set(resume);
        Long existingDeadline = context.channel().attr(OUTPUT_STALL_DEADLINE).get();
        if (existingDeadline != null) {
            return;
        }
        long deadline = System.currentTimeMillis() + OUTPUT_STALL_TIMEOUT_MS;
        context.channel().attr(OUTPUT_STALL_DEADLINE).set(deadline);
        context.executor().schedule(() -> {
            Long currentDeadline = context.channel()
                    .attr(OUTPUT_STALL_DEADLINE)
                    .get();
            if (currentDeadline != null
                    && currentDeadline == deadline
                    && context.channel().attr(PENDING_OUTPUT_WRITE).get() != null) {
                context.close();
            }
        }, OUTPUT_STALL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private static String outputOffsetValue(FullHttpRequest request) {
        String raw = request.headers().get("Last-Event-ID");
        if (raw == null || raw.isBlank()) {
            raw = queryValue(request.uri(), "offset");
        }
        return raw;
    }

    private static int outputOffset(String raw, byte[] output) {
        try {
            int requested = raw == null
                    ? 0
                    : Math.max(0, Math.min(output.length, Integer.parseInt(raw.trim())));
            while (requested > 0
                    && requested < output.length
                    && isUtf8Continuation(output[requested])) {
                requested--;
            }
            return requested;
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static int nextUtf8Boundary(byte[] output, int offset, int maxBytes) {
        int next = Math.min(output.length, offset + maxBytes);
        while (next > offset
                && next < output.length
                && isUtf8Continuation(output[next])) {
            next--;
        }
        return next;
    }

    private static boolean isUtf8Continuation(byte value) {
        return (value & 0xC0) == 0x80;
    }

    private void writeControlStream(
            ChannelHandlerContext context,
            FullHttpRequest request) {
        writeSseHeaders(context);
        controlStreamHub.subscribe(
                context,
                queryList(request.uri(), "clusters"),
                queryList(request.uri(), "agents"),
                "true".equalsIgnoreCase(queryValue(request.uri(), "once")));
    }

    private void proxy(ChannelHandlerContext context, FullHttpRequest request) {
        byte[] body = new byte[request.content().readableBytes()];
        request.content().getBytes(request.content().readerIndex(), body);
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + legacy.port() + request.uri()))
                .timeout(Duration.ofMinutes(5))
                .method(request.method().name(), body.length == 0
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body));
        request.headers().forEach(header -> {
            String name = header.getKey();
            if (!name.equalsIgnoreCase(HttpHeaderNames.HOST.toString())
                    && !name.equalsIgnoreCase(HttpHeaderNames.CONNECTION.toString())
                    && !name.equalsIgnoreCase(HttpHeaderNames.CONTENT_LENGTH.toString())
                    && !name.equalsIgnoreCase(HttpHeaderNames.UPGRADE.toString())
                    && !name.equalsIgnoreCase(HttpHeaderNames.TRANSFER_ENCODING.toString())
                    && !name.equalsIgnoreCase("http2-settings")
                    && !name.equalsIgnoreCase("te")
                    && !name.equalsIgnoreCase("trailer")
                    && !name.equalsIgnoreCase("keep-alive")
                    && !name.toLowerCase(java.util.Locale.ROOT).startsWith("proxy-")) {
                builder.header(name, header.getValue());
            }
        });
        CompletableFuture<HttpResponse<Flow.Publisher<List<ByteBuffer>>>> responseFuture =
                proxyClient.sendAsync(
                        builder.build(), HttpResponse.BodyHandlers.ofPublisher());
        context.channel().closeFuture().addListener(
                ignored -> responseFuture.cancel(true));
        responseFuture
                .whenComplete((response, failure) -> context.executor().execute(() -> {
                    if (failure != null) {
                        writeError(context, HttpResponseStatus.BAD_GATEWAY, failure.getMessage());
                        return;
                    }
                    bridgePublisherResponse(context, response, false);
                }));
    }

    private static void bridgePublisherResponse(
            ChannelHandlerContext context,
            HttpResponse<Flow.Publisher<List<ByteBuffer>>> response,
            boolean outputStream) {
        DefaultHttpResponse outgoing = new DefaultHttpResponse(
                HttpVersion.HTTP_1_1,
                HttpResponseStatus.valueOf(response.statusCode()));
        response.headers().map().forEach((name, values) -> {
            if (!name.equalsIgnoreCase("content-length")
                    && !name.equalsIgnoreCase("transfer-encoding")
                    && !name.equalsIgnoreCase("connection")) {
                outgoing.headers().set(name, values);
            }
        });
        outgoing.headers().set(
                HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
        if (outputStream) {
            if (!outgoing.headers().contains(HttpHeaderNames.CONTENT_TYPE)) {
                outgoing.headers().set(
                        HttpHeaderNames.CONTENT_TYPE,
                        "text/event-stream; charset=utf-8");
            }
            outgoing.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache");
        }
        context.writeAndFlush(outgoing);
        response.body().subscribe(new NettyBodySubscriber(context));
    }

    static void writeSse(
            ChannelHandlerContext context,
            String id,
            String event,
            String data) {
        writeSseChunk(context, id, event, data);
    }

    private static ChannelFuture writeSseChunk(
            ChannelHandlerContext context,
            String id,
            String event,
            String data) {
        String payload = "id: " + id + "\n"
                + "event: " + event + "\n"
                + "retry: 3000\n"
                + "data: " + data + "\n\n";
        return context.writeAndFlush(new DefaultHttpContent(
                Unpooled.copiedBuffer(payload, StandardCharsets.UTF_8)));
    }

    private static void writeSseHeaders(ChannelHandlerContext context) {
        DefaultHttpResponse response = new DefaultHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        response.headers()
                .set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream; charset=utf-8")
                .set(HttpHeaderNames.CACHE_CONTROL, "no-cache")
                .set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE)
                .set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED)
                .set("x-accel-buffering", "no");
        context.writeAndFlush(response);
    }

    static void closeSse(ChannelHandlerContext context) {
        context.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT)
                .addListener(ChannelFutureListener.CLOSE);
    }

    private static void writeError(
            ChannelHandlerContext context,
            HttpResponseStatus status,
            String message) {
        ByteBuf content = Unpooled.copiedBuffer(
                "{\"error\":\"" + String.valueOf(message).replace("\"", "\\\"") + "\"}",
                CharsetUtil.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, status, content);
        response.headers()
                .set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8")
                .setInt(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes());
        context.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    private static String queryValue(String uri, String key) {
        List<String> values = new QueryStringDecoder(uri).parameters().get(key);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private static List<String> queryList(String uri, String key) {
        String value = queryValue(uri, key);
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .distinct()
                .toList();
    }

    private static final class NettyBodySubscriber
            implements Flow.Subscriber<List<ByteBuffer>> {
        private final ChannelHandlerContext context;
        private Flow.Subscription subscription;

        private NettyBodySubscriber(ChannelHandlerContext context) {
            this.context = context;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            context.channel().closeFuture().addListener(
                    ignored -> subscription.cancel());
            subscription.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            int size = buffers.stream().mapToInt(ByteBuffer::remaining).sum();
            ByteBuf content = context.alloc().buffer(size);
            for (ByteBuffer buffer : buffers) {
                content.writeBytes(buffer);
            }
            context.writeAndFlush(new DefaultHttpContent(content))
                    .addListener(future -> {
                        if (future.isSuccess() && context.channel().isActive()) {
                            subscription.request(1);
                        } else {
                            subscription.cancel();
                            context.close();
                        }
                    });
        }

        @Override
        public void onError(Throwable throwable) {
            context.close();
        }

        @Override
        public void onComplete() {
            closeSse(context);
        }
    }

    static ObjectNode hostSummary(ObjectMapper mapper, HostView host) {
        return LegacyCoordinatorHttpServer.hostSummary(mapper, host);
    }

    static Map<String, Object> hostDelta(
            ObjectMapper mapper, List<HostView> previous, List<HostView> current) {
        return LegacyCoordinatorHttpServer.hostDelta(mapper, previous, current);
    }

    static Map<String, Object> hostSummaryDeltaV2(
            ObjectMapper mapper,
            long fromRevision,
            long toRevision,
            List<HostView> previous,
            List<HostView> current) {
        return LegacyCoordinatorHttpServer.hostSummaryDeltaV2(
                mapper, fromRevision, toRevision, previous, current);
    }

    static Map<String, Object> hostSummaryDeltaV2(
            ObjectMapper mapper,
            long fromRevision,
            long toRevision,
            List<HostView> previous,
            List<HostView> current,
            List<String> clusters) {
        return LegacyCoordinatorHttpServer.hostSummaryDeltaV2(
                mapper, fromRevision, toRevision, previous, current, clusters);
    }

    static String routeHost(String coordinatorId) {
        return LegacyCoordinatorHttpServer.routeHost(coordinatorId);
    }

    interface PeerForwarder {
        void forward(HeartbeatRequest request);

        static PeerForwarder noop() {
            return request -> {};
        }

        static PeerForwarder fromEnvironment(String coordinatorId) {
            LegacyCoordinatorHttpServer.PeerForwarder delegate =
                    LegacyCoordinatorHttpServer.PeerForwarder.fromEnvironment(coordinatorId);
            return delegate::forward;
        }
    }

    static final class HttpPeerForwarder implements PeerForwarder {
        private final LegacyCoordinatorHttpServer.HttpPeerForwarder delegate;

        HttpPeerForwarder(String coordinatorId, List<String> peerUrls, Duration timeout) {
            delegate = new LegacyCoordinatorHttpServer.HttpPeerForwarder(
                    coordinatorId, peerUrls, timeout);
        }

        @Override
        public void forward(HeartbeatRequest request) {
            delegate.forward(request);
        }

        HeartbeatForwardRequest toForwardRequest(HeartbeatRequest request) {
            return delegate.toForwardRequest(request);
        }
    }
}
