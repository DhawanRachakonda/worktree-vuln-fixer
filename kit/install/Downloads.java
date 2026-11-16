import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Downloading and unpacking a program safely, for {@code appmod setup}. JDK only, no dependencies, no {@code package} line.
 * Only HTTPS is used (a loopback address may use plain HTTP: it is how the tests serve a stand-in), the hash of the archive is compared
 * before anything is unpacked, an archive entry that would leave the target folder stops the unpacking, and only the one wanted program is taken.
 */
public final class Downloads {

    /** A download or unpacking that must stop; the text is shown to the person. */
    static final class DownloadProblem extends Exception {
        private static final long serialVersionUID = 1L;

        DownloadProblem(String message) {
            super(message);
        }
    }

    private Downloads() {
    }

    /** Why an address may not be downloaded from, or null: HTTPS, or HTTP only to a loopback host. */
    static String addressProblem(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        boolean loopback = host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]") || host.equals("::1");
        if (scheme.equals("https") || (scheme.equals("http") && loopback)) {
            return null;
        }
        return "only HTTPS addresses are downloaded from (a loopback address may use HTTP), but this is: " + uri;
    }

    /** Downloads {@code uri} to {@code target}. The partial file is deleted when it fails. */
    static void download(URI uri, Path target) throws DownloadProblem {
        String problem = addressProblem(uri);
        if (problem != null) {
            throw new DownloadProblem(problem);
        }
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(20)).build();
        try {
            Files.createDirectories(target.getParent());
            HttpResponse<InputStream> response = client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(5)).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                throw new DownloadProblem("%s answered HTTP %d".formatted(uri, response.statusCode()));
            }
            String finalProblem = addressProblem(response.uri());
            if (finalProblem != null) {
                throw new DownloadProblem("the download was redirected: " + finalProblem);
            }
            try (InputStream in = response.body()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            deleteQuietly(target);
            throw new DownloadProblem("%s could not be downloaded: %s".formatted(uri, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            deleteQuietly(target);
            throw new DownloadProblem("the download of %s was interrupted".formatted(uri));
        } catch (DownloadProblem e) {
            deleteQuietly(target);
            throw e;
        }
    }

    static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            for (int read; (read = in.read(buffer)) > 0;) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Compares the archive's hash with the pinned one; a different hash deletes the archive and says both. */
    static void verify(Path archive, String expectedSha256) throws DownloadProblem, IOException {
        if (expectedSha256 == null || !expectedSha256.matches("[0-9a-fA-F]{64}")) {
            throw new DownloadProblem("no pinned SHA-256 for this download: an unverified download is never made");
        }
        String actual = sha256(archive);
        if (!actual.equalsIgnoreCase(expectedSha256)) {
            deleteQuietly(archive);
            throw new DownloadProblem("the downloaded file has the SHA-256 %s but %s was expected; it was deleted and nothing was unpacked"
                    .formatted(actual, expectedSha256.toLowerCase(Locale.ROOT)));
        }
    }

    /**
     * Unpacks only {@code wantedName} (a file name, wherever it is in the archive) into {@code targetFolder}. An entry whose path is absolute,
     * has a drive, or leaves the folder with {@code ..} stops everything, also when it is not the wanted one. The archive is deleted afterwards.
     *
     * @return the unpacked program
     */
    static Path extractOnly(Path archive, Path targetFolder, String wantedName) throws DownloadProblem, IOException {
        Files.createDirectories(targetFolder);
        Path root = targetFolder.toAbsolutePath().normalize();
        Path result = null;
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                String name = entry.getName().replace('\\', '/');
                if (name.startsWith("/") || name.matches("^[A-Za-z]:.*") || java.util.Arrays.asList(name.split("/")).contains("..")
                        || !root.resolve(name).normalize().startsWith(root)) {
                    throw new DownloadProblem("the archive holds an entry that would leave the target folder (%s); nothing was unpacked".formatted(MachineConfig.printable(name)));
                }
                if (!entry.isDirectory() && Path.of(name).getFileName().toString().equalsIgnoreCase(wantedName) && result == null) {
                    Path out = root.resolve(wantedName);
                    try (var sink = Files.newOutputStream(out)) {
                        zip.transferTo(sink);
                    }
                    result = out;
                }
            }
        } catch (DownloadProblem e) {
            deleteQuietly(root.resolve(wantedName));
            throw e;
        }
        Files.deleteIfExists(archive);
        if (result == null) {
            throw new DownloadProblem("the archive does not contain %s".formatted(wantedName));
        }
        return result;
    }

    static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException _) {
            // nothing more can be done for a file that cannot be deleted
        }
    }
}
