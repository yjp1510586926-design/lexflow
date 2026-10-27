'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const os = require('node:os');
const { spawnSync } = require('node:child_process');
const core = require('../backup-core.cjs');
const { auditBackup, auditRestore } = require('../audit-buffer-backup.cjs');
const { fs, path, crypto } = core;
async function fixture(action) {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'lexflow-backup-files-'));
  try {
    return await action(directory);
  } finally {
    await fs.rm(directory, { recursive: true, force: true });
  }
}
async function write(file, bytes) {
  const handle = await core.createPrivate(file);
  try {
    await handle.writeFile(bytes);
  } finally {
    await handle.close();
  }
}
test('exclusive private backup keys reject broad permissions, hardlinks and symlinks', () =>
  fixture(async (directory) => {
    const key = path.join(directory, 'key');
    await core.keygen(key);
    assert.equal((await fs.stat(key)).mode & 0o777, 0o600);
    assert.equal((await fs.stat(directory)).mode & 0o777, 0o700);
    const original = await fs.readFile(key);
    await assert.rejects(core.keygen(key));
    assert.deepEqual(await fs.readFile(key), original);
    const link = path.join(directory, 'link');
    await fs.link(key, link);
    await assert.rejects(core.readKey(key), /unsafe_file/);
    await fs.unlink(link);
    await fs.symlink(key, link);
    await assert.rejects(core.readKey(link), /unsafe_file/);
    await fs.unlink(link);
    await fs.chmod(key, 0o644);
    await assert.rejects(core.readKey(key), /unsafe_file/);
  }));
test('AES-GCM package authenticates every frame and rejects ciphertext, tag and key changes', () =>
  fixture(async (directory) => {
    const key = crypto.randomBytes(32),
      file = path.join(directory, 'encrypted');
    const writer = await core.encryptedWriter(file, key);
    const secret = '合成证据内容-不要进入明文归档';
    await writer.frame({ kind: 'synthetic', secret });
    await writer.finish();
    const encrypted = await fs.readFile(file);
    assert.equal(encrypted.includes(Buffer.from(secret)), false);
    const stage = await fs.mkdtemp(path.join(directory, 'stage-'));
    const payload = await core.decrypt(file, key, stage);
    assert.equal((await fs.readFile(payload, 'utf8')).includes(secret), true);
    await assert.rejects(core.encryptedWriter(file, key));
    assert.deepEqual(await fs.readFile(file), encrypted);
    for (const mutate of [
      (bytes) => {
        bytes[22] ^= 1;
      },
      (bytes) => {
        bytes[bytes.length - 1] ^= 1;
      },
    ]) {
      const changed = Buffer.from(encrypted);
      mutate(changed);
      await fs.writeFile(file, changed);
      const isolated = await fs.mkdtemp(path.join(directory, 'bad-'));
      await assert.rejects(core.decrypt(file, key, isolated), /archive_authentication_failed/);
    }
    await fs.writeFile(file, encrypted);
    const wrong = await fs.mkdtemp(path.join(directory, 'wrong-'));
    await assert.rejects(
      core.decrypt(file, crypto.randomBytes(32), wrong),
      /archive_authentication_failed/,
    );
  }));
test('interrupted encrypted output is removed on abort and cannot be confused with a completed backup', () =>
  fixture(async (directory) => {
    const file = path.join(directory, 'partial');
    const key = crypto.randomBytes(32),
      writer = await core.encryptedWriter(file, key);
    await writer.frame({ kind: 'partial' });
    const stage = await fs.mkdtemp(path.join(directory, 'stage-'));
    await assert.rejects(core.decrypt(file, key, stage));
    await writer.abort();
    await assert.rejects(fs.stat(file));
  }));
