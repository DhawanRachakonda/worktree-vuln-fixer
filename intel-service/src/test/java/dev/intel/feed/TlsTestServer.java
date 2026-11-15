package dev.intel.feed;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * A local HTTPS server with a throw-away self-signed certificate for {@code localhost}, so fetcher tests exercise the
 * real TLS path. Clients trust only that certificate, through {@link #clientContext()}.
 */
final class TlsTestServer implements AutoCloseable {

    record Request(String path, Map<String, List<String>> headers) {
        String header(String name) {
            return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name)).findFirst()
                    .map(e -> e.getValue().get(0)).orElse(null);
        }
    }

    @FunctionalInterface
    interface Handler {
        void handle(HttpExchange exchange) throws Exception;
    }

    private static final char[] PASSWORD = "changeit".toCharArray();

    private final HttpsServer server;
    private final SSLContext clientContext;
    private final List<Request> requests = new CopyOnWriteArrayList<>();

    private TlsTestServer(HttpsServer server, SSLContext clientContext) {
        this.server = server;
        this.clientContext = clientContext;
    }

    static TlsTestServer start(Path dir) throws Exception {
        Path keystore = dir.resolve("test-server.p12");
        List<String> keytool = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "test", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
                "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1",
                "-keystore", keystore.toString(), "-storetype", "PKCS12",
                "-storepass", new String(PASSWORD), "-keypass", new String(PASSWORD)));
        Process process = new ProcessBuilder(keytool).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) {
            throw new IllegalStateException("keytool failed: " + output);
        }

        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystore)) {
            store.load(in, PASSWORD);
        }
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, PASSWORD);
        SSLContext serverContext = SSLContext.getInstance("TLS");
        serverContext.init(keys.getKeyManagers(), null, null);

        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(store);
        SSLContext clientContext = SSLContext.getInstance("TLS");
        clientContext.init(null, trust.getTrustManagers(), null);

        HttpsServer server = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(serverContext));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        return new TlsTestServer(server, clientContext);
    }

    void route(String path, Handler handler) {
        server.createContext(path, exchange -> {
            requests.add(new Request(exchange.getRequestURI().getPath(), Map.copyOf(exchange.getRequestHeaders())));
            try {
                handler.handle(exchange);
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException(e);
            } finally {
                exchange.close();
            }
        });
    }

    int port() {
        return server.getAddress().getPort();
    }

    URI uri(String path) {
        return URI.create("https://localhost:" + port() + path);
    }

    SSLContext clientContext() {
        return clientContext;
    }

    List<Request> requests() {
        return requests;
    }

    void clearRequests() {
        requests.clear();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
