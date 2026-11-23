import { Controller, Get } from '@nestjs/common';

/**
 * 进程存活探针，供调用方确认 HTTP 路由可以响应并了解已接入的能力名称。
 * 不主动连接模型、数据库或消息队列；返回 UP 不能证明这些外部依赖或模型配置可用。
 * capabilities 是静态能力说明，不是逐项运行测试后的结果。
 */
@Controller('internal')
export class HealthController {
  /**
   * GET /internal/health：返回服务标识、静态能力与本次响应的 UTC 时间。
   * pendingCapabilities 表示尚未接入的能力；例如当前不执行扫描件 OCR。
   */
  @Get('health')
  health() {
    return {
      service: 'lexflow-ai',
      status: 'UP',
      // 进程在线不代表模型能力已经可用。
      capabilities: [
        'document.parse.text',
        'document.parse.pdf-text-layer',
        'document.parse.docx',
        'contract.review',
        'document.generate.draft',
      ],
      pendingCapabilities: ['document.parse.ocr'],
      timestamp: new Date().toISOString(),
    };
  }
}
