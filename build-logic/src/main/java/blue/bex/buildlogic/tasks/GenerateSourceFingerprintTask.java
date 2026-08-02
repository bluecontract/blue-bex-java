package blue.bex.buildlogic.tasks;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

/** Hashes tracked plus non-ignored untracked source with path-safe framing. */
public abstract class GenerateSourceFingerprintTask extends DefaultTask {
    @Internal
    public abstract DirectoryProperty getRepositoryDirectory();

    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    @TaskAction
    public void generate() {
        File repository = getRepositoryDirectory().get().getAsFile();
        try {
            byte[] names = git(repository, "ls-files", "-z", "--cached",
                    "--others", "--exclude-standard");
            List<byte[]> paths = splitNul(names);
            paths.sort(GenerateSourceFingerprintTask::compareUnsigned);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            int files = 0;
            int symlinks = 0;
            for (byte[] rawPath : paths) {
                String relative = new String(rawPath, StandardCharsets.UTF_8);
                Path path = repository.toPath().resolve(relative);
                byte type;
                byte[] content;
                if (Files.isSymbolicLink(path)) {
                    type = 'L';
                    content = Files.readSymbolicLink(path).toString()
                            .getBytes(StandardCharsets.UTF_8);
                    symlinks++;
                } else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    type = 'F';
                    content = Files.readAllBytes(path);
                    files++;
                } else {
                    throw new GradleException(
                            "Unsupported source path type: " + relative);
                }
                updateInt(digest, rawPath.length);
                digest.update(rawPath);
                digest.update(type);
                updateLong(digest, content.length);
                digest.update(content);
            }
            String head = new String(
                    git(repository, "rev-parse", "HEAD"),
                    StandardCharsets.UTF_8).trim();
            byte[] status = git(repository, "status", "--porcelain", "-z");
            String json = "{\n"
                    + "  \"schema\": \"blue-bex-source-fingerprint/1.0\",\n"
                    + "  \"commit\": \"" + head + "\",\n"
                    + "  \"dirty\": " + (status.length != 0) + ",\n"
                    + "  \"pathCount\": " + paths.size() + ",\n"
                    + "  \"fileCount\": " + files + ",\n"
                    + "  \"symlinkCount\": " + symlinks + ",\n"
                    + "  \"statusSha256\": \"" + hex(sha256(status)) + "\",\n"
                    + "  \"workspaceSha256\": \""
                    + hex(digest.digest()) + "\"\n"
                    + "}\n";
            File output = getOutputFile().get().getAsFile();
            output.getParentFile().mkdirs();
            Files.write(output.toPath(), json.getBytes(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GradleException("Cannot fingerprint BEX source", e);
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new GradleException("Cannot fingerprint BEX source", e);
        }
    }

    private static byte[] git(File repository, String... arguments)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(Arrays.asList(arguments));
        Process process = new ProcessBuilder(command)
                .directory(repository)
                .redirectErrorStream(true)
                .start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = process.getInputStream().read(buffer)) >= 0) {
            output.write(buffer, 0, read);
        }
        if (process.waitFor() != 0) {
            throw new IOException(
                    "git command failed: "
                            + new String(output.toByteArray(), StandardCharsets.UTF_8));
        }
        return output.toByteArray();
    }

    private static List<byte[]> splitNul(byte[] bytes) {
        List<byte[]> result = new ArrayList<>();
        int start = 0;
        for (int index = 0; index < bytes.length; index++) {
            if (bytes[index] == 0) {
                result.add(Arrays.copyOfRange(bytes, start, index));
                start = index + 1;
            }
        }
        return result;
    }

    private static int compareUnsigned(byte[] left, byte[] right) {
        int length = Math.min(left.length, right.length);
        for (int index = 0; index < length; index++) {
            int comparison = Integer.compare(
                    Byte.toUnsignedInt(left[index]),
                    Byte.toUnsignedInt(right[index]));
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(left.length, right.length);
    }

    private static void updateInt(MessageDigest digest, int value)
            throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(4);
        try (DataOutputStream data = new DataOutputStream(bytes)) {
            data.writeInt(value);
        }
        digest.update(bytes.toByteArray());
    }

    private static void updateLong(MessageDigest digest, long value)
            throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(8);
        try (DataOutputStream data = new DataOutputStream(bytes)) {
            data.writeLong(value);
        }
        digest.update(bytes.toByteArray());
    }

    private static byte[] sha256(byte[] bytes) throws NoSuchAlgorithmException {
        return MessageDigest.getInstance("SHA-256").digest(bytes);
    }

    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) {
            value.append(String.format("%02x", item));
        }
        return value.toString();
    }
}
