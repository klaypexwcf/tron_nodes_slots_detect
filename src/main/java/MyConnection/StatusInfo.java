package MyConnection;

import lombok.Getter;

@Getter
public class StatusInfo {
    private final String ipv4;
    private final int currentConn;
    private final int maxConn;

    public StatusInfo(String ipv4, int currentConn, int maxConn) {
        this.ipv4 = ipv4;
        this.currentConn = currentConn;
        this.maxConn = maxConn;
    }
}
