package com.bytedance.pulse;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Connection-local encoder for the fixed Hosts V3 columnar wire contract.
 */
public final class HostStreamV3Codec {
    public static final List<String> FIELDS = List.of(
            "agent_id",
            "epoch",
            "seq",
            "ttl_ms",
            "observed_at_ms",
            "expire_at_ms",
            "last_observed_age_ms",
            "heartbeat_confirmations",
            "status",
            "source",
            "coordinator_id",
            "group_id",
            "group_mode",
            "leader_agent_id",
            "leader_url",
            "group_size",
            "group_size_limit",
            "host",
            "ip",
            "cluster",
            "area",
            "zone",
            "role",
            "load");

    private HostStreamV3Codec() {}

    public static Session session(
            ObjectMapper mapper,
            long revision,
            List<String> scope,
            List<HostView> hosts) {
        Objects.requireNonNull(mapper, "mapper");
        return new Session(mapper, revision, canonicalScope(scope), hosts);
    }

    public static final class Session {
        private final List<String> scope;
        private final ArrayList<String> entities;
        private final Map<String, Object> snapshot;
        private long revision;
        private List<String> catalog;
        private ArrayList<HostView> activeHosts;
        private ArrayList<ValueDictionary> valueDictionaries;

        private Session(
                ObjectMapper mapper,
                long revision,
                List<String> scope,
                List<HostView> hosts) {
            this.scope = scope;
            this.revision = revision;
            this.catalog = catalog(hosts);

            TreeMap<String, HostView> scoped = scopedById(hosts, scope);
            this.entities = new ArrayList<>(scoped.keySet());
            this.activeHosts = new ArrayList<>(scoped.values());
            this.snapshot = snapshotEnvelope(mapper);
        }

        private Session(Session template) {
            this.scope = template.scope;
            this.entities = new ArrayList<>(template.entities);
            this.snapshot = template.snapshot;
            this.revision = template.revision;
            this.catalog = template.catalog;
            this.activeHosts = new ArrayList<>(template.activeHosts);
            this.valueDictionaries = copyValueDictionaries(
                    template.valueDictionaries);
        }

        Session fork() {
            return new Session(this);
        }

        public Object snapshot() {
            return snapshot;
        }

        public Object delta(long nextRevision, List<HostView> hosts) {
            if (nextRevision <= revision) {
                throw new IllegalArgumentException(
                        "revision must advance from " + revision + ": " + nextRevision);
            }

            List<String> nextCatalog = catalog(hosts);
            TreeMap<String, HostView> current = scopedById(hosts, scope);
            ArrayList<HostView> nextActiveHosts = new ArrayList<>(activeHosts);
            ArrayList<Integer> removed = new ArrayList<>();
            ArrayList<ArrayList<Integer>> changedIndexes = new ArrayList<>(FIELDS.size());
            ArrayList<ArrayList<Object>> changedValues = new ArrayList<>(FIELDS.size());
            for (int fieldIndex = 0; fieldIndex < FIELDS.size(); fieldIndex++) {
                changedIndexes.add(null);
                changedValues.add(null);
            }

            for (int entityIndex = 0; entityIndex < activeHosts.size(); entityIndex++) {
                HostView previous = activeHosts.get(entityIndex);
                if (previous == null) {
                    continue;
                }
                HostView next = current.remove(previous.agentId());
                if (next == null) {
                    removed.add(entityIndex);
                    nextActiveHosts.set(entityIndex, null);
                    continue;
                }
                nextActiveHosts.set(entityIndex, next);
                for (int fieldIndex = 0; fieldIndex < FIELDS.size(); fieldIndex++) {
                    if (sameField(fieldIndex, previous, next)) {
                        continue;
                    }
                    ArrayList<Integer> indexes = changedIndexes.get(fieldIndex);
                    ArrayList<Object> values = changedValues.get(fieldIndex);
                    if (indexes == null) {
                        indexes = new ArrayList<>();
                        values = new ArrayList<>();
                        changedIndexes.set(fieldIndex, indexes);
                        changedValues.set(fieldIndex, values);
                    }
                    indexes.add(entityIndex);
                    values.add(fieldValue(fieldIndex, next));
                }
            }

            ArrayList<List<Object>> additions = new ArrayList<>(current.size());
            ArrayList<String> addedIds = new ArrayList<>(current.size());
            ArrayList<HostView> addedHosts = new ArrayList<>(current.size());
            for (Map.Entry<String, HostView> entry : current.entrySet()) {
                String agentId = entry.getKey();
                HostView host = entry.getValue();
                addedIds.add(agentId);
                addedHosts.add(host);
            }

            ArrayList<ValueDictionary> nextValueDictionaries =
                    copyValueDictionaries(valueDictionaries);
            ArrayList<List<Object>> extensions = new ArrayList<>();
            for (int fieldIndex = 0; fieldIndex < FIELDS.size(); fieldIndex++) {
                ValueDictionary dictionary = nextValueDictionaries.get(fieldIndex);
                if (dictionary == null) {
                    continue;
                }
                ArrayList<Object> appended = new ArrayList<>();
                ArrayList<Object> values = changedValues.get(fieldIndex);
                if (values != null) {
                    appendMissing(dictionary, values, appended);
                }
                for (HostView host : addedHosts) {
                    appendMissing(dictionary, fieldValue(fieldIndex, host), appended);
                }
                if (!appended.isEmpty()) {
                    extensions.add(List.of(fieldIndex, appended));
                }
            }

            int nextEntityIndex = entities.size();
            for (int index = 0; index < addedHosts.size(); index++) {
                additions.add(List.of(
                        nextEntityIndex++,
                        addedIds.get(index),
                        completeRow(addedHosts.get(index), nextValueDictionaries)));
            }

            ArrayList<List<Object>> columns = new ArrayList<>();
            for (int fieldIndex = 0; fieldIndex < FIELDS.size(); fieldIndex++) {
                ArrayList<Integer> indexes = changedIndexes.get(fieldIndex);
                if (indexes != null) {
                    columns.add(List.of(
                            fieldIndex,
                            indexes,
                            encodeValues(
                                    fieldIndex,
                                    changedValues.get(fieldIndex),
                                    nextValueDictionaries)));
                }
            }

            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("schema", "hosts.v3");
            envelope.put("base_revision", revision);
            envelope.put("revision", nextRevision);
            if (!extensions.isEmpty()) {
                envelope.put("values", extensions);
            }
            envelope.put("add", additions);
            envelope.put("remove", removed);
            envelope.put("columns", columns);
            envelope.put("unset", List.of());
            if (!catalog.equals(nextCatalog)) {
                envelope.put("catalog", nextCatalog);
            }

            entities.addAll(addedIds);
            nextActiveHosts.addAll(addedHosts);
            activeHosts = nextActiveHosts;
            valueDictionaries = nextValueDictionaries;
            catalog = nextCatalog;
            revision = nextRevision;
            return envelope;
        }

