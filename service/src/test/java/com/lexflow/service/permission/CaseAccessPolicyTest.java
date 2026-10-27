package com.lexflow.service.permission;

import static com.lexflow.service.permission.CaseAccessPolicy.*;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CaseAccessPolicyTest {

    private final Actor member = new Actor(10, 1, true, true, false);

    @Test
    void tenantAdminCannotCrossTenant() {
        assertFalse(
            allows(
                new Actor(10, 1, true, true, true),
                new Resource(2, Status.ACTIVE, Membership.OWNER),
                Action.READ,
                true
            )
        );
    }

    @Test
    void tenantMembershipDoesNotGrantCaseAccess() {
        assertFalse(
            allows(member, new Resource(1, Status.ACTIVE, Membership.NONE), Action.READ, true)
        );
    }

    @Test
    void readerCannotMutate() {
        var r = new Resource(1, Status.ACTIVE, Membership.READER);
        assertTrue(allows(member, r, Action.READ, true));
        for (var action : Action.values())
            if (action != Action.READ) assertFalse(allows(member, r, action, true));
    }

    @Test
    void archivedCaseAllowsOnlyReadAndOwnerRestore() {
        var r = new Resource(1, Status.ARCHIVED, Membership.OWNER);
        assertTrue(allows(member, r, Action.READ, true));
        assertTrue(allows(member, r, Action.RESTORE, true));
        assertFalse(allows(member, r, Action.UPLOAD, true));
        assertFalse(allows(member, r, Action.MANAGE_MEMBERS, true));
    }

    @Test
    void disabledAndRevokedUsersLoseAccess() {
        var r = new Resource(1, Status.ACTIVE, Membership.OWNER);
        assertFalse(allows(new Actor(10, 1, false, true, true), r, Action.READ, true));
        assertFalse(allows(new Actor(10, 1, true, false, true), r, Action.READ, true));
    }

    @Test
    void operationPermissionIsRequiredEvenForOwner() {
        assertFalse(
            allows(member, new Resource(1, Status.ACTIVE, Membership.OWNER), Action.UPDATE, false)
        );
    }

    @Test
    void editorMayUploadButNotGrantAccess() {
        var r = new Resource(1, Status.ACTIVE, Membership.EDITOR);
        assertTrue(allows(member, r, Action.UPLOAD, true));
        assertFalse(allows(member, r, Action.MANAGE_MEMBERS, true));
    }
}
