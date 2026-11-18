import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SmokeTest {
    public static void main(String[] args) throws Exception {
        Path sock = args.length > 0
                ? Path.of(args[0])
                : Path.of(System.getenv("LOCALAPPDATA"), "monitord", "monitor.sock");
        int want = args.length > 1 ? Integer.parseInt(args[1]) : 6;
        System.out.println("Java " + Runtime.version() + " -> " + sock);

        boolean helloFirst = false, pong = false, seqIncreasing = true;
        int snapshots = 0;
        long lastSeq = -1;
        Pattern seqPat = Pattern.compile("\"seq\":(\\d+)");

        try (SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            ch.connect(UnixDomainSocketAddress.of(sock));
            System.out.println("connected");

            // Give up after 15 s instead of hanging forever.
            Thread watchdog = new Thread(() -> {
                try { Thread.sleep(15_000); ch.close(); } catch (Exception ignored) { }
            });
            watchdog.setDaemon(true);
            watchdog.start();

            // Client -> daemon direction: send a ping.
            ByteBuffer ping = StandardCharsets.UTF_8.encode("{\"type\":\"ping\",\"id\":7}\n");
            while (ping.hasRemaining()) ch.write(ping);

            // Daemon -> client direction: read NDJSON lines.
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(Channels.newInputStream(ch), StandardCharsets.UTF_8));
            for (int i = 1; i <= want; i++) {
                String line = in.readLine();
                if (line == null) { System.out.println("the daemon closed the connection"); break; }
                System.out.printf("%d: %s%n", i,
                        line.length() > 150 ? line.substring(0, 150) + "..." : line);

                if (i == 1) helloFirst = line.contains("\"type\":\"hello\"");
                if (line.contains("\"type\":\"pong\"") && line.contains("\"id\":7")) pong = true;
                if (line.contains("\"type\":\"snapshot\"")) {
                    Matcher m = seqPat.matcher(line);
                    if (m.find()) {
                        long seq = Long.parseLong(m.group(1));
                        if (seq <= lastSeq) seqIncreasing = false;
                        lastSeq = seq;
                        snapshots++;
                    }
                }
            }
        } catch (ClosedChannelException e) {
            System.out.println("timed out: nothing arrived for 15 s");
            return;
        }

        System.out.printf("hello first: %s | pong for id 7: %s | snapshots: %d (seq increasing: %s)%n",
                helloFirst, pong, snapshots, seqIncreasing);
        System.out.println(helloFirst && pong && snapshots >= 2 && seqIncreasing ? "PASS" : "FAIL");
    }
}