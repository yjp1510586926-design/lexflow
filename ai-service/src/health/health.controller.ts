import { Controller, Get } from '@nestjs/common';

@Controller('internal')
export class HealthController {
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
