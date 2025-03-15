package MyConnection;

import lombok.Getter;

@Getter
public class TcpInfo {
    private String ipv4;
    private int port;

    public TcpInfo(String ipv4, int port) {
        this.ipv4 = ipv4;
        this.port = port;
    }
}
