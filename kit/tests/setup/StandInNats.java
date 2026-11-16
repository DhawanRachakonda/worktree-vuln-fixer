import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A stand-in for nats-server, for the tests of appmod start only: it listens where it is told ({@code -a <address> -p <port>}), sends
 * the {@code INFO} line a NATS server sends on connect, and answers {@code PING} with {@code PONG}. It writes the arguments it got to
 * the file named by the environment variable STANDIN_LOG when that is set. It is not NATS.
 */
public final class StandInNats {

    public static void main(String[] args) throws Exception {
        String address = "0.0.0.0";
        int port = 4222;
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals("-a")) {
                address = args[i + 1];
            } else if (args[i].equals("-p")) {
                port = Integer.parseInt(args[i + 1]);
            }
        }
        String log = System.getenv("STANDIN_LOG");
        if (log != null) {
            Files.writeString(Path.of(log), "address=" + address + " port=" + port + System.lineSeparator());
        }
        try (ServerSocket server = new ServerSocket(port, 20, InetAddress.getByName(address))) {
            while (true) {
                Socket socket = server.accept();
                Thread.ofVirtual().start(() -> serve(socket));
            }
        }
    }

    static void serve(Socket socket) {
        try (socket) {
            socket.getOutputStream().write("INFO {\"server_name\":\"stand-in\",\"version\":\"0.0.0\"}\r\n".getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            for (String line; (line = in.readLine()) != null;) {
                if (line.startsWith("PING")) {
                    socket.getOutputStream().write("PONG\r\n".getBytes(StandardCharsets.UTF_8));
                    socket.getOutputStream().flush();
                }
            }
        } catch (Exception _) {
            // the client went away
        }
    }
}
