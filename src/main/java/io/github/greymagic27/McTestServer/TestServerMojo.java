package io.github.greymagic27.McTestServer;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.twdata.maven.mojoexecutor.MojoExecutor.artifactId;
import static org.twdata.maven.mojoexecutor.MojoExecutor.configuration;
import static org.twdata.maven.mojoexecutor.MojoExecutor.executeMojo;
import static org.twdata.maven.mojoexecutor.MojoExecutor.executionEnvironment;
import static org.twdata.maven.mojoexecutor.MojoExecutor.goal;
import static org.twdata.maven.mojoexecutor.MojoExecutor.groupId;
import static org.twdata.maven.mojoexecutor.MojoExecutor.plugin;
import static org.twdata.maven.mojoexecutor.MojoExecutor.version;

@Mojo(name = "mc-test-server", defaultPhase = LifecyclePhase.NONE, threadSafe = true)
public class TestServerMojo extends AbstractMojo {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    @Parameter
    private final List<PluginConfig> plugins = new ArrayList<>();
    @Parameter(defaultValue = "${project.build.directory}", readonly = true)
    private File targetDir;
    @Parameter(defaultValue = "${project.build.finalName}", readonly = true)
    private String finalName;
    @Parameter(defaultValue = "${session}", readonly = true)
    private MavenSession session;
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;
    @Parameter
    private String serverVersion;
    @Parameter
    private BuildPluginManager pluginManager;

