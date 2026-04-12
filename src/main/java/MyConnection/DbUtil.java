package MyConnection;

import lombok.extern.slf4j.Slf4j;

import java.sql.*;
import java.util.ArrayList;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
@Slf4j
public class DbUtil {
    private static final Queue<StatusInfo> messageQueue = new ConcurrentLinkedQueue<>();
    public static String database = "tron";
    public static String url = "jdbc:mysql://81.70.23.5:3306/"+database+"?connectTimeout=5000";
    public static String user = "tron";
    public static String password = "Wcf314159";
    public static String statusTableName = "status";
    public static String onlineTableName = "public_online_nodes";
    public static int BATCH_SIZE = 50;
    public static int offset = 0;
    public static String batchInsert = "INSERT INTO "+ statusTableName +
            " (ipv4, maxConn, currentConn) " +
            " VALUES (?,?,?) " +
            " ON DUPLICATE KEY UPDATE" +
            " maxConn=VALUES(maxConn), currentConn=VALUES(currentConn)";
    public static String getNodesIp = "Select ipv4, tcpPort FROM "+onlineTableName+
            " ORDER BY create_time" +
            " LIMIT ?" +
            " OFFSET ?";
    public static Connection getNewConnection() {
        Connection conn;
        try {
            conn = DriverManager.getConnection(url, user, password);
        } catch (SQLException e) {
            System.out.println("failed to get connection "+e.getMessage());
            e.printStackTrace();
            throw new RuntimeException(e);
        }
        return conn;
    }
    public static ArrayList<TcpInfo> getNewBatch(ArrayList<TcpInfo> arrayList,Connection conn) {
        try(PreparedStatement stmt = conn.prepareStatement(getNodesIp)) {
            stmt.setInt(1,BATCH_SIZE);
            stmt.setInt(2,offset);
            ResultSet rs = stmt.executeQuery();
            while(rs.next()) {
                arrayList.add(new TcpInfo(rs.getString("ipv4"),rs.getInt("tcpPort")));
            }
            if(arrayList.isEmpty()) {
                offset = 0;
                Thread.sleep(10_000);
                log.info("offset set to zero");
                return getNewBatch(arrayList,conn);
            }
            else{
                offset += arrayList.size();
                log.info("offset set to {}",offset);
                return arrayList;
            }
        }catch (SQLException e) {
            e.printStackTrace();
            return arrayList;
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
    public static void batchInsert(Connection conn){
        try(PreparedStatement stmt = conn.prepareStatement(batchInsert)){
            int count = 0;
            while(!messageQueue.isEmpty()){
                StatusInfo statusInfo = messageQueue.poll();
                stmt.setString(1,statusInfo.getIpv4());
                stmt.setInt(2,statusInfo.getMaxConn());
                stmt.setInt(3,statusInfo.getCurrentConn());
                stmt.addBatch();
                count++;
            }
            stmt.executeBatch();
            log.info("insert {} infos", count);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    public static void addNewStatus(StatusInfo statusInfo){
        messageQueue.offer(statusInfo);
    }
}