test('audit backup preserves journal and local HMAC key byte-for-byte in a fresh private directory', () =>
  fixture(async (directory) => {
    const source = path.join(directory, 'source');
    await fs.mkdir(source, { mode: 0o700 });
    const event = crypto.randomUUID() + '.audit',
      temporary = crypto.randomUUID() + '.tmp';
    const entries = {
      '.key': crypto.randomBytes(32),
      '.lock': Buffer.alloc(0),
      [event]: Buffer.from('{"synthetic":"pending event bytes including already damaged content"}'),
      [temporary]: Buffer.from('partial crash bytes'),
    };
    for (const [name, bytes] of Object.entries(entries))
      await write(path.join(source, name), bytes);
    const key = path.join(directory, 'backup-key');
    await core.keygen(key);
    const archive = path.join(directory, 'audit.encrypted');
    const prepared = await auditBackup({ sourceQuiesced: true, directory: source }, key, archive);
    assert.equal(prepared.files, 4);
    assert.equal(prepared.eventHmacValidationDeferredToJava, true);
    const destination = path.join(directory, 'restored');
    const result = await auditRestore(
      { destinationOffline: true, directory: destination },
      key,
      archive,
    );
    assert.equal(result.phase, 'OFFLINE_AUDIT_READY');
    assert.equal((await fs.stat(destination)).mode & 0o777, 0o700);
    for (const [name, bytes] of Object.entries(entries)) {
      assert.deepEqual(await fs.readFile(path.join(destination, name)), bytes);
      assert.equal((await fs.stat(path.join(destination, name))).mode & 0o777, 0o600);
    }
    await assert.rejects(
      auditRestore({ destinationOffline: true, directory: destination }, key, archive),
      /audit_target_exists/,
    );
    assert.deepEqual(await fs.readFile(path.join(destination, event)), entries[event]);
  }));
test('audit restore refuses corrupted input before creating or modifying its destination', () =>
  fixture(async (directory) => {
    const source = path.join(directory, 'source');
    await fs.mkdir(source, { mode: 0o700 });
    await write(path.join(source, '.key'), crypto.randomBytes(32));
    const key = path.join(directory, 'key');
    await core.keygen(key);
    const archive = path.join(directory, 'archive');
    await auditBackup({ sourceQuiesced: true, directory: source }, key, archive);
    const bytes = await fs.readFile(archive);
    bytes[25] ^= 1;
    await fs.writeFile(archive, bytes);
    const destination = path.join(directory, 'new');
    await assert.rejects(
      auditRestore({ destinationOffline: true, directory: destination }, key, archive),
      /archive_authentication_failed/,
    );
    await assert.rejects(fs.stat(destination));
    assert.equal(
      (await fs.readdir(directory)).some((name) => name.startsWith('.lexflow-audit-restore-')),
      false,
    );
  }));
test('audit source requires explicit offline attribution and refuses unknown or linked entries', () =>
  fixture(async (directory) => {
    const source = path.join(directory, 'source');
    await fs.mkdir(source, { mode: 0o700 });
    await write(path.join(source, '.key'), crypto.randomBytes(32));
    const key = path.join(directory, 'key');
    await core.keygen(key);
    const archive = path.join(directory, 'archive');
    await assert.rejects(
      auditBackup({ sourceQuiesced: false, directory: source }, key, archive),
      /source_quiescence_required/,
    );
    await write(path.join(source, 'unknown'), Buffer.from('do not delete'));
    await assert.rejects(
      auditBackup({ sourceQuiesced: true, directory: source }, key, archive),
      /unsupported_audit_directory/,
    );
    assert.equal(await fs.readFile(path.join(source, 'unknown'), 'utf8'), 'do not delete');
    await fs.unlink(path.join(source, 'unknown'));
    const target = path.join(directory, 'external');
    await write(target, Buffer.from('unchanged'));
    await fs.symlink(target, path.join(source, crypto.randomUUID() + '.audit'));
    await assert.rejects(
      auditBackup({ sourceQuiesced: true, directory: source }, key, archive),
      /unsafe_file/,
    );
    assert.equal(await fs.readFile(target, 'utf8'), 'unchanged');
    await assert.rejects(fs.stat(archive));
  }));
test('CLI error output excludes private configuration credentials and prevents implicit execution', () =>
  fixture(async (directory) => {
    const config = path.join(directory, 'config.json');
    const secret = 'synthetic-secret-must-not-print';
    await write(
      config,
      JSON.stringify({
        sourceQuiesced: false,
        business: { password: secret },
        ai: {},
        storage: { secretKey: secret },
      }),
    );
    const command = path.resolve(__dirname, '../lexflow-backup.cjs');
    const result = spawnSync(
      process.execPath,
      [
        command,
        'backup',
        '--config',
        config,
        '--key-file',
        path.join(directory, 'unused-key'),
        '--output',
        path.join(directory, 'unused-output'),
      ],
      { encoding: 'utf8' },
    );
    assert.equal(result.status, 1);
    assert.equal(result.stdout, '');
    assert.equal(result.stderr.includes(secret), false);
    assert.equal(result.stderr.includes('source_quiescence_required'), true);
    const idle = spawnSync(process.execPath, [command], { encoding: 'utf8' });
    assert.equal(idle.status, 1);
    assert.equal(idle.stderr.includes('invalid_arguments'), true);
  }));
