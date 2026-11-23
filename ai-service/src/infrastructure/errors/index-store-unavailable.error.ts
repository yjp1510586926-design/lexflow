/** 向量存储暂时不可用，投递层可稍后尝试并由操作日志复用已保存的模型结果。 */
export class IndexStoreUnavailableError extends Error {
  constructor() {
    super('向量存储暂时不可用，已保存的模型结果可供恢复');
  }
}
