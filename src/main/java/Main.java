import java.net.InetSocketAddress;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.concurrent.*;

import MyConnection.DbUtil;
import MyConnection.MySocket.MyPeerClient;
import MyConnection.MyUtil;
import MyConnection.TcpInfo;
import com.google.protobuf.ByteString;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import org.tron.p2p.discover.Node;
import org.tron.p2p.protos.Discover;
import org.tron.p2p.utils.ByteArray;

public class Main {

    private static final Connection conn1 = DbUtil.getNewConnection();
    private static final Connection conn2 = DbUtil.getNewConnection();
    private static final Node localNode = new Node(MyUtil.generateRandomNodeId(),"10.21.213.106","",30309,30309);
    private static final Discover.Endpoint localEndpoint = getEndpointFromNode(localNode);
    private static final ExecutorService threadPool = new ThreadPoolExecutor(
            5,  // 核心线程数
            5,  // 最大线程数（固定大小）
            0L, TimeUnit.MILLISECONDS, // 线程空闲超时时间（无效，因为核心线程数 = 最大线程数）
            new LinkedBlockingQueue<>(10), // 任务队列（容量 10）
            new ThreadPoolExecutor.CallerRunsPolicy() // 拒绝策略
    );


    private static Discover.Endpoint getEndpointFromNode(Node node) {
        return Discover.Endpoint.newBuilder()
                .setAddress(ByteString.copyFrom(ByteArray.fromString(node.getHostV4())))
                .setNodeId(ByteString.copyFrom(node.getId()))
                .setPort(node.getPort()).build();
    }

    public static void main(String[] args) {
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        scheduler.scheduleAtFixedRate(Main::batchInsert, 0, 15, TimeUnit.SECONDS);

        ArrayList<TcpInfo> ipList = new ArrayList<>();
        try {
            while (true){
                DbUtil.getNewBatch(ipList,conn1);
                for(TcpInfo ipPort : ipList){
                    threadPool.execute(()->{
                        oneTask(ipPort);
                    });
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void oneTask(TcpInfo ipPort) {
        MyPeerClient myPeerClient = new MyPeerClient();
        myPeerClient.init();
        Node remoteNode = new Node(new InetSocketAddress(ipPort.getIpv4(), ipPort.getPort()));
        try {
            int randomPort = MyUtil.getAvailablePort();
            ChannelFuture future = myPeerClient.connectInDiscMode(remoteNode,
                    new Node(MyUtil.generateRandomNodeId(),localNode.getHostV4(),"",randomPort,randomPort)
                    ,localEndpoint,getEndpointFromNode(remoteNode));
            future.addListener((ChannelFutureListener) f -> {
                if (f.isSuccess()) {
                    // 监听 channel 关闭事件
                    f.channel().closeFuture().addListener(cf -> {
                        System.out.println("连接已关闭: " + f.channel().remoteAddress().toString());
                    });
                } else {
                    System.out.println("连接失败: " + f.cause().getMessage());
                }
            });

            // 阻塞主线程，等待关闭
            future.channel().closeFuture().sync();
        }catch (Exception e){
            e.printStackTrace();
        }finally {
            myPeerClient.close();
        }
    }

    private static void batchInsert() {
        DbUtil.batchInsert(conn2);
    }

}
