package io.github.greymagic27.McTestServer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

public class AACH_CUSTOM_CONFIG {

    private static String backupContent = null;

    public static void applyCustomConfig(Path pluginDir) throws IOException {
        Path pluginConfig = pluginDir.resolve("advanced-achievements-plugin/config.yml");
        if (!Files.exists(pluginConfig)) {
            Files.createDirectories(pluginConfig.getParent());
            Files.createFile(pluginConfig);
        }
        backupContent = Files.readString(pluginConfig);
        setRestrictCreativeAACH(pluginDir);
        addDisabledCategoriesAACH(pluginDir, List.of("JobsReborn"));
    }

    public static void restoreOriginalConfig(Path pluginDir) throws IOException {
        if (backupContent == null) return;
        Path pluginConfig = pluginDir.resolve("advanced-achievements-plugin/config.yml");
        Files.writeString(pluginConfig, backupContent, StandardOpenOption.TRUNCATE_EXISTING);
        backupContent = null;
    }

    public static void setRestrictCreativeAACH(Path pluginDir) throws IOException {
        Path pluginConfig = pluginDir.resolve("advanced-achievements-plugin/config.yml");
        if (!Files.exists(pluginConfig)) return;
        List<String> lines = Files.readAllLines(pluginConfig);
        boolean found = false;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).trim().startsWith("restrictCreative:")) {
                lines.set(i, "restrictCreative: true");
                found = true;
                break;
            }
        }
        if (!found) lines.add("restrictCreative: true");
        Files.write(pluginConfig, lines, StandardOpenOption.TRUNCATE_EXISTING);
    }

    public static void addDisabledCategoriesAACH(Path pluginDir, List<String> categories) throws IOException {
        Path pluginConfig = pluginDir.resolve("advanced-achievements-plugin/config.yml");
        if (!Files.exists(pluginConfig)) return;
        List<String> lines = Files.readAllLines(pluginConfig);
        boolean inSection = false;
        int sectionIndex = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).trim().startsWith("disabledCategories:")) {
                inSection = true;
                sectionIndex = i;
                break;
            }
        }
        if (inSection) {
            for (String cat : categories) {
                if (lines.stream().noneMatch(l -> l.trim().equals("- " + cat))) {
                    lines.add(sectionIndex + 1, "  - " + cat);
                }
            }
        } else {
            lines.add("disabledCategories:");
            for (String cat : categories) {
                lines.add("  - " + cat);
            }
        }
        Files.write(pluginConfig, lines, StandardOpenOption.TRUNCATE_EXISTING);
    }
}