import MyConnection.AppConfig;
import MyConnection.DbUtil;
import MyConnection.MySocket.MyPeerClient;
import MyConnection.MyUtil;
import MyConnection.TcpInfo;
import com.google.protobuf.ByteString;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.tron.p2p.discover.Node;
import org.tron.p2p.protos.Discover;
import org.tron.p2p.utils.ByteArray;

import java.net.InetSocketAddress;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class Main {

    private static Discover.Endpoint getEndpointFromNode(Node node) {
        return Discover.Endpoint.newBuilder()
                .setAddress(ByteString.copyFrom(ByteArray.fromString(node.getHostV4())))
                .setNodeId(ByteString.copyFrom(node.getId()))
                .setPort(node.getPort())
                .build();
    }

    private static ExecutorService buildThreadPool(AppConfig config) {
        return new ThreadPoolExecutor(
                config.getThreadPoolCoreSize(),
                config.getThreadPoolMaxSize(),
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(config.getThreadPoolQueueCapacity()),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    private static String parseConfigPath(String[] args) {
        Options options = new Options();
        options.addOption(
                Option.builder("c")
                        .longOpt("config")
                        .hasArg()
                        .argName("file")
                        .required()
                        .desc("配置文件路径，例如 -c config.conf")
                        .build()
        );

        CommandLineParser parser = new DefaultParser();
        HelpFormatter formatter = new HelpFormatter();

        try {
            CommandLine cmd = parser.parse(options, args);
            return cmd.getOptionValue("c");
        } catch (ParseException e) {
            formatter.printHelp("java -jar nodeDetect-1.0-SNAPSHOT-all.jar -c config.conf", options);
            throw new IllegalArgumentException("命令行参数解析失败", e);
        }
    }

    public static void main(String[] args) {
        AppConfig config = AppConfig.load(parseConfigPath(args));
        DbUtil.init(config);

        final Connection conn1 = DbUtil.getNewConnection();
        final Connection conn2 = DbUtil.getNewConnection();
        final Node localNode = new Node(
                MyUtil.generateRandomNodeId(),
                config.getLocalHost(),
                "",
                config.getLocalPort(),
                config.getLocalPort()
        );
        final Discover.Endpoint localEndpoint = getEndpointFromNode(localNode);
        final ExecutorService threadPool = buildThreadPool(config);
        final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            scheduler.shutdownNow();
            threadPool.shutdownNow();
            closeQuietly(conn1);
            closeQuietly(conn2);
        }));

        scheduler.scheduleAtFixedRate(
                () -> batchInsert(conn2),
                0,
                config.getBatchInsertIntervalSeconds(),
                TimeUnit.SECONDS
        );

        ArrayList<TcpInfo> ipList = new ArrayList<>();
        try {
            while (!Thread.currentThread().isInterrupted()) {
                DbUtil.getNewBatch(ipList, conn1);
                for (TcpInfo ipPort : ipList) {
                    threadPool.execute(() -> oneTask(ipPort, localNode, localEndpoint));
                }
                ipList.clear();
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            scheduler.shutdownNow();
            threadPool.shutdownNow();
            closeQuietly(conn1);
            closeQuietly(conn2);
        }
    }

    private static void oneTask(
            TcpInfo ipPort,
            Node localNode,
            Discover.Endpoint localEndpoint) {

        MyPeerClient myPeerClient = new MyPeerClient();
        myPeerClient.init();
        Node remoteNode = new Node(new InetSocketAddress(ipPort.getIpv4(), ipPort.getPort()));

        try {
            int randomPort = MyUtil.getAvailablePort();
            ChannelFuture future = myPeerClient.connectInDiscMode(
                    remoteNode,
                    new Node(
                            MyUtil.generateRandomNodeId(),
                            localNode.getHostV4(),
                            "",
                            randomPort,
                            randomPort
                    ),
                    localEndpoint,
                    getEndpointFromNode(remoteNode)
            );

            future.addListener((ChannelFutureListener) f -> {
                if (f.isSuccess()) {
                    f.channel().closeFuture().addListener(cf ->
                            System.out.println("连接已关闭: " + f.channel().remoteAddress().toString())
                    );
                } else {
                    System.out.println("连接失败: " + f.cause().getMessage());
                }
            });

            future.channel().closeFuture().sync();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            myPeerClient.close();
        }
    }

    private static void batchInsert(Connection conn) {
        DbUtil.batchInsert(conn);
    }

    private static void closeQuietly(Connection conn) {
        if (conn == null) {
            return;
        }
        try {
            conn.close();
        } catch (Exception ignored) {
        }
    }
}