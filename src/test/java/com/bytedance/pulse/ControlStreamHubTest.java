package com.bytedance.pulse;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.util.ReferenceCountUtil;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ControlStreamHubTest {
    @Test
    void initialControlFramesDoNotWaitForTaskSnapshot() throws Exception {
        CoordinatorService service = new CoordinatorService(
                "coordinator-a",
                Clock.fixed(Instant.ofEpochMilli(1_710_000_000_000L), ZoneOffset.UTC));
        AtomicReference<ChannelHandlerContext> contextRef = new AtomicReference<>();
        CountDownLatch releaseTask = new CountDownLatch(1);
        EmbeddedChannel channel = new EmbeddedChannel(new ChannelInboundHandlerAdapter() {
            @Override
            public void handlerAdded(ChannelHandlerContext context) {
                contextRef.set(context);
            }
        });
        ControlStreamHub hub = new ControlStreamHub(
                service,
                null,
                JsonSupport.objectMapper(),
                agentId -> {
                    try {
                        releaseTask.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                    return Map.of("agent_id", agentId);
                });
        try {
            hub.start();
            hub.subscribe(contextRef.get(), List.of(), List.of("agent-1"), true);

            String initial = awaitPayload(channel, "event: hosts.snapshot");
            assertTrue(initial.contains("event: hosts.snapshot"), initial);
            assertFalse(initial.contains("event: task.snapshot"), initial);
            assertTrue(channel.isActive());

            releaseTask.countDown();
            String task = awaitPayload(channel, "event: task.snapshot");
            assertTrue(task.contains("event: task.snapshot"), task);
            awaitClosed(channel);
        } finally {
            releaseTask.countDown();
            hub.close();
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void taskSnapshotsAreLoadedOutsideChannelEventLoop() throws Exception {
        CoordinatorService service = new CoordinatorService(
                "coordinator-a",
                Clock.fixed(Instant.ofEpochMilli(1_710_000_000_000L), ZoneOffset.UTC));
        AtomicReference<ChannelHandlerContext> contextRef = new AtomicReference<>();
        AtomicReference<String> providerThread = new AtomicReference<>();
        CountDownLatch loaded = new CountDownLatch(1);
        EmbeddedChannel channel = new EmbeddedChannel(new ChannelInboundHandlerAdapter() {
            @Override
            public void handlerAdded(ChannelHandlerContext context) {
                contextRef.set(context);
            }
        });
        ControlStreamHub hub = new ControlStreamHub(
                service,
                null,
                JsonSupport.objectMapper(),
                agentId -> {
                    providerThread.set(Thread.currentThread().getName());
                    loaded.countDown();
                    return Map.of("agent_id", agentId);
                });
        try {
            hub.start();
            hub.subscribe(contextRef.get(), List.of(), List.of("agent-1"), true);
            assertTrue(loaded.await(2, TimeUnit.SECONDS));
            channel.runPendingTasks();
            assertTrue(!providerThread.get().contains("eventLoop"), providerThread.get());
        } finally {
            hub.close();
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void slowClientReceivesResyncMarkerBeforeAuthoritativeSnapshot() throws Exception {
        CoordinatorService service = new CoordinatorService(
                "coordinator-a",
                Clock.fixed(Instant.ofEpochMilli(1_710_000_000_000L), ZoneOffset.UTC));
        AtomicReference<ChannelHandlerContext> contextRef = new AtomicReference<>();
        EmbeddedChannel channel = new EmbeddedChannel(new ChannelInboundHandlerAdapter() {
            @Override
            public void handlerAdded(ChannelHandlerContext context) {
                contextRef.set(context);
            }
        });
        ControlStreamHub hub = new ControlStreamHub(
                service,
                null,
                JsonSupport.objectMapper(),
                agentId -> Map.of("agent_id", agentId));
        try {
            hub.start();
            channel.unsafe().outboundBuffer().setUserDefinedWritability(1, false);
            hub.subscribe(contextRef.get(), List.of(), List.of(), false);
            Thread.sleep(100);
            channel.runPendingTasks();

            channel.unsafe().outboundBuffer().setUserDefinedWritability(1, true);
            String payload = awaitPayload(channel, "event: hosts.snapshot");

            int resync = payload.indexOf("event: control.resync_required");
            int snapshot = payload.indexOf("event: hosts.snapshot");
            assertTrue(resync >= 0, payload);
            assertTrue(snapshot > resync, payload);
            assertTrue(payload.contains("\"reason\":\"slow_client\""), payload);
        } finally {
            hub.close();
            channel.finishAndReleaseAll();
        }
    }

    private static String awaitPayload(EmbeddedChannel channel, String expected) throws Exception {
        StringBuilder payload = new StringBuilder();
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline && payload.indexOf(expected) < 0) {
            Thread.sleep(25);
            channel.runPendingTasks();
            Object message;
            while ((message = channel.readOutbound()) != null) {
                try {
                    if (message instanceof DefaultHttpContent content) {
                        payload.append(content.content().toString(java.nio.charset.StandardCharsets.UTF_8));
                    }
                } finally {
                    ReferenceCountUtil.release(message);
                }
            }
        }
        return payload.toString();
    }

    private static void awaitClosed(EmbeddedChannel channel) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline && channel.isActive()) {
            Thread.sleep(25);
            channel.runPendingTasks();
        }
        assertFalse(channel.isActive());
    }
}
