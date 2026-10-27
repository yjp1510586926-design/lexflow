package com.lexflow.website.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.lexflow.framework.exception.BusinessException;
import com.lexflow.framework.model.ApiResponse;
import com.lexflow.service.CaseService;
import com.lexflow.service.identity.Identity;
import com.lexflow.website.biz.dto.AddCaseMemberDTO;
import com.lexflow.website.biz.dto.ChangeCaseMemberRoleDTO;
import com.lexflow.website.biz.dto.CreateCaseDTO;
import com.lexflow.website.biz.dto.TransferCaseOwnerDTO;
import com.lexflow.website.biz.dto.UpdateCaseDTO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.DateTimeException;
import java.time.LocalDate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 案件及成员管理入口；租户隔离、案件权限和版本冲突由 CaseService 统一校验。 */
@RestController
@RequestMapping("/api/v1")
@Validated
public class CaseController {

    private final CaseService cases;

    public CaseController(CaseService cases) {
        this.cases = cases;
    }

    @GetMapping("/cases")
    ApiResponse<?> list(
        @AuthenticationPrincipal Identity identity,
        @RequestParam(defaultValue = "1") @Min(1) @Max(100000) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
        @RequestParam(defaultValue = "") @Size(max = 100) String keyword,
        @RequestParam(required = false) @Pattern(regexp = "DRAFT|ACTIVE|ARCHIVED") String status,
        @RequestParam(required = false) @Size(max = 64) String type,
        @RequestParam(required = false) @Positive Long ownerId
    ) {
        return ApiResponse.success(
            cases.list(identity, page, size, keyword, status, type, ownerId)
        );
    }

    @PostMapping("/cases")
    ApiResponse<?> create(
        @AuthenticationPrincipal Identity identity,
        @Valid @RequestBody CreateCaseDTO request
    ) {
        return ApiResponse.success(
            cases.create(
                identity,
                request.caseNo(),
                request.title(),
                request.type(),
                request.description(),
                request.filedOn()
            )
        );
    }

    @GetMapping("/cases/owners")
    ApiResponse<?> owners(@AuthenticationPrincipal Identity identity) {
        return ApiResponse.success(cases.owners(identity));
    }

    @GetMapping("/cases/{id}")
    ApiResponse<?> get(@AuthenticationPrincipal Identity identity, @PathVariable long id) {
        return ApiResponse.success(cases.get(identity, id));
    }

    @PatchMapping("/cases/{id}")
    ApiResponse<?> update(
        @AuthenticationPrincipal Identity identity,
        @PathVariable long id,
        @Valid @RequestBody UpdateCaseDTO request
    ) {
        return ApiResponse.success(
            cases.update(
                identity,
                id,
                request.version(),
                request.title(),
                request.description(),
                request.status(),
                // Java null 表示字段缺失；NullNode 表示客户端显式清空日期。
                request.filedOn() != null,
                parseFiledOn(request.filedOn())
            )
        );
    }

    private LocalDate parseFiledOn(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        try {
            if (value.isTextual() && value.asText().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
                return LocalDate.parse(value.asText());
            }
        } catch (DateTimeException ignored) {
            // 格式正确但日期不存在时，统一转为下方的业务参数错误。
        }
        throw BusinessException.badRequest("立案日期须为有效的 YYYY-MM-DD 日期或空值");
    }

    @GetMapping("/cases/{id}/members")
    ApiResponse<?> members(@AuthenticationPrincipal Identity identity, @PathVariable long id) {
        return ApiResponse.success(cases.members(identity, id));
    }

    @PostMapping("/cases/{id}/members")
    ApiResponse<?> member(
        @AuthenticationPrincipal Identity identity,
        @PathVariable long id,
        @Valid @RequestBody AddCaseMemberDTO request
    ) {
        cases.addMember(identity, id, request.userId(), request.role());
        return ApiResponse.success(null);
    }

    @DeleteMapping("/cases/{id}/members/{userId}")
    ApiResponse<?> remove(
        @AuthenticationPrincipal Identity identity,
        @PathVariable long id,
        @PathVariable long userId
    ) {
        cases.removeMember(identity, id, userId);
        return ApiResponse.success(null);
    }

    @PatchMapping("/cases/{id}/members/{userId}")
    ApiResponse<?> role(
        @AuthenticationPrincipal Identity identity,
        @PathVariable long id,
        @PathVariable long userId,
        @Valid @RequestBody ChangeCaseMemberRoleDTO request
    ) {
        cases.changeMemberRole(identity, id, userId, request.role());
        return ApiResponse.success(null);
    }

    @PostMapping("/cases/{id}/owner")
    ApiResponse<?> transfer(
        @AuthenticationPrincipal Identity identity,
        @PathVariable long id,
        @Valid @RequestBody TransferCaseOwnerDTO request
    ) {
        return ApiResponse.success(
            cases.transferOwner(identity, id, request.userId(), request.version())
        );
    }

    @GetMapping("/tenant/users")
    ApiResponse<?> users(@AuthenticationPrincipal Identity identity) {
        return ApiResponse.success(cases.tenantUsers(identity));
    }
}
