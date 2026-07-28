package com.bunshock.note_app_for_it_frontend.services;

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
}
