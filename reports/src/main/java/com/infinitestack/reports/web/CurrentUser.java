package com.infinitestack.reports.web;

/**
 * Who is calling, as the host told us. The host authenticates the user, strips Cookie and
 * Authorization, and injects X-IS-User-Id, X-IS-Username, X-IS-Role and X-IS-Principal-Type
 * before proxying to the app. There is no organization header (yet).
 */
public record CurrentUser(String id, String username, String role, String principalType) {

    /** ADMIN and SCICROP manage the app: they release Blocks and edit any report. */
    public boolean isAdmin() {
        return "ADMIN".equalsIgnoreCase(role) || "SCICROP".equalsIgnoreCase(role);
    }

    public boolean canEdit(String ownerId) {
        return isAdmin() || (id != null && id.equals(ownerId));
    }
}
