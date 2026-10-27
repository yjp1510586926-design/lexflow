'use strict';
const core = require('./backup-core.cjs');
const { fs, path, crypto, readline, nativeFs, fail, fields, digest, quote } = core;
const OBJECT_COLUMNS = {
  file_version: ['object_key'],
  document_version: ['content_object_key'],
  async_task: ['result_object_key'],
  document_export_job: ['result_object_key'],
  document_export_artifact: ['object_key'],
  file_cleanup_job: ['object_key'],
  file_cleanup_tombstone: ['object_key'],
  export_cleanup_tombstone: ['object_key'],
};
function referenceCollector() {
  const references = new Map(),
    files = new Map();
  return {
    references,
    row(table, values, descriptor) {
      const row = Object.fromEntries(
        descriptor.columns.map((column, index) => [column.name, values[index]]),
      );
      if (table === 'file_object') files.set(row.tenant_id + ':' + row.id, row.status);
      for (const column of descriptor.columns.filter(
        (column) => column.name === 'object_key' || column.name.endsWith('_object_key'),
      )) {
        if (!OBJECT_COLUMNS[table]?.includes(column.name)) fail('unsupported_object_reference');
        const key = row[column.name];
        if (key === null) continue;
        if (
          typeof key !== 'string' ||
          !key ||
          Buffer.byteLength(key) > 512 ||
          /[\x00-\x1f\x7f]/.test(key)
        )
          fail('invalid_object_reference');
        const required =
          table === 'file_version'
            ? files.get(row.tenant_id + ':' + row.file_id) === 'AVAILABLE'
            : ['document_version', 'document_export_job', 'async_task'].includes(table);
        const sha =
          table === 'file_version'
            ? row.sha256
            : table === 'document_export_job'
              ? row.source_sha256
              : null;
        // 导出任务的 source_sha256 属于输入正文，结果对象的摘要须从实际字节重新计算。
        const expectedHash = table === 'file_version' ? sha : null;
        const expectedSize = table === 'file_version' ? Number(row.size_bytes) : null;
        if (
          expectedHash !== null &&
          (!/^[a-f0-9]{64}$/.test(expectedHash) ||
            !Number.isSafeInteger(expectedSize) ||
            expectedSize < 1 ||
            expectedSize > core.MAX_OBJECT)
        )
          fail('invalid_object_reference');
        const previous = references.get(key);
        if (
          previous &&
          previous.sha256 &&
          expectedHash &&
          (previous.sha256 !== expectedHash || previous.size !== expectedSize)
        )
          fail('conflicting_object_reference');
        references.set(key, {
          key,
          required: required || previous?.required || false,
          sha256: expectedHash || previous?.sha256 || null,
          size: expectedSize ?? previous?.size ?? null,
        });
      }
    },
  };
}
// 两个数据库需共同静止，工作流线程还须对应业务库中真实租户及任务/连接测试。
async function validateCrossComponent(business, ai) {
  const [tenants] = await business.db.query('SELECT id FROM tenant');
  const allowedTenants = new Set(tenants.map((row) => String(row.id)));
  const [tasks] = await business.db.query('SELECT tenant_id,id FROM async_task');
  const taskIds = new Set(tasks.map((row) => row.tenant_id + ':' + row.id));
  const [tests] = await business.db.query('SELECT tenant_id,id FROM ai_configuration_test');
  const testIds = new Set(tests.map((row) => row.tenant_id + ':' + row.id));
  for (const table of core.AI_TABLES) {
    const [threads] = await ai.db.query('SELECT DISTINCT tenant_id,thread_id FROM ' + quote(table));
    for (const row of threads) {
      const match = /^(lf|lft):([1-9][0-9]{0,18}):([1-9][0-9]{0,18})$/.exec(row.thread_id);
      if (
        !match ||
        match[2] !== String(row.tenant_id) ||
        !allowedTenants.has(String(row.tenant_id)) ||
        !(match[1] === 'lf' ? taskIds : testIds).has(match[2] + ':' + match[3])
      )
        fail('cross_component_identity_mismatch');
    }
  }
  const [leases] = await ai.db.query(
    'SELECT COUNT(*) total FROM workflow_thread WHERE lease_token IS NOT NULL',
  );
  const [uploads] = await business.db.query(
    "SELECT COUNT(*) total FROM file_object WHERE status='UPLOADING'",
  );
  if (Number(leases[0].total) || Number(uploads[0].total)) fail('source_has_active_writes');
  const [operations] = await ai.db.query(
    "SELECT COUNT(*) total FROM workflow_operation WHERE status NOT IN ('STARTED','SUCCEEDED','FAILED')",
  );
  if (Number(operations[0].total)) fail('unknown_operation_status');
}
// 操作方先停止写入，再对两个数据库加读锁；对象仅按数据库引用收集，避免混入无关文件。
async function backup(config, keyFile, output) {
  fields(config, ['sourceQuiesced', 'business', 'ai', 'storage']);
  if (config.sourceQuiesced !== true) fail('source_quiescence_required');
  if (config.business.database === config.ai.database || config.business.user === config.ai.user)
    fail('shared_database_identity');
  const key = await core.readKey(keyFile);
  const migrations = await core.migrationFiles();
  let business, ai, writer;
  try {
    business = await core.connection(config.business);
    ai = await core.connection(config.ai);
    const businessNames = await core.tableNames(business),
      aiNames = await core.tableNames(ai);
    if (
      JSON.stringify(businessNames) !==
        JSON.stringify(await core.expectedBusinessTables(migrations)) ||
      JSON.stringify(aiNames) !== JSON.stringify(core.AI_TABLES)
    )
      fail('unsupported_schema');
    const [history] = await business.db.query(
      "SELECT version,success FROM flyway_schema_history WHERE type='SQL' ORDER BY installed_rank",
    );
    if (
      history.length !== migrations.length ||
      history.some(
        (row, index) => String(row.version) !== String(index + 1) || Number(row.success) !== 1,
      )
    )
      fail('schema_version_mismatch');
    await business.db.query(
      'LOCK TABLES ' + businessNames.map((name) => quote(name) + ' READ').join(','),
    );
    await ai.db.query('LOCK TABLES ' + aiNames.map((name) => quote(name) + ' READ').join(','));
    await validateCrossComponent(business, ai);
    const tables = [
      ...(await core.descriptors(business, businessNames, 'business')),
      ...(await core.descriptors(ai, aiNames, 'ai')),
    ];
    const collector = referenceCollector();
    const hashes = [];
    let rowCount = 0;
    writer = await core.encryptedWriter(output, key);
    const backupId = crypto.randomUUID();
    await writer.frame({
      kind: 'metadata',
      format: 1,
      backupId,
      createdAt: new Date().toISOString(),
      source: {
        business: config.business.database,
        ai: config.ai.database,
        businessUser: config.business.user,
        aiUser: config.ai.user,
        bucket: config.storage.bucket,
      },
      migrations: migrations.map(({ name, version, sha256 }) => ({ name, version, sha256 })),
      workflowSchemaSha256: digest(
        await fs.readFile(
          path.join(core.REPOSITORY, 'ai-service/sql/001_workflow_checkpoints.sql'),
        ),
      ),
      tables,
      strategy: 'quiesced_read_locked_v1_vector_invalidation',
      excludes: [
        'milvus_vectors',
        'redis_sessions',
        'rabbitmq_messages',
        'ai_master_key',
        'unreferenced_objects',
        'audit_buffer',
      ],
    });
    for (const descriptor of tables) {
      const source = descriptor.component === 'business' ? business : ai;
      const hasher = crypto.createHash('sha256');
      let count = 0;
      for await (const raw of source.client
        .query({
          sql:
            'SELECT * FROM ' +
            quote(descriptor.name) +
            ' ORDER BY ' +
            descriptor.primary.map(quote).join(','),
          rowsAsArray: true,
        })
        .stream({ highWaterMark: 4 })) {
        const values = core.encodeRow(raw);
        if (++rowCount > core.MAX_ROWS) fail('row_limit');
        count++;
        const serialized = JSON.stringify(values);
        hasher.update(serialized + '\n');
        if (descriptor.component === 'business') collector.row(descriptor.name, values, descriptor);
        await writer.frame({
          kind: 'row',
          component: descriptor.component,
          table: descriptor.name,
          values,
        });
      }
      hashes.push({
        component: descriptor.component,
        table: descriptor.name,
        rows: count,
        sha256: hasher.digest('hex'),
      });
    }
    if ((await core.objectRequest(config.storage, 'HEAD')).status !== 200)
      fail('source_bucket_missing');
    const policy = await core.objectRequest(
      config.storage,
      'GET',
      null,
      Buffer.alloc(0),
      'policy=',
    );
    if (policy.status !== 404) {
      await policy.body?.cancel();
      fail('source_bucket_policy_requires_review');
    }
    let objectCount = 0;
    for (const reference of collector.references.values()) {
      const before = await core.objectRequest(config.storage, 'HEAD', reference.key);
      if (before.status === 404) {
        if (reference.required) fail('required_object_missing');
        await writer.frame({ kind: 'missingObject', key: reference.key });
        continue;
      }
      const bytes = await core.objectBytes(
        await core.objectRequest(config.storage, 'GET', reference.key),
      );
      const sha256 = digest(bytes),
        size = bytes.length;
      if (reference.sha256 && (reference.sha256 !== sha256 || reference.size !== size))
        fail('source_object_integrity_mismatch');
      // 对象存储不受 SQL 读锁保护，下载前后核对 ETag 和大小以发现源对象变化。
      const after = await core.objectRequest(config.storage, 'HEAD', reference.key);
      if (
        after.status === 404 ||
        before.headers.get('etag') !== after.headers.get('etag') ||
        Number(after.headers.get('content-length')) !== size
      )
        fail('source_object_changed');
      const contentType = before.headers.get('content-type') || 'application/octet-stream';
      if (contentType.length > 256 || /[\r\n]/.test(contentType)) fail('invalid_content_type');
      await writer.frame({
        kind: 'object',
        key: reference.key,
        size,
        sha256,
        contentType,
        base64: bytes.toString('base64'),
      });
      objectCount++;
    }
    await validateCrossComponent(business, ai);
    await writer.frame({
      kind: 'complete',
      backupId,
      rowCount,
      objectCount,
      tables: hashes,
      references: [...collector.references.values()],
    });
    await writer.finish();
    writer = null;
    return {
      backupId,
      tables: tables.length,
      rows: rowCount,
      objects: objectCount,
      encrypted: true,
      automaticExecution: false,
    };
  } catch (error) {
    if (writer) await writer.abort();
    throw error;
  } finally {
    for (const source of [ai, business])
      if (source) {
        await source.db.query('UNLOCK TABLES').catch(() => {});
        await source.db.end().catch(() => {});
      }
    key.fill(0);
  }
}
// 完整核对迁移版本、表顺序、行摘要和对象引用后，恢复流程才可写入目标资源。
async function verifyPayload(payload, staging) {
  let metadata,
    complete,
    rowCount = 0,
    objectCount = 0,
    lastTableIndex = -1,
    objectPhase = false;
  const tableFiles = new Map(),
    tableHashes = new Map(),
    objects = new Map(),
    missing = new Set();
  const collector = referenceCollector();
  const stream = readline.createInterface({
    input: nativeFs.createReadStream(payload),
    crlfDelay: Infinity,
  });
  try {
    for await (const line of stream) {
      if (Buffer.byteLength(line) > core.MAX_LINE) fail('archive_limit');
      const frame = JSON.parse(line);
      if (complete) fail('data_after_complete');
      if (!metadata) {
        fields(frame, [
          'kind',
          'format',
          'backupId',
          'createdAt',
          'source',
          'migrations',
          'workflowSchemaSha256',
          'tables',
          'strategy',
          'excludes',
        ]);
        if (
          frame.kind !== 'metadata' ||
          frame.format !== 1 ||
          frame.strategy !== 'quiesced_read_locked_v1_vector_invalidation' ||
          !Array.isArray(frame.tables)
        )
          fail('invalid_metadata');
        metadata = frame;
        const migrations = await core.migrationFiles();
        if (
          JSON.stringify(frame.migrations) !==
            JSON.stringify(
              migrations.map(({ name, version, sha256 }) => ({ name, version, sha256 })),
            ) ||
          frame.workflowSchemaSha256 !==
            digest(
              await fs.readFile(
                path.join(core.REPOSITORY, 'ai-service/sql/001_workflow_checkpoints.sql'),
              ),
            )
        )
          fail('restore_code_version_mismatch');
        const expected = [
          ...(await core.expectedBusinessTables(migrations)).map((name) => 'business:' + name),
          ...core.AI_TABLES.map((name) => 'ai:' + name),
        ];
        if (
          JSON.stringify(frame.tables.map((table) => table.component + ':' + table.name)) !==
          JSON.stringify(expected)
        )
          fail('invalid_tables');
        for (const table of frame.tables) {
          fields(table, ['component', 'name', 'columns', 'primary', 'ddlHash', 'nextId']);
          core.identifier(table.name);
          if (
            !Array.isArray(table.columns) ||
            !table.columns.length ||
            table.columns.length > 100 ||
            !/^[a-f0-9]{64}$/.test(table.ddlHash) ||
            (table.nextId !== null && !/^[1-9][0-9]{0,19}$/.test(table.nextId))
          )
            fail('invalid_table_descriptor');
          for (const column of table.columns) {
            fields(column, ['name', 'type', 'nullable', 'collation', 'extra']);
            core.identifier(column.name);
          }
          if (
            !Array.isArray(table.primary) ||
            !table.primary.length ||
            table.primary.some((column) => !table.columns.some((item) => item.name === column))
          )
            fail('invalid_primary_key');
          const token = table.component + ':' + table.name;
          tableFiles.set(token, path.join(staging, token.replace(':', '_') + '.rows'));
          tableHashes.set(token, { hash: crypto.createHash('sha256'), rows: 0 });
          const handle = await core.createPrivate(tableFiles.get(token));
          await handle.close();
        }
      } else if (frame.kind === 'row') {
        fields(frame, ['kind', 'component', 'table', 'values']);
        const token = frame.component + ':' + frame.table;
        const table = metadata.tables.find((item) => item.component + ':' + item.name === token);
        const tableIndex = metadata.tables.indexOf(table);
        if (!table || objectPhase || tableIndex < lastTableIndex || ++rowCount > core.MAX_ROWS)
          fail('invalid_row');
        lastTableIndex = tableIndex;
        core.decodeRow(frame.values, table.columns.length);
        const serialized = JSON.stringify(frame.values) + '\n';
        await fs.appendFile(tableFiles.get(token), serialized);
        tableHashes.get(token).hash.update(serialized);
        tableHashes.get(token).rows++;
        if (frame.component === 'business') collector.row(frame.table, frame.values, table);
      } else if (frame.kind === 'object') {
        objectPhase = true;
        fields(frame, ['kind', 'key', 'size', 'sha256', 'contentType', 'base64']);
        if (
          typeof frame.key !== 'string' ||
          Buffer.byteLength(frame.key) > 512 ||
          objects.has(frame.key) ||
          missing.has(frame.key) ||
          typeof frame.base64 !== 'string' ||
          !Number.isSafeInteger(frame.size) ||
          frame.size < 0 ||
          frame.size > core.MAX_OBJECT ||
          typeof frame.contentType !== 'string' ||
          frame.contentType.length > 256 ||
          /[\r\n]/.test(frame.contentType)
        )
          fail('invalid_object');
        const bytes = Buffer.from(frame.base64, 'base64');
        if (
          bytes.toString('base64') !== frame.base64 ||
          bytes.length !== frame.size ||
          digest(bytes) !== frame.sha256
        )
          fail('invalid_object');
        const file = path.join(staging, digest(frame.key) + '.object');
        const handle = await core.createPrivate(file);
        try {
          await handle.writeFile(bytes);
          await handle.sync();
        } finally {
          await handle.close();
        }
        objects.set(frame.key, { ...frame, file, base64: undefined });
        objectCount++;
      } else if (frame.kind === 'missingObject') {
        objectPhase = true;
        fields(frame, ['kind', 'key']);
        if (typeof frame.key !== 'string' || missing.has(frame.key) || objects.has(frame.key))
          fail('invalid_object');
        missing.add(frame.key);
      } else if (frame.kind === 'complete') {
        fields(frame, ['kind', 'backupId', 'rowCount', 'objectCount', 'tables', 'references']);
        complete = frame;
      } else fail('unknown_archive_frame');
    }
  } finally {
    stream.close();
  }
  if (
    !metadata ||
    !complete ||
    complete.backupId !== metadata.backupId ||
    complete.rowCount !== rowCount ||
    complete.objectCount !== objectCount
  )
    fail('incomplete_archive');
  const hashes = metadata.tables.map((table) => {
    const token = table.component + ':' + table.name,
      item = tableHashes.get(token);
    return {
      component: table.component,
      table: table.name,
      rows: item.rows,
      sha256: item.hash.digest('hex'),
    };
  });
  if (
    JSON.stringify(hashes) !== JSON.stringify(complete.tables) ||
    JSON.stringify([...collector.references.values()]) !== JSON.stringify(complete.references)
  )
    fail('archive_manifest_mismatch');
  if (objects.size + missing.size !== collector.references.size)
    fail('archive_object_reference_mismatch');
  for (const reference of collector.references.values()) {
    const object = objects.get(reference.key);
    if (
      (!object && (!missing.has(reference.key) || reference.required)) ||
      (object &&
        reference.sha256 &&
        (object.sha256 !== reference.sha256 || object.size !== reference.size))
    )
      fail('archive_object_reference_mismatch');
  }
  return { metadata, complete, tableFiles, objects };
}
module.exports = { backup, verifyPayload, referenceCollector, validateCrossComponent };
