package com.lexflow.service.ai;

/** 只将受信分类码映射为固定提示，不向用户回传服务商响应或运行时凭据。 */
public final class ModelFailureMessages {

    private ModelFailureMessages() {}

    public static String forCaseAnalysis(String code) {
        if (code == null) {
            return "案件分析未完成，请检查材料和模型服务";
        }
        return switch (code) {
            case "AUTH_FAILED" -> "模型服务认证失败，请检查并重新保存 API Key";
            case "INSUFFICIENT_BALANCE" -> "模型服务账号余额不足，请在服务商平台检查额度";
            case "MODEL_UNAVAILABLE" -> "模型服务地址或模型名称不可用，请检查 AI 配置";
            case "REQUEST_INVALID" -> "模型服务不支持本次请求参数，请联系部署者检查接口兼容性";
            case "RATE_LIMITED" -> "模型服务限流，本次分析已停止，系统不会自动重复调用";
            case "PROVIDER_UNAVAILABLE" -> "模型服务暂时不可用，本次分析已停止";
            case "MODEL_TIMEOUT" -> "模型响应超时，本次分析已停止；请核对后再决定是否新建任务";
            case "NETWORK_FAILED" -> "无法连接模型服务，请检查网络和服务地址";
            case "OUTPUT_LIMIT" -> "模型输出达到长度上限，未生成完整分析；请缩小本次材料范围后新建任务";
            case "OUTPUT_INVALID" -> "模型返回的分析格式不符合要求，本次结果未被采用";
            case "CITATION_INVALID" -> "模型引用与选定材料原文不一致，本次结果未被采用";
            default -> "案件分析未完成，请检查材料和模型服务";
        };
    }
}
