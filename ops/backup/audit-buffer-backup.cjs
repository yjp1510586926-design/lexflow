'use strict';
const core = require('./backup-core.cjs');
const { fs, path, crypto, readline, nativeFs, fail, fields } = core;
const validName = (name) =>
  name === '.key' ||
  name === '.lock' ||
  /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}\.(audit|tmp)$/.test(name);
// 审计缓冲与其 HMAC 密钥一起加密备份；源目录需静止，且仅接受已知文件命名。
async function auditBackup(config, keyFile, output) {
  fields(config, ['sourceQuiesced', 'directory']);
  if (config.sourceQuiesced !== true) fail('source_quiescence_required');
  await core.privateDirectory(config.directory);
  const names = (await fs.readdir(config.directory)).sort();
  if (!names.includes('.key') || names.some((name) => !validName(name)) || names.length > 100002)
    fail('unsupported_audit_directory');
  const key = await core.readKey(keyFile);
  let writer;
  const hashes = [];
  try {
    writer = await core.encryptedWriter(output, key);
    const backupId = crypto.randomUUID();
    await writer.frame({
      kind: 'auditMetadata',
      format: 1,
      backupId,
      createdAt: new Date().toISOString(),
      purpose: 'request_audit_buffer',
    });
    for (const name of names) {
      const file = path.join(config.directory, name);
      const info = await core.privateFile(file, 8192);
      if ((name === '.key' && info.size !== 32) || (name === '.lock' && info.size !== 0))
        fail('invalid_audit_key_or_lock');
      const handle = await fs.open(
        file,
        nativeFs.constants.O_RDONLY | nativeFs.constants.O_NOFOLLOW,
      );
      let bytes;
      try {
        bytes = await handle.readFile();
      } finally {
        await handle.close();
      }
      const sha256 = core.digest(bytes);
      hashes.push({ name, size: bytes.length, sha256 });
      await writer.frame({
        kind: 'auditFile',
        name,
        size: bytes.length,
        sha256,
        base64: bytes.toString('base64'),
      });
      if (core.digest(await fs.readFile(file)) !== sha256) fail('audit_source_changed');
    }
    if (JSON.stringify((await fs.readdir(config.directory)).sort()) !== JSON.stringify(names))
      fail('audit_source_changed');
    await writer.frame({ kind: 'auditComplete', backupId, files: hashes });
    await writer.finish();
    writer = null;
    return {
      backupId,
      files: names.length,
      encrypted: true,
      eventHmacValidationDeferredToJava: true,
    };
  } catch (error) {
    if (writer) await writer.abort();
    throw error;
  } finally {
    key.fill(0);
  }
}
// 恢复到全新私有目录；此处只验证归档完整性，单条审计事件 HMAC 由 Java 回放时校验。
async function auditRestore(config, keyFile, input) {
  fields(config, ['destinationOffline', 'directory']);
  if (config.destinationOffline !== true) fail('offline_destination_required');
  const destination = path.resolve(config.directory),
    parent = path.dirname(destination);
  await core.privateDirectory(parent);
  try {
    await fs.lstat(destination);
    fail('audit_target_exists');
  } catch (error) {
    if (error.code !== 'ENOENT') throw error;
  }
  const key = await core.readKey(keyFile);
  const staging = await fs.mkdtemp(path.join(parent, '.lexflow-audit-restore-'));
  let owned = false,
    metadata,
    complete;
  const files = new Map();
  try {
    const payload = await core.decrypt(input, key, staging);
    const stream = readline.createInterface({
      input: nativeFs.createReadStream(payload),
      crlfDelay: Infinity,
    });
    try {
      for await (const line of stream) {
        if (Buffer.byteLength(line) > 16384) fail('invalid_audit_archive');
        const frame = JSON.parse(line);
        if (complete) fail('data_after_complete');
        if (!metadata) {
          fields(frame, ['kind', 'format', 'backupId', 'createdAt', 'purpose']);
          if (
            frame.kind !== 'auditMetadata' ||
            frame.format !== 1 ||
            frame.purpose !== 'request_audit_buffer'
          )
            fail('invalid_audit_archive');
          metadata = frame;
        } else if (frame.kind === 'auditFile') {
          fields(frame, ['kind', 'name', 'size', 'sha256', 'base64']);
          if (
            !validName(frame.name) ||
            files.has(frame.name) ||
            files.size >= 100002 ||
            typeof frame.base64 !== 'string'
          )
            fail('invalid_audit_archive');
          const bytes = Buffer.from(frame.base64, 'base64');
          if (
            bytes.toString('base64') !== frame.base64 ||
            bytes.length !== frame.size ||
            bytes.length > 8192 ||
            core.digest(bytes) !== frame.sha256
          )
            fail('invalid_audit_archive');
          if (
            (frame.name === '.key' && bytes.length !== 32) ||
            (frame.name === '.lock' && bytes.length !== 0)
          )
            fail('invalid_audit_key_or_lock');
          files.set(frame.name, {
            bytes,
            name: frame.name,
            size: bytes.length,
            sha256: frame.sha256,
          });
        } else if (frame.kind === 'auditComplete') {
          fields(frame, ['kind', 'backupId', 'files']);
          complete = frame;
        } else fail('invalid_audit_archive');
      }
    } finally {
      stream.close();
    }
    if (
      !complete ||
      complete.backupId !== metadata.backupId ||
      !files.has('.key') ||
      JSON.stringify(complete.files) !==
        JSON.stringify(
          [...files.values()].map(({ name, size, sha256 }) => ({ name, size, sha256 })),
        )
    )
      fail('incomplete_audit_archive');
    await fs.mkdir(destination, { mode: 0o700 });
    owned = true;
    for (const file of files.values()) {
      const handle = await core.createPrivate(path.join(destination, file.name));
      try {
        await handle.writeFile(file.bytes);
        await handle.sync();
      } finally {
        await handle.close();
      }
    }
    await core.forceDirectory(destination);
    await core.forceDirectory(parent);
    return {
      backupId: metadata.backupId,
      files: files.size,
      phase: 'OFFLINE_AUDIT_READY',
      eventHmacValidationDeferredToJava: true,
    };
  } catch (error) {
    if (owned) await fs.rm(destination, { recursive: true, force: true });
    throw error;
  } finally {
    key.fill(0);
    await fs.rm(staging, { recursive: true, force: true });
  }
}
module.exports = { auditBackup, auditRestore };
