package com.fleetops.security;

import org.springframework.stereotype.Component;

import com.fleetops.common.web.NotFoundException;

/**
 * Which warehouse a signed-in person is limited to. Warehouse accounts see only their own
 * warehouse; dispatchers and admins see everything. Anything outside someone's warehouse is
 * answered as "not found", so its existence is not revealed.
 */
@Component
public class StaffScope {

    private final UserAccountRepository users;

    public StaffScope(UserAccountRepository users) {
        this.users = users;
    }

    /** The warehouse the current user is limited to, or null when they see every warehouse. */
    public Long warehouse() {
        if (!CurrentUser.hasRole(Role.WAREHOUSE) || CurrentUser.hasRole(Role.DISPATCHER)) {
            return null;
        }
        Long id = users.findByUsername(CurrentUser.username()).map(UserAccount::getWarehouseId).orElse(null);
        if (id == null) {
            // A Warehouse account without a warehouse sees nothing rather than everything.
            throw new NotFoundException("Warehouse", "for " + CurrentUser.username());
        }
        return id;
    }

    /** The current Warehouse user's warehouse; for pages only they use. */
    public long ownWarehouse() {
        Long id = warehouse();
        if (id == null) {
            throw new NotFoundException("Page", "warehouse desk");
        }
        return id;
    }

    public void require(Long warehouseId, String what, Object id) {
        Long mine = warehouse();
        if (mine != null && !mine.equals(warehouseId)) {
            throw new NotFoundException(what, id);
        }
    }
}
