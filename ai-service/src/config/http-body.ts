import { NestExpressApplication } from '@nestjs/platform-express';

/**
 * 按内部接口用途配置 JSON 请求体上限，替代 NestJS 创建应用时关闭的默认解析器。
 * 两个恢复探针需要接收完整的固定输入，用于与操作日志比较，体积高于普通问答请求。
 * 大请求体规则必须先注册，并同时匹配 POST、精确路径和 JSON Content-Type，
 * 才不会把较高上限扩展到其他接口；最后的 256 KB 解析器处理其余 JSON 请求。
 * 已被前序解析器读取的请求体不会再按普通规则重复读取，超限则由解析器返回 413。
 * @param app 已创建且配置 bodyParser:false 的 NestExpressApplication。
 */
export function configureInternalJsonBody(app: NestExpressApplication): void {
  // 工作流恢复探针携带完整材料；去掉查询串后匹配路径，保留 8 MB 的专用上限。
  app.useBodyParser('json', {
    limit: '8mb',
    type: (request) =>
      request.method === 'POST' &&
      request.url?.split('?')[0] === '/internal/v1/workflow-recovery/eligibility' &&
      /^application\/json(?:\s*;|$)/i.test(request.headers['content-type'] ?? ''),
  });
  // 索引恢复探针最多携带 10000 段原文，不含 API Key，因此使用单独的 16 MB 上限。
  // JSON 大小按传输字节计，而源码中的文本长度按 UTF-16 单元计，两者不可直接等同。
  app.useBodyParser('json', {
    limit: '16mb',
    type: (request) =>
      request.method === 'POST' &&
      request.url?.split('?')[0] === '/internal/v1/index-recovery/eligibility' &&
      /^application\/json(?:\s*;|$)/i.test(request.headers['content-type'] ?? ''),
  });
  // 普通内部接口仍使用较小上限；控制器会继续校验字段类型、数量与单段长度。
  app.useBodyParser('json', { limit: '256kb' });
}
