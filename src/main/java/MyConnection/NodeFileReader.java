package MyConnection;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class NodeFileReader {

    public static List<TcpInfo> readPeers(Path nodesFile) throws IOException {
        List<String> lines = Files.readAllLines(nodesFile, StandardCharsets.UTF_8);
        List<TcpInfo> peers = new ArrayList<>();

        for (String raw : lines) {
            if (raw == null) continue;
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;

            int idx = line.lastIndexOf(':');
            if (idx <= 0 || idx == line.length() - 1) {
                throw new IllegalArgumentException("bad peer line: " + line);
            }

            String host = line.substring(0, idx).trim();
            int port = Integer.parseInt(line.substring(idx + 1).trim());

            InetAddress inetAddress = InetAddress.getByName(host);
            peers.add(new TcpInfo(inetAddress.getHostAddress(), port));
        }

        return peers;
    }
}