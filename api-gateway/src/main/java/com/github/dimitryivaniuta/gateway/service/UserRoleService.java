package com.github.dimitryivaniuta.gateway.service;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import com.github.dimitryivaniuta.gateway.model.RoleEntity;
import com.github.dimitryivaniuta.gateway.model.RoleRepository;
import com.github.dimitryivaniuta.gateway.model.UserRoleEntity;
import com.github.dimitryivaniuta.gateway.model.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Admin operations for user roles.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserRoleService {

    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;

    /**
     * Idempotently sets a user's roles to the given role names.
     * - Resolves valid roles via findByNameIgnoreCaseIn
     * - Computes additions and removals
     * - Applies changes with assign/unassign
     *
     * @param userId user id
     * @param roleNames target role names
     * @return completion signal
     */
    public Mono<Void> setUserRoles(final Long userId, final Collection<String> roleNames) {
        final Collection<String> requested = roleNames == null ? Set.of() : roleNames;

        return roleRepository.findByNameIgnoreCaseIn(requested)
                .map(RoleEntity::getId)
                .collectList()
                .flatMap(validRoleIds ->
                        userRoleRepository.findByUserId(userId)
                                .map(UserRoleEntity::getRoleId)
                                .collectList()
                                .flatMap(existingRoleIds -> {
                                    Set<Long> toAdd = new HashSet<>(validRoleIds);
//                                    toAdd.removeAll(existingRoleIds);
                                    existingRoleIds.forEach(toAdd::remove);

                                    Set<Long> toRemove = new HashSet<>(existingRoleIds);
//                                    toRemove.removeAll(validRoleIds);
                                    validRoleIds.forEach(toRemove::remove);
                                    Mono<Void> adds = Flux.fromIterable(toAdd)
                                            .flatMap(roleId -> userRoleRepository.assign(userId, roleId))
                                            .then();

                                    Mono<Void> removes = Flux.fromIterable(toRemove)
                                            .flatMap(roleId -> userRoleRepository.unassign(userId, roleId))
                                            .then();

                                    return Mono.when(adds, removes).then();
                                })
                )
                .doOnSuccess(v -> log.info("Updated roles for user={} requested={}", userId, requested));
    }
}