        private Map<String, Object> snapshotEnvelope(ObjectMapper mapper) {
            ArrayList<List<Object>> rawColumns = new ArrayList<>(FIELDS.size());
            for (int fieldIndex = 0; fieldIndex < FIELDS.size(); fieldIndex++) {
                ArrayList<Object> values = new ArrayList<>(activeHosts.size());
                for (HostView host : activeHosts) {
                    values.add(fieldValue(fieldIndex, host));
                }
                rawColumns.add(Collections.unmodifiableList(values));
            }

            ArrayList<ValueDictionary> selected = candidateDictionaries(rawColumns);
            Map<String, Object> rawEnvelope = snapshotEnvelope(rawColumns, null);
            int rawBytes = serializedBytes(mapper, rawEnvelope);
            int selectedBytes = serializedBytes(mapper, snapshotEnvelope(
                    encodedColumns(rawColumns, selected), selected));

            for (int fieldIndex = 0; fieldIndex < FIELDS.size(); fieldIndex++) {
                ValueDictionary dictionary = selected.get(fieldIndex);
                if (dictionary == null) {
                    continue;
                }
                selected.set(fieldIndex, null);
                Map<String, Object> withoutField = snapshotEnvelope(
                        encodedColumns(rawColumns, selected), selected);
                int withoutFieldBytes = serializedBytes(mapper, withoutField);
                if (withoutFieldBytes <= selectedBytes) {
                    selectedBytes = withoutFieldBytes;
                } else {
                    selected.set(fieldIndex, dictionary);
                }
            }

            if (selectedBytes >= rawBytes) {
                selected = emptyValueDictionaries();
            }
            valueDictionaries = selected;
            return snapshotEnvelope(encodedColumns(rawColumns, selected), selected);
        }

        private Map<String, Object> snapshotEnvelope(
                List<List<Object>> columns,
                List<ValueDictionary> dictionariesByField) {
            Map<String, Object> dictionaries = new LinkedHashMap<>();
            dictionaries.put("fields", FIELDS);
            dictionaries.put("entities", List.copyOf(entities));
            List<List<Object>> declarations = dictionaryDeclarations(dictionariesByField);
            if (!declarations.isEmpty()) {
                dictionaries.put("values", declarations);
            }

            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("schema", "hosts.v3");
            envelope.put("revision", revision);
            envelope.put("scope", scope);
            envelope.put("catalog", catalog);
            envelope.put("dictionaries", dictionaries);
            envelope.put("columns", columns);
            return envelope;
        }

