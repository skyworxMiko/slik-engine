package com.ilkeiapps.slik.slikengine.security;

import com.ilkeiapps.slik.slikengine.service.AuthService;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collections;
import java.util.List;

public class CustomTokenAuthenticationProvider implements AuthenticationProvider {

    private final AuthService authService;

    public CustomTokenAuthenticationProvider(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws BadCredentialsException {
        String token = (String) authentication.getPrincipal();

        if (Boolean.TRUE.equals(authService.isValidToken(token))) {
            List<GrantedAuthority> authorities = Collections.emptyList(); // Define your authorities
            return new CustomTokenAuthenticationToken(token, authorities);
        } else {
            throw new BadCredentialsException("Invalid token");
        }
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return CustomTokenAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
