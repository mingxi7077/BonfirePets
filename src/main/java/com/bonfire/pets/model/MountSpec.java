package com.bonfire.pets.model;

public record MountSpec(
        boolean mountable,
        String mountType,
        boolean flying,
        boolean controllable,
        boolean canJump,
        boolean canDismountBySelf,
        boolean damageDismount,
        String mountPermission
) {
}
