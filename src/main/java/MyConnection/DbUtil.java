package MyConnection;

import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

@Slf4j
public class DbUtil {

    private static final Queue<StatusInfo> messageQueue = new ConcurrentLinkedQueue<>();
    private static volatile AppConfig config;
    private static volatile int offset = 0;

    public static synchronized void init(AppConfig appConfig) {
        config = appConfig;
        offset = 0;
    }

    private static AppConfig requireConfig() {
        if (config == null) {
            throw new IllegalStateException("DbUtil 尚未初始化，请先调用 DbUtil.init(config)");
        }
        return config;
    }

    private static String buildBatchInsertSql(AppConfig cfg) {
        return "INSERT INTO " + cfg.getDbStatusTableName()
                + " (ipv4, maxConn, currentConn) "
                + " VALUES (?,?,?) "
                + " ON DUPLICATE KEY UPDATE "
                + " maxConn=VALUES(maxConn), currentConn=VALUES(currentConn)";
    }

    private static String buildGetNodesIpSql(AppConfig cfg) {
        return "SELECT ipv4, tcpPort FROM " + cfg.getDbOnlineTableName()
                + " ORDER BY create_time"
                + " LIMIT ?"
                + " OFFSET ?";
    }

    public static Connection getNewConnection() {
        AppConfig cfg = requireConfig();
        try {
            return DriverManager.getConnection(
                    cfg.getDbUrl(),
                    cfg.getDbUser(),
                    cfg.getDbPassword()
            );
        } catch (SQLException e) {
            System.out.println("failed to get connection " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    public static ArrayList<TcpInfo> getNewBatch(ArrayList<TcpInfo> arrayList, Connection conn) {
        AppConfig cfg = requireConfig();
        String sql = buildGetNodesIpSql(cfg);

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, cfg.getDbBatchSize());
            stmt.setInt(2, offset);

            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                arrayList.add(new TcpInfo(rs.getString("ipv4"), rs.getInt("tcpPort")));
            }

            if (arrayList.isEmpty()) {
                offset = 0;
                Thread.sleep(cfg.getDbEmptyBatchSleepMillis());
                log.info("offset set to zero");
                return getNewBatch(arrayList, conn);
            } else {
                offset += arrayList.size();
                log.info("offset set to {}", offset);
                return arrayList;
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return arrayList;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    public static void batchInsert(Connection conn) {
        AppConfig cfg = requireConfig();
        String sql = buildBatchInsertSql(cfg);

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            int count = 0;

            while (!messageQueue.isEmpty()) {
                StatusInfo statusInfo = messageQueue.poll();
                if (statusInfo == null) {
                    continue;
                }

                stmt.setString(1, statusInfo.getIpv4());
                stmt.setInt(2, statusInfo.getMaxConn());
                stmt.setInt(3, statusInfo.getCurrentConn());
                stmt.addBatch();
                count++;
            }

            stmt.executeBatch();
            log.info("insert {} infos", count);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void addNewStatus(StatusInfo statusInfo) {
        messageQueue.offer(statusInfo);
    }
}