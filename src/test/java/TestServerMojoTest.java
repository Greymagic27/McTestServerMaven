import io.github.greymagic27.McTestServer.TestServerMojo;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

class TestServerMojoTest {

    private TestServerMojo mojo;
    @TempDir
    private Path tempDir;

    private static Object invokeStatic(String methodName, Class<?>[] paramTypes, Object... args) throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Method m = TestServerMojo.class.getDeclaredMethod(methodName, paramTypes);
        m.setAccessible(true);
        return m.invoke(null, args);
    }

    private static Object invokeInstance(Object target, String methodName, Class<?>[] paramTypes, Object... args) throws Exception {
        Method m = TestServerMojo.class.getDeclaredMethod(methodName, paramTypes);
        m.setAccessible(true);
        try {
            return m.invoke(target, args);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception ex) throw ex;
            throw e;
        }
    }

    private static void setField(Object target, Object value) throws NoSuchFieldException, IllegalAccessException {
        Field f = TestServerMojo.class.getDeclaredField("project");
        f.setAccessible(true);
        f.set(target, value);
    }

    private static MavenProject mockProjectWithBaseDir(Path baseDir) {
        MavenProject project = Mockito.mock(MavenProject.class);
        Mockito.when(project.getBasedir()).thenReturn(baseDir.toFile());
        return project;
    }

    private static void writeJarWithEntry(Path jarPath, String name) throws IOException {
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jarPath.toFile()))) {
            jos.putNextEntry(new JarEntry(name));
            jos.write("test: true".getBytes());
            jos.closeEntry();
        }
    }

    @BeforeEach
    void before() {
        mojo = new TestServerMojo();
    }

    @Test
    void returnsTrueWhenVersionHasNoDash() throws Exception {
        Assertions.assertTrue((Boolean) invokeStatic("isStableVersion", new Class[]{String.class}, "26.2"));
    }

    @Test
    void returnsFalseWhenVersionHasDash() throws Exception {
        Assertions.assertFalse((Boolean) invokeStatic("isStableVersion", new Class[]{String.class}, "26.2-snapshot-1"));
    }

    @Test
    void returnsTrueWhenVersionIsEmpty() throws Exception {
        Assertions.assertTrue((Boolean) invokeStatic("isStableVersion", new Class[]{String.class}, ""));
    }

    @Test
    void returnsZeroWhenVersionsAreEqual() throws Exception {
        Assertions.assertEquals(0, (int) invokeStatic("compareVersions", new Class[]{String.class, String.class}, "26.2", "26.2"));
    }

    @Test
    void returnsPositiveWhenMajorIsGreater() throws Exception {
        Assertions.assertTrue((int) invokeStatic("compareVersions", new Class[]{String.class, String.class}, "26.2", "26.1") > 0);
    }

    @Test
    void returnsNegativeWhenMinorVersionIsLess() throws Exception {
        Assertions.assertTrue((int) invokeStatic("compareVersions", new Class[]{String.class, String.class}, "26.1", "26.2") < 0);
    }

    @Test
    void treatsMissingSegmentsAsZeroWhenLengthDiffers() throws Exception {
        Assertions.assertTrue((int) invokeStatic("compareVersions", new Class[]{String.class, String.class}, "26.1", "26.1.1") < 0);
    }

    @Test
    void returnsMavenWhenPomXmlExists() throws Exception {
        Files.createFile(tempDir.resolve("pom.xml"));
        setField(mojo, mockProjectWithBaseDir(tempDir));
        Assertions.assertEquals("maven", invokeInstance(mojo, "detectBuildTool", new Class[]{}));
    }

    @Test
    void throwsRuntimeExceptionWhenNoBuildFileIsPresent() throws Exception {
        setField(mojo, mockProjectWithBaseDir(tempDir));
        Assertions.assertThrows(RuntimeException.class, () -> invokeInstance(mojo, "detectBuildTool", new Class[]{}));
    }

    @Test
    void deletesNestedDirectoryTreeCompletely() throws Exception {
        Path nested = tempDir.resolve("a/b/c");
        Files.createDirectories(nested);
        Files.writeString(nested.resolve("file.txt"), "content");
        Files.writeString(tempDir.resolve("a/top.txt"), "content");
        invokeInstance(mojo, "deleteRecursive", new Class[]{Path.class}, tempDir);
        Assertions.assertFalse(Files.exists(tempDir));
    }

    @Test
    void doesNothingWhenPathDoesNotExist() {
        Path missing = Path.of(System.getProperty("java.io.tmpdir"), "does-not-exist" + System.nanoTime());
        Assertions.assertFalse(Files.exists(missing));
        Assertions.assertDoesNotThrow(() -> invokeInstance(mojo, "deleteRecursive", new Class[]{Path.class}, missing));
    }

    @Test
    void findsJarContainingPluginYml() throws Exception {
        Path target = tempDir.resolve("target");
        Files.createDirectories(target);
        Path jar = target.resolve("plugin-1.0.0.jar");
        writeJarWithEntry(jar, "plugin.yml");
        setField(mojo, mockProjectWithBaseDir(tempDir));
        Path result = (Path) invokeInstance(mojo, "findPluginJar", new Class[]{});
        Assertions.assertEquals(jar.toRealPath(), result.toRealPath());
    }

    @Test
    void findsJarContainingPaperPluginYml() throws Exception {
        Path target = tempDir.resolve("target");
        Files.createDirectories(target);
        Path jar = target.resolve("plugin-1.0.0.jar");
        writeJarWithEntry(jar, "paper-plugin.yml");
        setField(mojo, mockProjectWithBaseDir(tempDir));
        Path result = (Path) invokeInstance(mojo, "findPluginJar", new Class[]{});
        Assertions.assertEquals(jar.toRealPath(), result.toRealPath());
    }

    @Test
    void picksNewestJarWhenMultipleJarsExist() throws Exception {
        Path target = tempDir.resolve("target");
        Files.createDirectories(target);
        Path older = target.resolve("older.jar");
        Path newer = target.resolve("newer.jar");
        writeJarWithEntry(older, "plugin.yml");
        writeJarWithEntry(newer, "plugin.yml");
        Files.setLastModifiedTime(older, FileTime.fromMillis(1000));
        Files.setLastModifiedTime(newer, FileTime.fromMillis(2000));
        setField(mojo, mockProjectWithBaseDir(tempDir));
        Path result = (Path) invokeInstance(mojo, "findPluginJar", new Class[]{});
        Assertions.assertEquals(newer.toRealPath(), result.toRealPath());
    }

    @Test
    void throwsIOExceptionWhenNoTargetDirectoryExists() throws Exception {
        setField(mojo, mockProjectWithBaseDir(tempDir));
        Assertions.assertThrows(IOException.class, () -> invokeInstance(mojo, "findPluginJar", new Class[]{}));
    }

    @Test
    void throwsIOExceptionWhenTargetExistsButNoValidJar() throws Exception {
        Path target = tempDir.resolve("target");
        Files.createDirectories(target);
        Files.writeString(target.resolve("readme.txt"), "text");
        setField(mojo, mockProjectWithBaseDir(tempDir));
        Assertions.assertThrows(IOException.class, () -> invokeInstance(mojo, "findPluginJar", new Class[]{}));
    }
}