package io.github.greymagic27.McTestServer;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Mojo(name = "mc-test-server", defaultPhase = LifecyclePhase.NONE, threadSafe = true)
public class TestServerMojo extends AbstractMojo {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Parameter
    private String serverVersion;

    @Parameter
    private List<PluginConfig> additionalPlugins = new ArrayList<>();

    @Parameter(defaultValue = "${project}", readonly = true)
    private MavenProject project;

    private static boolean isStableVersion(String v) {
        return !v.contains("-");
    }

    private static int compareVersions(String v1, String v2) {
        String[] p1 = v1.split("\\.");
        String[] p2 = v2.split("\\.");
        int len = Math.max(p1.length, p2.length);
        for (int i = 0; i < len; i++) {
            int n1 = i < p1.length ? Integer.parseInt(p1[i]) : 0;
            int n2 = i < p2.length ? Integer.parseInt(p2[i]) : 0;
            if (n1 != n2) return Integer.compare(n1, n2);
        }
        return 0;
    }

    @Override
    public void execute() throws MojoExecutionException {
        try {
            run();
        } catch (IOException | InterruptedException | ExecutionException e) {
            throw new MojoExecutionException("Failed to run test server", e);
        }
    }

    private void run() throws IOException, InterruptedException, ExecutionException, MojoExecutionException {
        Path tempServerDir = Files.createTempDirectory("mc-server-");
        getLog().info("Temp server directory: " + tempServerDir);
        Path pluginDir = tempServerDir.resolve("plugins");
        Files.createDirectories(pluginDir);
        String mcVersion = (serverVersion != null && !serverVersion.isBlank()) ? serverVersion : fetchLatestVersion();
        int build = fetchLatestBuild(mcVersion);
        CompletableFuture<Void> packageFuture = CompletableFuture.runAsync(() -> {
            try {
                packagePlugin();
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException("Failed to package plugin", e);
            }
        });
        CompletableFuture<Path> paperFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return downloadPaper(mcVersion, build, tempServerDir);
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException("Failed to download PaperMC", e);
            }
        });
        try {
            CompletableFuture.allOf(packageFuture, paperFuture).join();
        } catch (RuntimeException e) {
            throw new MojoExecutionException("Async task failed", e.getCause());
        }
        Path pluginJar = findPluginJar();
        if (pluginJar == null) throw new RuntimeException("Plugin JAR not found after packaging");
        Files.copy(pluginJar, pluginDir.resolve(pluginJar.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        for (PluginConfig plugin : additionalPlugins) downloadPlugin(plugin, pluginDir);
        Path paperJar = paperFuture.get();
        Files.writeString(tempServerDir.resolve("eula.txt"), "eula=true\n", StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        ProcessBuilder pb = new ProcessBuilder("java", "-Xmx2G", "-jar", paperJar.toString(), "nogui");
        pb.directory(tempServerDir.toFile());
        pb.redirectErrorStream(true);
        Process serverProcess = pb.start();
        handleConsole(serverProcess);
        int exitCode = serverProcess.waitFor();
        if (exitCode != 0) throw new MojoExecutionException("Test server failed to start, exit code: " + exitCode);
        deleteRecursive(tempServerDir);
    }

    private void packagePlugin() throws IOException, InterruptedException {
        getLog().info("Packaging plugin using Maven...");
        String mvnCmd = System.getProperty("os.name").toLowerCase().contains("win") ? "mvn.cmd" : "mvn";
        Path pluginProjectDir = project.getBasedir().toPath().resolve(".");
        ProcessBuilder pb = new ProcessBuilder(mvnCmd, "clean", "package");
        pb.directory(pluginProjectDir.toFile());
        pb.inheritIO();
        Process mvnProcess = pb.start();
        boolean finished = mvnProcess.waitFor(2, TimeUnit.MINUTES);
        if (!finished || mvnProcess.exitValue() != 0) {
            throw new RuntimeException("Maven packaging failed");
        }
        getLog().info("Plugin packaged successfully");
    }

    private Path findPluginJar() throws IOException {
        Path base = project.getBasedir().toPath();
        try (Stream<Path> files = Files.walk(base)) {
            return files.filter(f -> f.getFileName().toString().endsWith(".jar")).filter(f -> {
                try (JarFile jar = new JarFile(f.toFile())) {
                    return jar.getEntry("plugin.yml") != null || jar.getEntry("paper-plugin.yml") != null;
                } catch (IOException e) {
                    return false;
                }

            }).findFirst().orElseThrow(() -> new IOException("No valid plugin JAR with plugin.yml found in project directories under " + base));
        }
    }

    private String fetchLatestVersion() throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create("https://fill.papermc.io/v3/projects/paper")).build();
        HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        JsonNode root = MAPPER.readTree(res.body());
        JsonNode versions = root.get("versions");
        List<String> stableVersions = new ArrayList<>();
        for (JsonNode vNode : versions) {
            if (vNode.isArray()) {
                for (JsonNode inner : vNode) {
                    String version = inner.asString();
                    if (isStableVersion(version)) stableVersions.add(version);
                }
            } else if (vNode.isString()) {
                String version = vNode.asString();
                if (isStableVersion(version)) stableVersions.add(version);
            }
        }
        stableVersions.sort((v1, v2) -> compareVersions(v2, v1));
        String latest = stableVersions.getFirst();
        getLog().info("Latest PaperMC version: " + latest);
        return latest;
    }

    private int fetchLatestBuild(String version) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create("https://fill.papermc.io/v3/projects/paper/versions/" + version + "/builds")).build();
        HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        JsonNode builds = MAPPER.readTree(res.body());
        int max = -1;
        for (JsonNode b : builds) {
            int id = b.get("id").asInt();
            max = Math.max(max, id);
        }
        return max;
    }

    private Path downloadPaper(String version, int build, Path dir) throws IOException, InterruptedException {
        String metaUrl = "https://fill.papermc.io/v3/projects/paper/versions/" + version + "/builds/" + build;
        HttpResponse<String> res = HTTP.send(HttpRequest.newBuilder().uri(URI.create(metaUrl)).build(), HttpResponse.BodyHandlers.ofString());
        JsonNode downloads = MAPPER.readTree(res.body()).get("downloads");
        String downloadUrl = downloads.get("server:default").get("url").asString();
        Path jarPath = dir.resolve("paper.jar");
        HTTP.send(HttpRequest.newBuilder().uri(URI.create(downloadUrl)).build(), HttpResponse.BodyHandlers.ofFile(jarPath));
        return jarPath;
    }

    private void handleConsole(Process process) {
        new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream())); BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println(line);
                    if (line.contains("Done (")) {
                        writer.write("op Greymagic27\n");
                        writer.flush();
                    }
                }
            } catch (IOException e) {
                getLog().error("Console error", e);
            }
        }).start();
    }

    private void deleteRecursive(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    getLog().warn("Failed to delete " + p, e);
                }
            });
        }
    }

    private void downloadPlugin(PluginConfig plugin, Path pluginDir) throws IOException, InterruptedException {
        Path out = pluginDir.resolve(plugin.name.endsWith(".jar") ? plugin.name : plugin.name + ".jar");
        getLog().info("Downloading additional plugin: " + plugin.name + " from " + plugin.url);
        HTTP.send(HttpRequest.newBuilder().uri(URI.create(plugin.url)).build(), HttpResponse.BodyHandlers.ofFile(out));
    }

    private static class PluginConfig {
        @Parameter(required = true)
        public String name;
        @Parameter(required = true)
        public String url;
    }
}