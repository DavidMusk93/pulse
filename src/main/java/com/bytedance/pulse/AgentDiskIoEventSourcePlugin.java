package com.bytedance.pulse;

import java.util.List;
import java.util.Map;

/**
 * Coordinator-side contract for the Agent disk IO producer.
 */
final class AgentDiskIoEventSourcePlugin implements EventPlugin.Source {
    static final String TYPE = "agent_disk_io";

    private final PulseMessageEventSourcePlugin transport = new PulseMessageEventSourcePlugin();

    @Override
    public boolean supports(String inputType) {
        return transport.supports(inputType);
    }

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor(
                TYPE,
                "source",
                "Agent 磁盘 IO Source",
                "Agent 依据门槛和持续时间生成事件，再通过 heartbeat event.publish 传输。",
                AgentDiskIoEventEmitter.configFields());
    }

    @Override
    public List<Event> evaluate(
            String sourceId,
            String eventType,
            String severity,
            Map<String, Object> config,
            Observation observation) {
        return transport.evaluate(
                sourceId,
                eventType,
                severity,
                config,
                observation);
    }
}
