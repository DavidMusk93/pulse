export type HostRecord = Record<string, unknown> & { agent_id?: string };

export type HostStreamV3State<T extends { agent_id?: string } = HostRecord> = {
  revision: number;
  scope: string[];
  catalog: string[];
  fields: string[];
  entities: string[];
  rows: Array<T | null>;
  hosts: T[];
  valueDictionaries: Map<number, unknown[]>;
};

type JsonObject = Record<string, unknown>;

function object(value: unknown, context: string): JsonObject {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error(`${context} must be an object`);
  }
  return value as JsonObject;
}

function array(value: unknown, context: string): unknown[] {
  if (!Array.isArray(value)) throw new Error(`${context} must be an array`);
  return value;
}

function integer(value: unknown, context: string) {
  if (!Number.isInteger(value) || Number(value) < 0) {
    throw new Error(`${context} must be a non-negative integer`);
  }
  return Number(value);
}

function strings(value: unknown, context: string) {
  return array(value, context).map((item, index) => {
    if (typeof item !== 'string') throw new Error(`${context}[${index}] must be a string`);
    return item;
  });
}

function uniqueKey(value: unknown) {
  return JSON.stringify(value);
}

function parseValueDictionaries(value: unknown, fieldCount: number) {
  const result = new Map<number, unknown[]>();
  if (value === undefined) return result;
  for (const [entryIndex, rawEntry] of array(value, 'dictionaries.values').entries()) {
    const entry = array(rawEntry, `dictionaries.values[${entryIndex}]`);
    if (entry.length !== 2) throw new Error('value dictionary must be [field_index, values]');
    const fieldIndex = integer(entry[0], 'value dictionary field index');
    if (fieldIndex >= fieldCount || result.has(fieldIndex)) {
      throw new Error(`invalid value dictionary field ${fieldIndex}`);
    }
    const values = array(entry[1], `value dictionary ${fieldIndex}`);
    const seen = new Set<string>();
    for (const item of values) {
      if (item === null || seen.has(uniqueKey(item))) {
        throw new Error(`invalid value dictionary value for field ${fieldIndex}`);
      }
      seen.add(uniqueKey(item));
    }
    result.set(fieldIndex, [...values]);
  }
  return result;
}

function decodeCell(
  dictionaries: Map<number, unknown[]>,
  fieldIndex: number,
  cell: unknown
) {
  const dictionary = dictionaries.get(fieldIndex);
  if (!dictionary || cell === null) return cell;
  const reference = integer(cell, `value reference for field ${fieldIndex}`);
  if (reference >= dictionary.length) {
    throw new Error(`invalid value reference for field ${fieldIndex}`);
  }
  return dictionary[reference];
}

export function decodeHostSnapshotV3<T extends { agent_id?: string } = HostRecord>(
  input: unknown
): HostStreamV3State<T> {
  const snapshot = object(input, 'V3 snapshot');
  if (snapshot.schema !== 'hosts.v3') throw new Error('Host SSE V3 snapshot contract mismatch');
  const revision = integer(snapshot.revision, 'snapshot revision');
  const dictionaries = object(snapshot.dictionaries, 'snapshot dictionaries');
  const fields = strings(dictionaries.fields, 'dictionaries.fields');
  const entities = strings(dictionaries.entities, 'dictionaries.entities');
  if (new Set(fields).size !== fields.length || new Set(entities).size !== entities.length) {
    throw new Error('Host SSE V3 dictionaries contain duplicates');
  }
  const columns = array(snapshot.columns, 'snapshot columns');
  if (columns.length !== fields.length) {
    throw new Error('Host SSE V3 columns must align with fields');
  }
  const valueDictionaries = parseValueDictionaries(dictionaries.values, fields.length);
  const rows: T[] = entities.map((entity, entityIndex) => {
    const row: HostRecord = {};
    fields.forEach((field, fieldIndex) => {
      const column = array(columns[fieldIndex], `snapshot column ${fieldIndex}`);
      if (column.length !== entities.length) {
        throw new Error(`snapshot column ${fieldIndex} length mismatch`);
      }
      row[field] = decodeCell(valueDictionaries, fieldIndex, column[entityIndex]);
    });
    if (row.agent_id !== entity) throw new Error(`entity dictionary mismatch for ${entity}`);
    return row as T;
  });
  return {
    revision,
    scope: strings(snapshot.scope || [], 'snapshot scope'),
    catalog: strings(snapshot.catalog || [], 'snapshot catalog'),
    fields,
    entities,
    rows,
    hosts: rows,
    valueDictionaries
  };
}

