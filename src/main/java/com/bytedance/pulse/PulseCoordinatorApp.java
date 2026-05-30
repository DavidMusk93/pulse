package com.bytedance.pulse;

import java.time.Clock;

public final class PulseCoordinatorApp {
    private PulseCoordinatorApp() {}

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("PULSE_PORT", "8080"));
        String bindHost = System.getenv().getOrDefault("PULSE_BIND_HOST", "127.0.0.1");
        String coordinatorId = System.getenv().getOrDefault("PULSE_COORDINATOR_ID", "coordinator-local");

        CoordinatorService service = new CoordinatorService(coordinatorId, Clock.systemUTC());
        CoordinatorHttpServer server = new CoordinatorHttpServer(service, bindHost, port);
        server.start();

        System.out.printf("Pulse coordinator %s listening on %s:%d%n", coordinatorId, bindHost, server.port());
    }
}
