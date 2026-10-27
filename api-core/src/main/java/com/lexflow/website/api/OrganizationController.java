package com.lexflow.website.api;

import com.lexflow.framework.model.ApiResponse;
import com.lexflow.service.OrganizationService;
import com.lexflow.service.identity.Identity;
import com.lexflow.website.biz.dto.CreateOrganizationUserDTO;
import com.lexflow.website.biz.dto.UpdateOrganizationUserDTO;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 律所成员管理入口；身份来自会话，成员状态和角色修改由服务层授权并处理。 */
@RestController
@RequestMapping("/api/v1/organization/users")
public class OrganizationController {

    private final OrganizationService organizations;

    public OrganizationController(OrganizationService organizations) {
        this.organizations = organizations;
    }

    @GetMapping
    ApiResponse<?> list(@AuthenticationPrincipal Identity identity) {
        return ApiResponse.success(organizations.users(identity));
    }

    @PostMapping
    ApiResponse<?> create(
        @AuthenticationPrincipal Identity identity,
        @Valid @RequestBody CreateOrganizationUserDTO request
    ) {
        return ApiResponse.success(
            Map.of(
                "id",
                organizations.createUser(
                    identity,
                    request.username(),
                    request.password(),
                    request.name(),
                    request.role()
                )
            )
        );
    }

    @PatchMapping("/{id}")
    ApiResponse<?> update(
        @AuthenticationPrincipal Identity identity,
        @PathVariable long id,
        @Valid @RequestBody UpdateOrganizationUserDTO request
    ) {
        organizations.changeMembership(identity, id, request.status(), request.role());
        return ApiResponse.success(null);
    }
}
