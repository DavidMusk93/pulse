package com.bytedance.pulse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HostStreamV3CodecTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void snapshotHasFixedFieldsAlignedColumnsAndDeterministicDictionaries() {
        HostView zeta = host("zeta", 9, "cdn2", null, "0.90");
        HostView alpha = host("alpha", 3, "cdn1", "zone-a", "0.30");
        HostStreamV3Codec.Session session = HostStreamV3Codec.session(
                MAPPER, 41, List.of("cdn2", "cdn2"), List.of(zeta, alpha));

        JsonNode snapshot = json(session.snapshot());

        assertEquals("hosts.v3", snapshot.get("schema").asText());
        assertEquals(41, snapshot.get("revision").asLong());
        assertEquals(List.of("cdn2"), strings(snapshot.get("scope")));
        assertEquals(List.of("cdn1", "cdn2"), strings(snapshot.get("catalog")));
        assertEquals(
                HostStreamV3Codec.FIELDS,
                strings(snapshot.at("/dictionaries/fields")));
        assertEquals(List.of("zeta"), strings(snapshot.at("/dictionaries/entities")));
        assertEquals(24, snapshot.get("columns").size());
        snapshot.get("columns").forEach(column -> assertEquals(1, column.size()));
        assertEquals("zeta", snapshot.at("/columns/0/0").asText());
        assertEquals(9, snapshot.at("/columns/2/0").asLong());
        assertTrue(snapshot.at("/columns/21/0").isNull());
        assertEquals(
                Set.of("schema", "revision", "scope", "catalog", "dictionaries", "columns"),
                MAPPER.convertValue(snapshot, Map.class).keySet());
    }

    @Test
    void deltaGroupsSparseFieldsAndKeepsNullAsAValue() {
        HostView alpha = host("alpha", 1, "cdn1", "zone-a", "0.10");
        HostView beta = host("beta", 2, "cdn2", "zone-b", "0.20");
        HostStreamV3Codec.Session session = HostStreamV3Codec.session(
                MAPPER, 10, List.of(), List.of(beta, alpha));
        JsonNode snapshot = json(session.snapshot());

        HostView changedAlpha = host("alpha", 7, "cdn1", null, "0.70");
        HostView gamma = host("gamma", 1, "cdn3", "zone-c", "0.30");
        JsonNode delta = json(session.delta(14, List.of(gamma, changedAlpha)));

        assertEquals("hosts.v3", delta.get("schema").asText());
        assertEquals(10, delta.get("base_revision").asLong());
        assertEquals(14, delta.get("revision").asLong());
        assertEquals(List.of(1), integers(delta.get("remove")));
        assertEquals(1, delta.get("add").size());
        assertEquals(2, delta.at("/add/0/0").asInt());
        assertEquals("gamma", delta.at("/add/0/1").asText());
        assertEquals(24, delta.at("/add/0/2").size());
        assertEquals(List.of("cdn1", "cdn3"), strings(delta.get("catalog")));
        assertTrue(delta.get("unset").isEmpty());

        JsonNode seqColumn = column(delta, 2);
        assertEquals(List.of(0), integers(seqColumn.get(1)));
        assertEquals(7, seqColumn.at("/2/0").asLong());
        JsonNode zoneColumn = column(delta, 21);
        assertEquals(List.of(0), integers(zoneColumn.get(1)));
        assertTrue(zoneColumn.at("/2/0").isNull());

        Decoded decoded = decode(snapshot);
        apply(decoded, delta);
        assertEquals(Set.of("alpha", "gamma"), decoded.rows.keySet());
        assertTrue(decoded.rows.get("alpha").containsKey("zone"));
        assertNull(decoded.rows.get("alpha").get("zone"));
        assertEquals(7L, decoded.rows.get("alpha").get("seq"));
        assertEquals("zone-c", decoded.rows.get("gamma").get("zone"));
    }

    @Test
    void removalsAreTombstonesAndReappearingIdsAppend() {
        HostView alpha = host("alpha", 1, "cdn1", "zone-a", "0.10");
        HostView beta = host("beta", 1, "cdn1", "zone-b", "0.20");
        HostStreamV3Codec.Session session = HostStreamV3Codec.session(
                MAPPER, 1, List.of(), List.of(alpha, beta));
        Object originalSnapshot = session.snapshot();

        JsonNode removed = json(session.delta(2, List.of(beta)));
        JsonNode reappeared = json(session.delta(3, List.of(beta, alpha)));
        JsonNode removedAgain = json(session.delta(4, List.of(beta)));

        assertEquals(List.of(0), integers(removed.get("remove")));
        assertEquals(2, reappeared.at("/add/0/0").asInt());
        assertEquals("alpha", reappeared.at("/add/0/1").asText());
        assertEquals(List.of(2), integers(removedAgain.get("remove")));
        assertEquals(
                List.of("alpha", "beta"),
                strings(json(originalSnapshot).at("/dictionaries/entities")));
    }

    @Test
    void revisionMustAdvanceAndRejectedCallsDoNotMutateTheChain() {
        HostView alpha = host("alpha", 1, "cdn1", "zone-a", "0.10");
        HostStreamV3Codec.Session session = HostStreamV3Codec.session(
                MAPPER, 5, List.of(), List.of(alpha));

        assertThrows(IllegalArgumentException.class, () -> session.delta(5, List.of(alpha)));
        assertThrows(IllegalArgumentException.class, () -> session.delta(4, List.of(alpha)));

        JsonNode first = json(session.delta(9, List.of(alpha)));
        JsonNode second = json(session.delta(12, List.of(alpha)));
        assertEquals(5, first.get("base_revision").asLong());
        assertEquals(9, second.get("base_revision").asLong());
        assertTrue(first.get("add").isEmpty());
        assertTrue(first.get("remove").isEmpty());
        assertTrue(first.get("columns").isEmpty());
        assertTrue(first.get("unset").isEmpty());
    }

    @Test
    void scopeIsAppliedBeforeEncodingWhileCatalogRemainsGlobal() {
        HostView alpha = host("alpha", 1, "cdn1", "zone-a", "0.10");
        HostView beta = host("beta", 1, "cdn2", "zone-b", "0.20");
        HostStreamV3Codec.Session session = HostStreamV3Codec.session(
                MAPPER, 1, List.of("cdn2"), List.of(alpha, beta));

        JsonNode offScopeOnly = json(session.delta(
                2, List.of(host("alpha", 9, "cdn1", "zone-z", "0.90"), beta)));
        JsonNode catalogChange = json(session.delta(
                3, List.of(alpha, beta, host("gamma", 1, "cdn3", "zone-c", "0.30"))));

        assertTrue(offScopeOnly.get("add").isEmpty());
        assertTrue(offScopeOnly.get("remove").isEmpty());
        assertTrue(offScopeOnly.get("columns").isEmpty());
        assertFalse(offScopeOnly.has("catalog"));
        assertTrue(catalogChange.get("add").isEmpty());
        assertEquals(List.of("cdn1", "cdn2", "cdn3"), strings(catalogChange.get("catalog")));
    }

    @Test
    void outputIsIndependentOfHostAndScopeInputOrder() throws Exception {
        HostView alpha = host("alpha", 1, "cdn1", "zone-a", "0.10");
        HostView beta = host("beta", 1, "cdn2", "zone-b", "0.20");
        HostStreamV3Codec.Session left = HostStreamV3Codec.session(
                MAPPER, 1, List.of("cdn2", "cdn1"), List.of(beta, alpha));
        HostStreamV3Codec.Session right = HostStreamV3Codec.session(
                MAPPER, 1, List.of("cdn1", "cdn2"), List.of(alpha, beta));

        assertEquals(
                MAPPER.writeValueAsString(left.snapshot()),
                MAPPER.writeValueAsString(right.snapshot()));
        assertEquals(
                MAPPER.writeValueAsString(left.delta(
                        2,
                        List.of(
                                host("beta", 3, "cdn2", "zone-b", "0.40"),
                                host("alpha", 2, "cdn1", "zone-a", "0.30")))),
                MAPPER.writeValueAsString(right.delta(
                        2,
                        List.of(
                                host("alpha", 2, "cdn1", "zone-a", "0.30"),
                                host("beta", 3, "cdn2", "zone-b", "0.40")))));
    }

    @Test
    void duplicateEntityIdsAreRejected() {
        HostView alpha = host("alpha", 1, "cdn1", "zone-a", "0.10");
        assertThrows(
                IllegalArgumentException.class,
                () -> HostStreamV3Codec.session(
                        MAPPER, 1, List.of(), List.of(alpha, alpha)));
    }

    @Test
    void snapshotUsesOnlyValueDictionariesThatReduceSerializedBytes() throws Exception {
        ArrayList<HostView> hosts = new ArrayList<>();
        for (int index = 0; index < 32; index++) {
            hosts.add(host(
                    "agent-" + index,
                    index,
                    "cluster-" + index,
                    index == 0 ? null : "zone-" + (index % 2),
                    "0." + index));
        }

        JsonNode snapshot = json(HostStreamV3Codec.session(
                MAPPER, 1, List.of(), hosts).snapshot());
        Map<Integer, JsonNode> dictionaries = valueDictionaries(
                snapshot.at("/dictionaries/values"));

        assertTrue(dictionaries.containsKey(8), "repeated status should be dictionary-coded");
        assertTrue(dictionaries.containsKey(21), "repeated non-null zones should be dictionary-coded");
        assertFalse(dictionaries.containsKey(0), "unique agent ids must remain raw");
        assertFalse(dictionaries.containsKey(2), "unique numeric sequence values must remain raw");
        assertFalse(dictionaries.containsKey(19), "high-cardinality clusters must remain raw");
        assertTrue(snapshot.at("/columns/8/0").isIntegralNumber());
        assertTrue(snapshot.at("/columns/21/0").isNull());
        int agentSevenIndex = strings(snapshot.at("/dictionaries/entities")).indexOf("agent-7");
        assertTrue(snapshot.get("columns").get(2).get(agentSevenIndex).isIntegralNumber());
        assertEquals(7, snapshot.get("columns").get(2).get(agentSevenIndex).asLong());

        Decoded decoded = decode(snapshot);
        for (HostView host : hosts) {
            assertEquals(expectedRow(host), decoded.rows.get(host.agentId()));
        }

        ArrayList<HostView> reversed = new ArrayList<>();
        for (int index = hosts.size() - 1; index >= 0; index--) {
            reversed.add(hosts.get(index));
        }
        assertEquals(
                MAPPER.writeValueAsString(snapshot),
                MAPPER.writeValueAsString(HostStreamV3Codec.session(
                        MAPPER, 1, List.of(), reversed).snapshot()));
    }

    @Test
    void deltaAppendsValuesBeforeUsingReferencesAndReconstructsAllFields() {
        ArrayList<HostView> initial = new ArrayList<>();
        for (int index = 0; index < 24; index++) {
            initial.add(host("agent-" + index, index, "cdn1", "zone-a", "0.10"));
        }
        HostStreamV3Codec.Session session = HostStreamV3Codec.session(
                MAPPER, 10, List.of(), initial);
        JsonNode snapshot = json(session.snapshot());
        assertTrue(valueDictionaries(snapshot.at("/dictionaries/values")).containsKey(8));

        ArrayList<HostView> next = new ArrayList<>(initial);
        HostView changed = hostWithStatus(
                "agent-0", 99, "cdn1", "zone-b", "0.99", "draining");
        next.set(0, changed);
        HostView added = hostWithStatus(
                "agent-new", 100, "cdn1", "zone-c", "0.50", "starting");
        next.add(added);
        JsonNode delta = json(session.delta(11, next));

        Map<Integer, JsonNode> extensions = valueDictionaries(delta.get("values"));
        assertEquals(List.of("draining", "starting"), strings(extensions.get(8)));
        assertTrue(column(delta, 8).at("/2/0").isIntegralNumber());
        assertEquals(1, column(delta, 8).at("/2/0").asInt());
        assertEquals(2, delta.at("/add/0/2/8").asInt());

        Decoded decoded = decode(snapshot);
        apply(decoded, delta);
        assertEquals(expectedRow(changed), decoded.rows.get("agent-0"));
        assertEquals(24, decoded.rows.get("agent-0").size());
        assertEquals(expectedRow(added), decoded.rows.get("agent-new"));
    }

    private static JsonNode column(JsonNode delta, int fieldIndex) {
        for (JsonNode column : delta.get("columns")) {
            if (column.get(0).asInt() == fieldIndex) {
                return column;
            }
        }
        throw new AssertionError("missing field column " + fieldIndex);
    }

    private static JsonNode json(Object value) {
        return MAPPER.valueToTree(value);
    }

    private static List<String> strings(JsonNode values) {
        ArrayList<String> result = new ArrayList<>(values.size());
        values.forEach(value -> result.add(value.asText()));
        return result;
    }

    private static List<Integer> integers(JsonNode values) {
        ArrayList<Integer> result = new ArrayList<>(values.size());
        values.forEach(value -> result.add(value.asInt()));
        return result;
    }

    private static Decoded decode(JsonNode snapshot) {
        List<String> fields = strings(snapshot.at("/dictionaries/fields"));
        List<String> entities = strings(snapshot.at("/dictionaries/entities"));
        Map<Integer, ArrayList<Object>> dictionaries = decodeValueDictionaries(
                snapshot.at("/dictionaries/values"));
        Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
        for (int entityIndex = 0; entityIndex < entities.size(); entityIndex++) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int fieldIndex = 0; fieldIndex < fields.size(); fieldIndex++) {
                row.put(
                        fields.get(fieldIndex),
                        decodedScalar(
                                snapshot.get("columns").get(fieldIndex).get(entityIndex),
                                dictionaries.get(fieldIndex)));
            }
            rows.put(entities.get(entityIndex), row);
        }
        return new Decoded(fields, new ArrayList<>(entities), dictionaries, rows);
    }

    private static void apply(Decoded decoded, JsonNode delta) {
        if (delta.has("values")) {
            for (JsonNode extension : delta.get("values")) {
                int fieldIndex = extension.get(0).asInt();
                ArrayList<Object> values = decoded.valueDictionaries.get(fieldIndex);
                extension.get(1).forEach(value -> values.add(scalar(value)));
            }
        }
        for (JsonNode addition : delta.get("add")) {
            int entityIndex = addition.get(0).asInt();
            String agentId = addition.get(1).asText();
            assertEquals(decoded.entities.size(), entityIndex);
            decoded.entities.add(agentId);
            Map<String, Object> row = new LinkedHashMap<>();
            for (int fieldIndex = 0; fieldIndex < decoded.fields.size(); fieldIndex++) {
                row.put(
                        decoded.fields.get(fieldIndex),
                        decodedScalar(
                                addition.get(2).get(fieldIndex),
                                decoded.valueDictionaries.get(fieldIndex)));
            }
            decoded.rows.put(agentId, row);
        }
        for (JsonNode entityIndex : delta.get("remove")) {
            decoded.rows.remove(decoded.entities.get(entityIndex.asInt()));
        }

        Map<Integer, Map<String, Object>> pending = new HashMap<>();
        for (JsonNode column : delta.get("columns")) {
            String field = decoded.fields.get(column.get(0).asInt());
            for (int index = 0; index < column.get(1).size(); index++) {
                int entityIndex = column.get(1).get(index).asInt();
                pending.computeIfAbsent(entityIndex, ignored -> new LinkedHashMap<>())
                        .put(
                                field,
                                decodedScalar(
                                        column.get(2).get(index),
                                        decoded.valueDictionaries.get(column.get(0).asInt())));
            }
        }
        for (Map.Entry<Integer, Map<String, Object>> entry : pending.entrySet()) {
            String agentId = decoded.entities.get(entry.getKey());
            Map<String, Object> clone = new LinkedHashMap<>(decoded.rows.get(agentId));
            clone.putAll(entry.getValue());
            decoded.rows.put(agentId, clone);
        }
    }

    private static Map<Integer, JsonNode> valueDictionaries(JsonNode declarations) {
        Map<Integer, JsonNode> result = new LinkedHashMap<>();
        declarations.forEach(declaration ->
                result.put(declaration.get(0).asInt(), declaration.get(1)));
        return result;
    }

    private static Map<Integer, ArrayList<Object>> decodeValueDictionaries(JsonNode declarations) {
        Map<Integer, ArrayList<Object>> result = new LinkedHashMap<>();
        declarations.forEach(declaration -> {
            ArrayList<Object> values = new ArrayList<>();
            declaration.get(1).forEach(value -> values.add(scalar(value)));
            result.put(declaration.get(0).asInt(), values);
        });
        return result;
    }

    private static Object decodedScalar(JsonNode value, List<Object> dictionary) {
        if (value.isNull() || dictionary == null) {
            return scalar(value);
        }
        return dictionary.get(value.asInt());
    }

    private static Object scalar(JsonNode value) {
        if (value.isNull()) {
            return null;
        }
        if (value.isIntegralNumber()) {
            return value.asLong();
        }
        return value.asText();
    }

    private static Map<String, Object> expectedRow(HostView host) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("agent_id", host.agentId());
        row.put("epoch", host.epoch());
        row.put("seq", host.seq());
        row.put("ttl_ms", host.ttlMs());
        row.put("observed_at_ms", host.observedAtMs());
        row.put("expire_at_ms", host.expireAtMs());
        row.put("last_observed_age_ms", host.lastObservedAgeMs());
        row.put("heartbeat_confirmations", (long) host.heartbeatConfirmations());
        row.put("status", host.status());
        row.put("source", host.source());
        row.put("coordinator_id", host.coordinatorId());
        row.put("group_id", host.groupId());
        row.put("group_mode", host.groupMode());
        row.put("leader_agent_id", host.leaderAgentId());
        row.put("leader_url", host.leaderUrl());
        row.put("group_size", (long) host.groupSize());
        row.put("group_size_limit", (long) host.groupSizeLimit());
        row.put("host", host.host());
        row.put("ip", host.ip());
        row.put("cluster", host.cluster());
        row.put("area", host.area());
        row.put("zone", host.zone());
        row.put("role", host.role());
        row.put("load", host.load());
        return row;
    }

    private static HostView host(
            String agentId,
            long seq,
            String cluster,
            String zone,
            String load) {
        return hostWithStatus(agentId, seq, cluster, zone, load, "alive");
    }

    private static HostView hostWithStatus(
            String agentId,
            long seq,
            String cluster,
            String zone,
            String load,
            String status) {
        return new HostView(
                agentId,
                1,
                seq,
                15_000,
                1_710_000_000_000L + seq,
                1_710_000_015_000L + seq,
                seq,
                3,
                status,
                "direct",
                "coordinator-a",
                "direct",
                "direct",
                agentId,
                null,
                1,
                100,
                "host-" + agentId,
                "10.0.0." + seq,
                cluster,
                "area-a",
                zone,
                "worker",
                load,
                Map.of("ignored", true));
    }

    private record Decoded(
            List<String> fields,
            ArrayList<String> entities,
            Map<Integer, ArrayList<Object>> valueDictionaries,
            Map<String, Map<String, Object>> rows) {}
}
