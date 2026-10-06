package com.chessedu.platform.security;

import com.chessedu.platform.identity.model.PlatformRole;
import com.chessedu.platform.identity.model.Role;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Principal attached to {@link org.springframework.security.core.Authentication}
 * after a valid access token is parsed. Kept small — everything on it came
 * from the token claims, no fresh DB lookup.
 */
public record AuthenticatedUser(
        UUID userId,
        String email,
        UUID schoolId,
        Role role,
        PlatformRole platformRole
) {

    public Collection<? extends GrantedAuthority> authorities() {
        List<GrantedAuthority> out = new ArrayList<>(2);
        if (role != null) {
            out.add(new SimpleGrantedAuthority(role.authority()));
        }
        if (platformRole != null) {
            out.add(new SimpleGrantedAuthority(platformRole.authority()));
        }
        return List.copyOf(out);
    }
}