        private static int serializedBytes(ObjectMapper mapper, Object value) {
            try {
                return mapper.writeValueAsBytes(value).length;
            } catch (Exception exception) {
                throw new IllegalStateException("failed to measure V3 snapshot bytes", exception);
            }
        }
    }

    private static List<String> canonicalScope(List<String> scope) {
        Objects.requireNonNull(scope, "scope");
        TreeSet<String> ordered = new TreeSet<>();
        for (String cluster : scope) {
            if (cluster != null && !cluster.isBlank()) {
                ordered.add(cluster);
            }
        }
        return List.copyOf(ordered);
    }

    private static List<String> catalog(List<HostView> hosts) {
        Objects.requireNonNull(hosts, "hosts");
        TreeSet<String> clusters = new TreeSet<>();
        for (HostView host : hosts) {
            Objects.requireNonNull(host, "host");
            if (host.cluster() != null && !host.cluster().isBlank()) {
                clusters.add(host.cluster());
            }
        }
        return List.copyOf(clusters);
    }

    private static TreeMap<String, HostView> scopedById(
            List<HostView> hosts,
            List<String> scope) {
        Objects.requireNonNull(hosts, "hosts");
        Set<String> selected = scope.isEmpty() ? Set.of() : new HashSet<>(scope);
        TreeMap<String, HostView> result = new TreeMap<>();
        for (HostView host : hosts) {
            Objects.requireNonNull(host, "host");
            if (!selected.isEmpty() && !selected.contains(host.cluster())) {
                continue;
            }
            String agentId = Objects.requireNonNull(host.agentId(), "host.agentId");
            if (result.putIfAbsent(agentId, host) != null) {
                throw new IllegalArgumentException("duplicate agent_id: " + agentId);
            }
        }
        return result;
    }

    private static List<Object> completeRow(
            HostView host,
            List<ValueDictionary> valueDictionaries) {
        ArrayList<Object> values = new ArrayList<>(FIELDS.size());
        for (int fieldIndex = 0; fieldIndex < FIELDS.size(); fieldIndex++) {
            values.add(encodeValue(
                    fieldIndex,
                    fieldValue(fieldIndex, host),
                    valueDictionaries));
        }
        return Collections.unmodifiableList(values);
    }

    private static ArrayList<ValueDictionary> candidateDictionaries(
            List<List<Object>> columns) {
        ArrayList<ValueDictionary> result = emptyValueDictionaries();
        for (int fieldIndex = 0; fieldIndex < FIELDS.size(); fieldIndex++) {
            ValueDictionary dictionary = new ValueDictionary();
            for (Object value : columns.get(fieldIndex)) {
                dictionary.add(value);
            }
            if (!dictionary.values.isEmpty()) {
                result.set(fieldIndex, dictionary);
            }
        }
        return result;
    }

    private static ArrayList<ValueDictionary> emptyValueDictionaries() {
        ArrayList<ValueDictionary> result = new ArrayList<>(FIELDS.size());
        for (int fieldIndex = 0; fieldIndex < FIELDS.size(); fieldIndex++) {
            result.add(null);
        }
        return result;
    }

    private static ArrayList<ValueDictionary> copyValueDictionaries(
            List<ValueDictionary> dictionaries) {
        ArrayList<ValueDictionary> result = new ArrayList<>(dictionaries.size());
        for (ValueDictionary dictionary : dictionaries) {
            result.add(dictionary == null ? null : new ValueDictionary(dictionary));
        }
        return result;
    }

    private static List<List<Object>> encodedColumns(
            List<List<Object>> rawColumns,
            List<ValueDictionary> dictionaries) {
        ArrayList<List<Object>> result = new ArrayList<>(FIELDS.size());
        for (int fieldIndex = 0; fieldIndex < FIELDS.size(); fieldIndex++) {
            result.add(encodeValues(fieldIndex, rawColumns.get(fieldIndex), dictionaries));
        }
        return result;
    }

    private static List<Object> encodeValues(
            int fieldIndex,
            List<Object> rawValues,
            List<ValueDictionary> dictionaries) {
        ValueDictionary dictionary = dictionaries.get(fieldIndex);
        if (dictionary == null) {
            return rawValues;
        }
        ArrayList<Object> encoded = new ArrayList<>(rawValues.size());
        for (Object value : rawValues) {
            encoded.add(encodeValue(fieldIndex, value, dictionaries));
        }
        return Collections.unmodifiableList(encoded);
    }