    @Override
    public void execute() {
        try {
            run();
        } catch (IOException | InterruptedException | MojoExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    private void run() throws IOException, InterruptedException, MojoExecutionException {
        Path tempDir = Files.createTempDirectory("mc-server-");
        getLog().info("Temp dir: " + tempDir);
        Path pluginsDir = tempDir.resolve("plugins");
        Files.createDirectories(pluginsDir);
        String version = (serverVersion != null && !serverVersion.isBlank()) ? serverVersion : fetchLatestVersion();
        int build = fetchLatestBuild(version);
        Path paperJar = downloadPaper(version, build, tempDir);
        packagePlugin();
        Path pluginJar = targetDir.toPath().resolve(finalName + ".jar");
        Files.copy(pluginJar, pluginsDir.resolve(pluginJar.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        for (PluginConfig plugin : plugins) downloadPlugin(plugin, pluginsDir);
        Files.writeString(tempDir.resolve("eula.txt"), "eula=true\n");
        ProcessBuilder pb = new ProcessBuilder("java", "-Xms2G", "-Xmx4G", "-XX:+UseCompactObjectHeaders", "-XX:+AlwaysPreTouch", "-XX:+UseStringDeduplication", "-XX:+UseZGC", "-jar", paperJar.toString(), "nogui");
        pb.directory(tempDir.toFile());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        handleConsole(process);
        int ec = process.waitFor();
        getLog().info("Server exited with code: " + ec);
        deleteRecursive(tempDir);
    }

    private String fetchLatestVersion() throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create("https://fill.papermc.io/v3/projects/paper")).build();
        HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        JsonNode root = MAPPER.readTree(res.body());
        JsonNode versions = root.get("versions");
        if (versions == null || !versions.isObject()) throw new RuntimeException("Invalid JSON Response: 'versions' is missing or is not an object");
        List<String> majorVersions = new ArrayList<>();
        versions.propertyNames().forEach(v -> {
            if (isStableVersion(v)) majorVersions.add(v);
        });
        if (majorVersions.isEmpty()) throw new RuntimeException("No major versions found");
        majorVersions.sort((v1, v2) -> compareVersions(v2, v1));
        JsonNode patchVersions = versions.get(majorVersions.getFirst());
        List<String> stablePatches = new ArrayList<>();
        for (JsonNode n : patchVersions) {
            String v = n.asString();
            if (isStableVersion(v)) stablePatches.add(v);
        }
        if (stablePatches.isEmpty()) throw new RuntimeException("No stable patch versions found for " + majorVersions.getFirst());
        stablePatches.sort((v1, v2) -> compareVersions(v2, v1));
        String latestVersion = stablePatches.getFirst();
        getLog().info("Latest version " + latestVersion);
        return latestVersion;
    }

    private int fetchLatestBuild(String version) throws IOException, InterruptedException {
        HttpResponse<String> res = HTTP.send(HttpRequest.newBuilder().uri(URI.create("https://fill.papermc.io/v3/projects/paper/versions/" + version + "/builds")).build(), BodyHandlers.ofString());
        JsonNode builds = MAPPER.readTree(res.body());
        int max = -1;
        for (JsonNode b : builds) max = Math.max(max, b.get("id").asInt());
        return max;
    }

    private Path downloadPaper(String version, int build, Path dir) throws IOException, InterruptedException {
        String metaUrl = "https://fill.papermc.io/v3/projects/paper/versions/" + version + "/builds/" + build;
        HttpResponse<String> res = HTTP.send(HttpRequest.newBuilder().uri(URI.create(metaUrl)).build(), HttpResponse.BodyHandlers.ofString());
        JsonNode root = MAPPER.readTree(res.body());
        String downloadUrl = root.get("downloads").get("server:default").get("url").asString();
        Path jar = dir.resolve("paper.jar");
        HTTP.send(HttpRequest.newBuilder().uri(URI.create(downloadUrl)).build(), HttpResponse.BodyHandlers.ofFile(jar));
        return jar;
    }

    private void downloadPlugin(PluginConfig plugin, Path pluginsDir) throws IOException, InterruptedException {
        Path out = pluginsDir.resolve(plugin.name);
        HTTP.send(HttpRequest.newBuilder().uri(URI.create(plugin.url)).build(), HttpResponse.BodyHandlers.ofFile(out));
    }

    private void deleteRecursive(Path path) throws IOException {
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    private boolean isStableVersion(String version) {
        return !version.contains("-");
    }

    private int compareVersions(String v1, String v2) {
        String[] parts1 = v1.split("\\.");
        String[] parts2 = v2.split("\\.");
        int length = Math.max(parts1.length, parts2.length);
        for (int i = 0; i < length; i++) {
            int p1 = i < parts1.length ? Integer.parseInt(parts1[i]) : 0;
            int p2 = i < parts2.length ? Integer.parseInt(parts2[i]) : 0;
            if (p1 != p2) {
                return Integer.compare(p1, p2);
            }
        }
        return 0;
    }

    private void handleConsole(Process process) {
        new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream())); BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println(line);
                    if (line.contains("Done (")) {
                        writer.write("op greymagic27\n");
                        writer.flush();
                    }
                }
            } catch (IOException e) {
                getLog().error("Console error", e);
            }
        }).start();
    }

    private void packagePlugin() throws MojoExecutionException {
        Path pluginJar = targetDir.toPath().resolve(finalName + ".jar");
        List<String> goals = session.getGoals();
        if (Files.exists(pluginJar)) {
            getLog().warn("Plugin JAR already exists: " + pluginJar);
            return;
        }
        if (!Files.exists(pluginJar) && !goals.contains("package")) {
            getLog().warn("Plugin JAR not found, running 'mvn clean package'");
            executeMojo(plugin(groupId("org.apache.maven.plugins"), artifactId("maven-clean-plugin"), version("3.5.0")), goal("clean"), configuration(), executionEnvironment(project, session, pluginManager));
            executeMojo(plugin(groupId("org.apache.maven.plugins"), artifactId("maven-jar-plugin"), version("3.5.0")), goal("package"), configuration(), executionEnvironment(project, session, pluginManager));
        }
        if (!Files.exists(pluginJar)) throw new MojoExecutionException("Plugin JAR still not found after 'mvn clean package: '" + pluginJar);
        getLog().info("Plugin JAR packaged: " + pluginJar);
    }

    public static class PluginConfig {
        public String name;
        public String url;
    }
}
