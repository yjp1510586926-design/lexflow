'use strict';
const core = require('./backup-core.cjs');
const { verifyPayload, validateCrossComponent } = require('./database-backup.cjs');
const { fs, path, crypto, nativeFs, readline, fail, fields, quote } = core;
// 匹配项目固定的 Flyway/MySQL V1 部署；建表只执行仓库可信 SQL，不执行归档内 SQL。
const FLYWAY_TABLE = `CREATE TABLE flyway_schema_history (
 installed_rank INT NOT NULL,version VARCHAR(50) NULL,description VARCHAR(200) NOT NULL,type VARCHAR(20) NOT NULL,
 script VARCHAR(1000) NOT NULL,checksum INT NULL,installed_by VARCHAR(100) NOT NULL,
 installed_on TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,execution_time INT NOT NULL,success TINYINT(1) NOT NULL,
 PRIMARY KEY(installed_rank),KEY flyway_schema_history_s_idx(success)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci`;
async function validateForeignKeys(target) {
  const [keys] = await target.db.query(
    `SELECT TABLE_NAME tableName,CONSTRAINT_NAME constraintName,COLUMN_NAME columnName,
    REFERENCED_TABLE_NAME referenceTable,REFERENCED_COLUMN_NAME referenceColumn
    FROM information_schema.key_column_usage WHERE TABLE_SCHEMA=? AND REFERENCED_TABLE_NAME IS NOT NULL
    ORDER BY TABLE_NAME,CONSTRAINT_NAME,ORDINAL_POSITION`,
    [target.config.database],
  );
  const groups = new Map();
  for (const key of keys) {
    const name = key.tableName + ':' + key.constraintName;
    if (!groups.has(name)) groups.set(name, []);
    groups.get(name).push(key);
  }
  for (const columns of groups.values()) {
    const first = columns[0];
    const joins = columns
      .map((column) => 's.' + quote(column.columnName) + '=r.' + quote(column.referenceColumn))
      .join(' AND ');
    const present = columns
      .map((column) => 's.' + quote(column.columnName) + ' IS NOT NULL')
      .join(' AND ');
    const [orphans] = await target.db.query(
      'SELECT COUNT(*) total FROM ' +
        quote(first.tableName) +
        ' s LEFT JOIN ' +
        quote(first.referenceTable) +
        ' r ON ' +
        joins +
        ' WHERE ' +
        present +
        ' AND r.' +
        quote(first.referenceColumn) +
        ' IS NULL',
    );
    if (Number(orphans[0].total)) fail('restored_foreign_key_violation');
  }
}
async function loadRows(target, descriptor, file) {
  const stored = descriptor.columns
    .map((column, index) => ({ column, index }))
    .filter((item) => !/(?:STORED|VIRTUAL) GENERATED/.test(item.column.extra));
  const sql =
    'INSERT INTO ' +
    quote(descriptor.name) +
    ' (' +
    stored.map((item) => quote(item.column.name)).join(',') +
    ') VALUES (' +
    stored.map(() => '?').join(',') +
    ')';
  const input = readline.createInterface({
    input: nativeFs.createReadStream(file),
    crlfDelay: Infinity,
  });
  try {
    for await (const line of input) {
      const values = core.decodeRow(JSON.parse(line), descriptor.columns.length);
      await target.db.execute(
        sql,
        stored.map((item) => values[item.index]),
      );
    }
  } finally {
    input.close();
  }
  if (descriptor.nextId !== null)
    await target.db.query(
      'ALTER TABLE ' + quote(descriptor.name) + ' AUTO_INCREMENT=' + descriptor.nextId,
    );
}
async function verifyRows(target, descriptor, expected) {
  const hash = crypto.createHash('sha256');
  let rows = 0;
  for await (const row of target.client
    .query({
      sql:
        'SELECT * FROM ' +
        quote(descriptor.name) +
        ' ORDER BY ' +
        descriptor.primary.map(quote).join(','),
      rowsAsArray: true,
    })
    .stream({ highWaterMark: 4 })) {
    rows++;
    hash.update(JSON.stringify(core.encodeRow(row)) + '\n');
  }
  if (rows !== expected.rows || hash.digest('hex') !== expected.sha256)
    fail('restored_rows_integrity_mismatch');
}
async function cleanupTarget(target, allowed) {
  if (!target) return;
  await target.db.query('SET FOREIGN_KEY_CHECKS=0');
  for (const name of await core.tableNames(target)) {
    if (!allowed.includes(name)) fail('unexpected_target_table_during_cleanup');
    await target.db.query('DROP TABLE ' + quote(name));
  }
  await target.db.query('SET FOREIGN_KEY_CHECKS=1');
}
// 恢复限定离线空库和新桶，并禁止复用源库、源账号或源桶，避免覆盖原环境。
async function restore(config, keyFile, input, readyFile) {
  fields(config, ['destinationOffline', 'business', 'ai', 'storage']);
  if (config.destinationOffline !== true) fail('offline_destination_required');
  if (config.business.database === config.ai.database || config.business.user === config.ai.user)
    fail('shared_database_identity');
  const parent = path.dirname(path.resolve(readyFile));
  await core.privateDirectory(parent);
  try {
    await fs.lstat(readyFile);
    fail('ready_file_exists');
  } catch (error) {
    if (error.code !== 'ENOENT') throw error;
  }
  const key = await core.readKey(keyFile);
  const staging = await fs.mkdtemp(path.join(parent, '.lexflow-restore-'));
  let business,
    ai,
    bucketOwned = false,
    targetsOwned = false,
    published = false,
    readyOwned = false;
  const attemptedObjects = [];
  let businessTables = [],
    aiTables = [];
  try {
    const payload = await core.decrypt(input, key, staging);
    const verified = await verifyPayload(payload, staging);
    const metadata = verified.metadata;
    fields(metadata.source, ['business', 'ai', 'businessUser', 'aiUser', 'bucket']);
    for (const name of ['business', 'ai'])
      if (
        [metadata.source.business, metadata.source.ai].includes(config[name].database) ||
        [metadata.source.businessUser, metadata.source.aiUser].includes(config[name].user)
      )
        fail('target_reuses_source_database');
    if (config.storage.bucket === metadata.source.bucket) fail('target_reuses_source_bucket');
    businessTables = metadata.tables
      .filter((table) => table.component === 'business')
      .map((table) => table.name);
    aiTables = metadata.tables
      .filter((table) => table.component === 'ai')
      .map((table) => table.name);
    business = await core.connection(config.business, true);
    ai = await core.connection(config.ai, true);
    if ((await core.tableNames(business)).length || (await core.tableNames(ai)).length)
      fail('target_database_not_empty');
    const existing = await core.objectRequest(config.storage, 'HEAD');
    if (existing.status !== 404) fail('target_bucket_already_exists');
    targetsOwned = true;
    bucketOwned = true;
    await core.objectRequest(config.storage, 'PUT');
    // 先按仓库迁移建表，再替换初始化行，保留归档中的 Flyway 身份及业务数据。
    for (const migration of await core.migrationFiles()) await business.db.query(migration.sql);
    await business.db.query(FLYWAY_TABLE);
    await ai.db.query(
      await fs.readFile(
        path.join(core.REPOSITORY, 'ai-service/sql/001_workflow_checkpoints.sql'),
        'utf8',
      ),
    );
    const actual = [
      ...(await core.descriptors(business, businessTables, 'business')),
      ...(await core.descriptors(ai, aiTables, 'ai')),
    ];
    for (let index = 0; index < metadata.tables.length; index++) {
      const expected = metadata.tables[index],
        found = actual[index];
      if (
        expected.ddlHash !== found.ddlHash ||
        JSON.stringify(expected.columns) !== JSON.stringify(found.columns) ||
        JSON.stringify(expected.primary) !== JSON.stringify(found.primary)
      )
        fail('restored_schema_mismatch');
    }
    for (const target of [business, ai]) await target.db.query('SET FOREIGN_KEY_CHECKS=0');
    for (const table of metadata.tables)
      await (table.component === 'business' ? business : ai).db.query(
        'TRUNCATE TABLE ' + quote(table.name),
      );
    for (const table of metadata.tables)
      await loadRows(
        table.component === 'business' ? business : ai,
        table,
        verified.tableFiles.get(table.component + ':' + table.name),
      );
    for (const target of [business, ai]) await target.db.query('SET FOREIGN_KEY_CHECKS=1');
    await validateForeignKeys(business);
    await validateForeignKeys(ai);
    await validateCrossComponent(business, ai);
    for (const table of metadata.tables)
      await verifyRows(
        table.component === 'business' ? business : ai,
        table,
        verified.complete.tables.find(
          (item) => item.component === table.component && item.table === table.name,
        ),
      );
    for (const object of verified.objects.values()) {
      const bytes = await fs.readFile(object.file);
      attemptedObjects.push(object.key);
      await core.objectRequest(config.storage, 'PUT', object.key, bytes, '', object.contentType);
      const stored = await core.objectBytes(
        await core.objectRequest(config.storage, 'GET', object.key),
      );
      if (stored === null || stored.length !== object.size || core.digest(stored) !== object.sha256)
        fail('restored_object_integrity_mismatch');
    }
    // 归档没有 Milvus 快照，故在新业务库中失效索引并移除旧向量位置，后续须显式重建。
    await business.db.beginTransaction();
    let invalidated;
    try {
      const [result] = await business.db.query(
        "UPDATE knowledge_document SET status=IF(status IN ('ACTIVE','INDEXING'),'FAILED',status),collection_name=NULL,embedding_dimension=NULL,model_identity=NULL,pending_index_task_id=NULL WHERE collection_name IS NOT NULL OR status IN ('ACTIVE','INDEXING')",
      );
      invalidated = result.affectedRows;
      await business.db.query('DELETE FROM knowledge_vector_artifact');
      await business.db.execute(
        "INSERT INTO audit_log(action,object_type,object_id,result,trace_id,detail_json) VALUES('system:restore','encrypted_backup',?,'SUCCESS',?,?)",
        [
          metadata.backupId,
          crypto.randomUUID(),
          JSON.stringify({
            strategy: 'offline_sql_objects_vectors_invalidated',
            invalidatedSources: invalidated,
          }),
        ],
      );
      await business.db.commit();
    } catch (error) {
      await business.db.rollback();
      throw error;
    }
    const [pending] = await business.db.query(
      "SELECT tenant_id tenantId,id taskId,type,status,attempt,state_version stateVersion FROM async_task WHERE status IN ('QUEUED','RUNNING','WAITING_HUMAN','CANCEL_REQUESTED') ORDER BY tenant_id,id",
    );
    const [uncertain] = await ai.db.query(
      "SELECT tenant_id tenantId,thread_id threadId,operation_key operationKey,status FROM workflow_operation WHERE status IN ('STARTED','FAILED') ORDER BY tenant_id,thread_id,operation_key",
    );
    const [outbox] = await business.db.query(
      'SELECT status,COUNT(*) total FROM outbox_event GROUP BY status ORDER BY status',
    );
    // OFFLINE_READY 只证明数据恢复完成；消费者与调度器继续停用，待逐项核对任务后切换。
    const ready = {
      format: 1,
      backupId: metadata.backupId,
      phase: 'OFFLINE_READY',
      target: {
        business: config.business.database,
        ai: config.ai.database,
        bucket: config.storage.bucket,
      },
      restoredRows: verified.complete.rowCount,
      restoredObjects: verified.objects.size,
      invalidatedVectorSources: invalidated,
      consumersMustStayDisabled: true,
      schedulersMustStayDisabled: true,
      automaticRecoveryAllowed: false,
      pendingTasks: pending,
      uncertainOrFailedOperations: uncertain,
      outboxStatesPreserved: outbox,
      requiredNextActions: [
        'Review each pending task against current delivery eligibility and the preserved operation journal; never automatically redeliver SENT or uncertain paid work.',
        'Configure a separate target Milvus deployment and explicitly rebuild authorized indexes before enabling retrieval.',
        'Inject the separately retained original AI credential master key; this archive does not contain it.',
        'Restore request audit buffer/key separately into a new private directory if needed.',
        'Create new sessions and validate application routes with consumers and schedulers disabled before cutover.',
      ],
      exclusions: metadata.excludes,
      completedAt: new Date().toISOString(),
    };
    const handle = await core.createPrivate(readyFile);
    readyOwned = true;
    try {
      await handle.writeFile(JSON.stringify(ready, null, 2) + '\n');
      await handle.sync();
    } finally {
      await handle.close();
    }
    await core.forceDirectory(parent);
    published = true;
    return {
      backupId: metadata.backupId,
      phase: 'OFFLINE_READY',
      rows: ready.restoredRows,
      objects: ready.restoredObjects,
      pendingTasks: pending.length,
      uncertainOperations: uncertain.length,
      invalidatedVectorSources: invalidated,
      automaticRecoveryAllowed: false,
    };
  } catch (error) {
    // 仅清理本次确认空白并创建的目标；就绪文件发布成功后不再自动回滚已交付环境。
    if (targetsOwned && !published) {
      let clean = true;
      if (readyOwned)
        try {
          await fs.unlink(readyFile);
        } catch (failure) {
          clean = false;
        }
      for (const [target, tables] of [
        [ai, aiTables],
        [business, businessTables],
      ])
        try {
          await cleanupTarget(target, tables);
        } catch (failure) {
          clean = false;
        }
      if (bucketOwned) {
        for (const object of attemptedObjects)
          try {
            await core.objectRequest(config.storage, 'DELETE', object);
          } catch (failure) {
            clean = false;
          }
        try {
          await core.objectRequest(config.storage, 'DELETE');
        } catch (failure) {
          clean = false;
        }
      }
      if (!clean) fail('restore_failed_cleanup_requires_operator');
    }
    throw error;
  } finally {
    for (const target of [ai, business]) if (target) await target.db.end().catch(() => {});
    key.fill(0);
    await fs.rm(staging, { recursive: true, force: true });
  }
}
module.exports = { restore, FLYWAY_TABLE, validateForeignKeys };