    private static Object encodeValue(
            int fieldIndex,
            Object value,
            List<ValueDictionary> dictionaries) {
        if (value == null) {
            return null;
        }
        ValueDictionary dictionary = dictionaries.get(fieldIndex);
        if (dictionary == null) {
            return value;
        }
        Integer reference = dictionary.indexes.get(value);
        if (reference == null) {
            throw new IllegalStateException(
                    "missing value dictionary entry for field " + fieldIndex + ": " + value);
        }
        return reference;
    }

    private static List<List<Object>> dictionaryDeclarations(
            List<ValueDictionary> dictionaries) {
        if (dictionaries == null) {
            return List.of();
        }
        ArrayList<List<Object>> declarations = new ArrayList<>();
        for (int fieldIndex = 0; fieldIndex < dictionaries.size(); fieldIndex++) {
            ValueDictionary dictionary = dictionaries.get(fieldIndex);
            if (dictionary != null) {
                declarations.add(List.of(fieldIndex, List.copyOf(dictionary.values)));
            }
        }
        return declarations;
    }

    private static void appendMissing(
            ValueDictionary dictionary,
            List<Object> values,
            List<Object> appended) {
        for (Object value : values) {
            appendMissing(dictionary, value, appended);
        }
    }

    private static void appendMissing(
            ValueDictionary dictionary,
            Object value,
            List<Object> appended) {
        if (value != null && dictionary.add(value)) {
            appended.add(value);
        }
    }

    private static final class ValueDictionary {
        private final ArrayList<Object> values = new ArrayList<>();
        private final Map<Object, Integer> indexes = new HashMap<>();

        private ValueDictionary() {}

        private ValueDictionary(ValueDictionary source) {
            values.addAll(source.values);
            indexes.putAll(source.indexes);
        }

        private boolean add(Object value) {
            if (value == null || indexes.containsKey(value)) {
                return false;
            }
            indexes.put(value, values.size());
            values.add(value);
            return true;
        }
    }

    private static boolean sameField(int fieldIndex, HostView left, HostView right) {
        return switch (fieldIndex) {
            case 0 -> Objects.equals(left.agentId(), right.agentId());
            case 1 -> left.epoch() == right.epoch();
            case 2 -> left.seq() == right.seq();
            case 3 -> left.ttlMs() == right.ttlMs();
            case 4 -> left.observedAtMs() == right.observedAtMs();
            case 5 -> left.expireAtMs() == right.expireAtMs();
            case 6 -> left.lastObservedAgeMs() == right.lastObservedAgeMs();
            case 7 -> left.heartbeatConfirmations() == right.heartbeatConfirmations();
            case 8 -> Objects.equals(left.status(), right.status());
            case 9 -> Objects.equals(left.source(), right.source());
            case 10 -> Objects.equals(left.coordinatorId(), right.coordinatorId());
            case 11 -> Objects.equals(left.groupId(), right.groupId());
            case 12 -> Objects.equals(left.groupMode(), right.groupMode());
            case 13 -> Objects.equals(left.leaderAgentId(), right.leaderAgentId());
            case 14 -> Objects.equals(left.leaderUrl(), right.leaderUrl());
            case 15 -> left.groupSize() == right.groupSize();
            case 16 -> left.groupSizeLimit() == right.groupSizeLimit();
            case 17 -> Objects.equals(left.host(), right.host());
            case 18 -> Objects.equals(left.ip(), right.ip());
            case 19 -> Objects.equals(left.cluster(), right.cluster());
            case 20 -> Objects.equals(left.area(), right.area());
            case 21 -> Objects.equals(left.zone(), right.zone());
            case 22 -> Objects.equals(left.role(), right.role());
            case 23 -> Objects.equals(left.load(), right.load());
            default -> throw new IndexOutOfBoundsException(fieldIndex);
        };
    }

    private static Object fieldValue(int fieldIndex, HostView host) {
        return switch (fieldIndex) {
            case 0 -> host.agentId();
            case 1 -> host.epoch();
            case 2 -> host.seq();
            case 3 -> host.ttlMs();
            case 4 -> host.observedAtMs();
            case 5 -> host.expireAtMs();
            case 6 -> host.lastObservedAgeMs();
            case 7 -> host.heartbeatConfirmations();
            case 8 -> host.status();
            case 9 -> host.source();
            case 10 -> host.coordinatorId();
            case 11 -> host.groupId();
            case 12 -> host.groupMode();
            case 13 -> host.leaderAgentId();
            case 14 -> host.leaderUrl();
            case 15 -> host.groupSize();
            case 16 -> host.groupSizeLimit();
            case 17 -> host.host();
            case 18 -> host.ip();
            case 19 -> host.cluster();
            case 20 -> host.area();
            case 21 -> host.zone();
            case 22 -> host.role();
            case 23 -> host.load();
            default -> throw new IndexOutOfBoundsException(fieldIndex);
        };
    }
}
