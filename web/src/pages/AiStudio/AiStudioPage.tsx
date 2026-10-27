import React from 'react';
import { Card } from '../../components/common/Card';
import { Button } from '../../components/common/Button';

interface Props {
  onOpenCases: () => void;
  onOpenTasks: () => void;
  onOpenKnowledge: () => void;
}

export const AiStudioPage: React.FC<Props> = ({ onOpenCases, onOpenTasks, onOpenKnowledge }) => (
  <div className="ai-studio-page">
    <Card title="AI 助手" subtitle="根据有权访问的案件或律所资料提问">
      <p style={{ lineHeight: 1.8, marginBottom: 16 }}>
        在案件详情的“案件知识库”中整理资料、完成索引后，即可根据原文提问并查看出处。
        也可在“律所知识库”中根据公共法律资料提问，会话仅你本人可见。
        在案件文件中点击“审查合同”，选择审查立场与关注点；需要人工复核时，可在任务记录中核对原文、填写意见并确认。
        管理员需要先在 AI 配置中填写模型 Key。
      </p>
      <div style={{ display: 'flex', gap: 12 }}>
        <Button onClick={onOpenCases}>查看案件</Button>
        <Button variant="secondary" onClick={onOpenKnowledge}>
          问律所资料
        </Button>
        <Button variant="secondary" onClick={onOpenTasks}>
          查看任务记录
        </Button>
      </div>
    </Card>
  </div>
);
