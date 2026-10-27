#!/usr/bin/env node
'use strict';
const core = require('./backup-core.cjs');
async function main(arguments_) {
  const [command, ...options] = arguments_;
  const values = {};
  for (let index = 0; index < options.length; index += 2) {
    const name = options[index];
    if (
      !['--config', '--key-file', '--output', '--input', '--ready-file'].includes(name) ||
      !options[index + 1] ||
      values[name]
    )
      core.fail('invalid_arguments');
    values[name] = options[index + 1];
  }
  const required = {
    keygen: ['--key-file'],
    backup: ['--config', '--key-file', '--output'],
    restore: ['--config', '--key-file', '--input', '--ready-file'],
    'audit-backup': ['--config', '--key-file', '--output'],
    'audit-restore': ['--config', '--key-file', '--input'],
  }[command];
  if (
    !required ||
    Object.keys(values).length !== required.length ||
    required.some((name) => !values[name])
  )
    core.fail('invalid_arguments');
  if (command === 'keygen') {
    await core.keygen(values['--key-file']);
    return { keyCreated: true };
  }
  const config = await core.readPrivateJson(values['--config']);
  if (command === 'backup')
    return require('./database-backup.cjs').backup(
      config,
      values['--key-file'],
      values['--output'],
    );
  if (command === 'restore')
    return require('./database-restore.cjs').restore(
      config,
      values['--key-file'],
      values['--input'],
      values['--ready-file'],
    );
  if (command === 'audit-backup')
    return require('./audit-buffer-backup.cjs').auditBackup(
      config,
      values['--key-file'],
      values['--output'],
    );
  return require('./audit-buffer-backup.cjs').auditRestore(
    config,
    values['--key-file'],
    values['--input'],
  );
}
// CLI 仅输出固定失败码，底层异常可能含连接凭据，不能直接打印。
if (require.main === module)
  main(process.argv.slice(2))
    .then((result) => process.stdout.write(JSON.stringify(result) + '\n'))
    .catch((error) => {
      const code = error instanceof core.BackupFailure ? error.code : 'operation_failed';
      process.stderr.write(
        '备份恢复操作失败：' + code + '。检查私有配置与离线目标；凭据和材料未输出。\n',
      );
      process.exitCode = 1;
    });
module.exports = { main };
