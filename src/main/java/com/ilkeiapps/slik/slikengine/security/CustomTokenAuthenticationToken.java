package com.ilkeiapps.slik.slikengine.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;

public class CustomTokenAuthenticationToken extends AbstractAuthenticationToken {

    private final String token;

    public CustomTokenAuthenticationToken(String token) {
        super(null);
        this.token = token;
        super.setAuthenticated(true);
    }

    public CustomTokenAuthenticationToken(String token, Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.token = token;
        super.setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public Object getPrincipal() {
        return token;
    }
}
