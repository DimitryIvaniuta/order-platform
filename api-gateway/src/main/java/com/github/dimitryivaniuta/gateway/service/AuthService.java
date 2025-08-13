package com.github.dimitryivaniuta.gateway.service;

import com.github.dimitryivaniuta.gateway.model.RoleRepository;
import com.github.dimitryivaniuta.gateway.model.UserEntity;
import com.github.dimitryivaniuta.gateway.model.UserRepository;
import com.github.dimitryivaniuta.gateway.model.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    /** Reactive user repository. */
    private final UserRepository userRepository;

    /** Reactive user-role repository (role lookups for token claims). */
    private final UserRoleRepository userRoleRepository;

    private final RoleRepository roleRepository;

    private final UserRoleService userRoleService;

    public Mono<UserEntity> findByUsername(String principal) {
        return userRepository.findByUsernameIgnoreCase(principal)
                .switchIfEmpty(userRepository.findByEmailIgnoreCase(principal));

    }

    public Mono<List<String>> findRoleNamesForUser(Long userId) {
        return userRoleRepository.roleNamesForUser(userId).collectList();
    }

}
