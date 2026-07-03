package com.bunshock.note_app_for_it_frontend.services;

import com.sun.jna.platform.win32.Secur32;
import com.sun.jna.platform.win32.Secur32Util;

public class WindowsIdentityService {

    private static final WindowsIdentityService INSTANCE = new WindowsIdentityService();

    private WindowsIdentityService() {}

    public static WindowsIdentityService getInstance() {
        return INSTANCE;
    }

    /**
     * Returns the current Windows session's User Principal Name (e.g. "user@domain.com").
     * Only available on a domain-joined machine — returns null on a local/workgroup account
     * or any other native failure, rather than throwing.
     */
    public String getSessionEmail() {
        try {
            String upn = Secur32Util.getUserNameEx(Secur32.EXTENDED_NAME_FORMAT.NameUserPrincipal);
            return upn != null && !upn.isBlank() ? upn : null;
        } catch (Exception notDomainJoined) {
            return null;
        }
    }
}