export function applyHostDeltaV3<T extends { agent_id?: string }>(
  current: HostStreamV3State<T>,
  input: unknown
): HostStreamV3State<T> {
  const delta = object(input, 'V3 delta');
  if (
    delta.schema !== 'hosts.v3'
    || delta.base_revision !== current.revision
    || !Number.isInteger(delta.revision)
    || Number(delta.revision) <= current.revision
  ) {
    throw new Error('Host SSE V3 revision gap');
  }

  let valueDictionaries = current.valueDictionaries;
  const extended = new Set<number>();
  const extensions = array(delta.values || [], 'delta values');
  if (extensions.length) {
    valueDictionaries = new Map(current.valueDictionaries);
  }
  for (const rawExtension of extensions) {
    const extension = array(rawExtension, 'value dictionary extension');
    if (extension.length !== 2) throw new Error('invalid value dictionary extension');
    const fieldIndex = integer(extension[0], 'extension field index');
    const currentDictionary = current.valueDictionaries.get(fieldIndex);
    const appended = array(extension[1], `extension values for field ${fieldIndex}`);
    if (!currentDictionary || !appended.length || extended.has(fieldIndex)) {
      throw new Error(`invalid value dictionary extension for field ${fieldIndex}`);
    }
    const dictionary = [...currentDictionary];
    const seen = new Set(dictionary.map(uniqueKey));
    for (const value of appended) {
      if (value === null || seen.has(uniqueKey(value))) {
        throw new Error(`value dictionary extension is not append-only for field ${fieldIndex}`);
      }
      seen.add(uniqueKey(value));
      dictionary.push(value);
    }
    valueDictionaries.set(fieldIndex, dictionary);
    extended.add(fieldIndex);
  }

  let entities = current.entities;
  let rows = current.rows;
  const makeRowsMutable = () => {
    if (rows === current.rows) {
      entities = [...current.entities];
      rows = [...current.rows];
    }
  };
  let collectionChanged = false;
  for (const rawAddition of array(delta.add || [], 'delta add')) {
    const addition = array(rawAddition, 'delta addition');
    if (addition.length !== 3) throw new Error('invalid V3 addition');
    const entityIndex = integer(addition[0], 'addition entity index');
    const entity = addition[1];
    const values = array(addition[2], 'addition values');
    if (entityIndex !== entities.length || typeof entity !== 'string'
        || values.length !== current.fields.length) {
      throw new Error('V3 additions must append one complete entity');
    }
    const row: HostRecord = {};
    current.fields.forEach((field, fieldIndex) => {
      row[field] = decodeCell(valueDictionaries, fieldIndex, values[fieldIndex]);
    });
    if (row.agent_id !== entity) throw new Error(`addition entity mismatch for ${entity}`);
    makeRowsMutable();
    entities.push(entity);
    rows.push(row as T);
    collectionChanged = true;
  }

  for (const rawIndex of array(delta.remove || [], 'delta remove')) {
    const entityIndex = integer(rawIndex, 'removed entity index');
    if (entityIndex >= rows.length) throw new Error(`invalid removed entity ${entityIndex}`);
    if (rows[entityIndex] !== null) {
      makeRowsMutable();
      rows[entityIndex] = null;
      collectionChanged = true;
    }
  }

  const pending = new Map<number, Map<string, { unset: boolean; value?: unknown }>>();
  const changesFor = (entityIndex: number) => {
    let changes = pending.get(entityIndex);
    if (!changes) {
      changes = new Map();
      pending.set(entityIndex, changes);
    }
    return changes;
  };
  for (const rawColumn of array(delta.columns || [], 'delta columns')) {
    const column = array(rawColumn, 'delta column');
    if (column.length !== 3) throw new Error('invalid sparse column');
    const fieldIndex = integer(column[0], 'column field index');
    const indexes = array(column[1], 'column entity indexes');
    const values = array(column[2], 'column values');
    if (fieldIndex >= current.fields.length || indexes.length !== values.length) {
      throw new Error('sparse column mismatch');
    }
    indexes.forEach((rawIndex, index) => {
      const entityIndex = integer(rawIndex, 'column entity index');
      const row = rows[entityIndex];
      if (!row) throw new Error(`column references inactive entity ${entityIndex}`);
      const value = decodeCell(valueDictionaries, fieldIndex, values[index]);
      const field = current.fields[fieldIndex];
      if (!Object.is((row as HostRecord)[field], value)) {
        changesFor(entityIndex).set(field, { unset: false, value });
      }
    });
  }
  for (const rawUnset of array(delta.unset || [], 'delta unset')) {
    const unset = array(rawUnset, 'delta unset entry');
    if (unset.length !== 2) throw new Error('invalid unset entry');
    const fieldIndex = integer(unset[0], 'unset field index');
    if (fieldIndex >= current.fields.length) throw new Error('invalid unset field');
    for (const rawIndex of array(unset[1], 'unset entity indexes')) {
      const entityIndex = integer(rawIndex, 'unset entity index');
      const row = rows[entityIndex];
      if (!row) throw new Error(`unset references inactive entity ${entityIndex}`);
      const field = current.fields[fieldIndex];
      if (field in (row as HostRecord)) changesFor(entityIndex).set(field, { unset: true });
    }
  }

  if (pending.size) makeRowsMutable();
  for (const [entityIndex, changes] of pending) {
    const previous = rows[entityIndex] as T;
    const next = { ...previous } as T;
    const mutableNext = next as HostRecord;
    for (const [field, change] of changes) {
      if (change.unset) delete mutableNext[field];
      else mutableNext[field] = change.value;
    }
    rows[entityIndex] = next;
    collectionChanged = true;
  }

  return {
    revision: Number(delta.revision),
    scope: current.scope,
    catalog: delta.catalog === undefined
      ? current.catalog
      : strings(delta.catalog, 'delta catalog'),
    fields: current.fields,
    entities,
    rows,
    hosts: collectionChanged ? rows.filter((row): row is T => row !== null) : current.hosts,
    valueDictionaries
  };
}
