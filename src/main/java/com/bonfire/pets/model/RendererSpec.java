package com.bonfire.pets.model;

import java.util.List;

public record RendererSpec(
        String mythicMobId,
        String betterModelId,
        String nameBone,
        String iconMaterial,
        Integer iconCustomModelData,
        String iconTextureBase64,
        String iconName,
        List<String> iconLore,
        String iconYaml,
        String skinsYaml
) {

    public RendererSpec {
        iconLore = iconLore == null ? List.of() : List.copyOf(iconLore);
    }
}
