package com.lexflow.website.biz.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * requestId 绑定操作者、原状态版本及费用确认，网络重试应复用相同标识和输入。
 * confirmModelCosts 仅表示确认费用，恢复资格仍由服务端按执行账本和当前权限核验。
 */
public record RecoverTaskDeliveryDTO(
    @Min(1) int stateVersion,
    @NotBlank @Size(max = 36) String requestId,
    boolean confirmModelCosts
) {}
