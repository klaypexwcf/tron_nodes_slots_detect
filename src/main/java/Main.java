import MyConnection.*;
import MyConnection.MySocket.MyPeerClient;
import com.google.protobuf.ByteString;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import org.apache.commons.cli.*;
import org.tron.p2p.discover.Node;
import org.tron.p2p.protos.Discover;
import org.tron.p2p.utils.ByteArray;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Paths;
import java.sql.Connection;
import java.util.List;
import java.util.concurrent.*;

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

    public static void main(String[] args) throws IOException {
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

        try {
            List<TcpInfo> ipList = NodeFileReader.readPeers(Paths.get(config.getNodesFile()));
            if (ipList.isEmpty()) {
                System.err.println("nodes file is empty: " + config.getNodesFile());
                return;
            }
            CountDownLatch latch = new CountDownLatch(ipList.size());
            ResultWriter writer = new ResultWriter(Paths.get(config.getResultFile()));

            writer.line("=== Connect Started ===");
            writer.line("nodesFile=" + Paths.get(config.getNodesFile()).toAbsolutePath());
            writer.line("resultFile=" + Paths.get(config.getResultFile()).toAbsolutePath());
            writer.line("peerCount=" + ipList.size());
            writer.line("");

            for (TcpInfo ipPort : ipList) {
                threadPool.execute(() -> {
                    try {
                        oneTask(ipPort, localNode, localEndpoint, writer, config);
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await();

            writer.line("");
            writer.line("=== Finished ===");
            writer.close();
            threadPool.shutdown();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
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
            Discover.Endpoint localEndpoint,
            ResultWriter writer,
            AppConfig config) {

        MyPeerClient myPeerClient = new MyPeerClient();
        myPeerClient.init();
        Node remoteNode = new Node(new InetSocketAddress(ipPort.getIpv4(), ipPort.getPort()));

        long start = System.currentTimeMillis();
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
                long cost = System.currentTimeMillis() - start;
                if (f.isSuccess()) {
                    writer.line("SUCCESS " + ipPort.getIpv4() + ":" + ipPort.getPort()
                            + " costMs=" + cost);
                } else {
                    String msg = f.cause() == null ? "unknown" : f.cause().getMessage();
                    writer.line("FAIL " + ipPort.getIpv4() + ":" + ipPort.getPort()
                            + " costMs=" + cost
                            + " reason=" + msg);
                }
            });

            future.await(config.getConnectTimeoutMillis());

            if (!future.isDone()) {
                writer.line("TIMEOUT " + ipPort.getIpv4() + ":" + ipPort.getPort()
                        + " timeoutMs=" + config.getConnectTimeoutMillis());
                future.cancel(true);
            } else if (future.channel() != null) {
                future.channel().close().awaitUninterruptibly();
            }

        } catch (Exception e) {
            writer.line("ERROR " + ipPort.getIpv4() + ":" + ipPort.getPort()
                    + " error=" + e.getMessage());
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