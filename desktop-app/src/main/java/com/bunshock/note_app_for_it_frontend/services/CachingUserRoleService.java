package com.bunshock.note_app_for_it_frontend.services;

import java.util.Set;

import com.bunshock.note_app_for_it_frontend.models.Permission;

public class CachingUserRoleService implements IUserRoleService {

    private final IUserRoleService primary;
    private final IUserRoleService local;

    public CachingUserRoleService(IUserRoleService primary, IUserRoleService local) {
        this.primary = primary;
        this.local = local;
    }

    @Override
    public String getRole(String username) {
        try { return primary.getRole(username); } catch (Exception e) { return local.getRole(username); }
    }

    @Override
    public boolean isRegistered(String username) {
        try { return primary.isRegistered(username); } catch (Exception e) { return local.isRegistered(username); }
    }

    @Override
    public Integer getSedeId(String username) {
        try { return primary.getSedeId(username); } catch (Exception e) { return local.getSedeId(username); }
    }

    @Override
    public Set<Permission> getPermissionsForRole(String role) {
        try { return primary.getPermissionsForRole(role); } catch (Exception e) { return local.getPermissionsForRole(role); }
    }
}
