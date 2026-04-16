import MyConnection.*;
import MyConnection.MySocket.MyPeerClient;
import com.google.protobuf.ByteString;
import io.netty.channel.ChannelFuture;
import org.apache.commons.cli.*;
import org.tron.p2p.discover.Node;
import org.tron.p2p.protos.Discover;
import org.tron.p2p.utils.ByteArray;

import java.net.InetSocketAddress;
import java.nio.file.Paths;
import java.sql.Connection;
import java.util.ArrayList;
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

    public static void main(String[] args) {
        AppConfig config = AppConfig.load(parseConfigPath(args));

        final Node localNode = new Node(
                MyUtil.generateRandomNodeId(),
                config.getLocalHost(),
                "",
                config.getLocalPort(),
                config.getLocalPort()
        );
        final Discover.Endpoint localEndpoint = getEndpointFromNode(localNode);
        final ExecutorService threadPool = buildThreadPool(config);

        Runtime.getRuntime().addShutdownHook(new Thread(threadPool::shutdownNow));

        try {
            if (config.isFileMode()) {
                runFileMode(config, localNode, localEndpoint, threadPool);
            } else {
                runDbMode(config, localNode, localEndpoint, threadPool);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("主线程被中断", e);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            threadPool.shutdownNow();
        }
    }

    private static void runFileMode(
            AppConfig config,
            Node localNode,
            Discover.Endpoint localEndpoint,
            ExecutorService threadPool) throws Exception {

        // 文件模式下虽然不连数据库，但如果你的 DbUtil 其他地方需要 config，
        // 这里初始化一下是安全的，不会创建数据库连接。
        DbUtil.init(config);

        List<TcpInfo> ipList = NodeFileReader.readPeers(Paths.get(config.getNodesFile()));
        if (ipList.isEmpty()) {
            System.err.println("nodes file is empty: " + config.getNodesFile());
            return;
        }

        ScheduledExecutorService statusScheduler = Executors.newScheduledThreadPool(1);

        try (ResultWriter writer = new ResultWriter(Paths.get(config.getResultFile()))) {
            writer.line("=== Connect Started ===");
            writer.line("mode=file");
            writer.line("nodesFile=" + Paths.get(config.getNodesFile()).toAbsolutePath());
            writer.line("resultFile=" + Paths.get(config.getResultFile()).toAbsolutePath());
            writer.line("peerCount=" + ipList.size());
            writer.line("threadPool=" + config.getThreadPoolCoreSize() + "/" + config.getThreadPoolMaxSize());
            writer.line("connectTimeoutMillis=" + config.getConnectTimeoutMillis());
            writer.line("successHoldMillis=" + config.getSuccessHoldMillis());
            writer.line("");

            // 定期把 StatusInfo 队列里的内容刷到输出文件
            statusScheduler.scheduleAtFixedRate(
                    () -> {
                        try {
                            DbUtil.batchWriteToFile(writer);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    },
                    1,
                    1,
                    TimeUnit.SECONDS
            );

            CountDownLatch latch = new CountDownLatch(ipList.size());

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

            // 所有连接任务结束后，再额外 flush 一次，避免最后一批状态没写出来
            DbUtil.batchWriteToFile(writer);

            writer.line("");
            writer.line("=== Finished ===");
        } finally {
            statusScheduler.shutdownNow();
        }
    }

    private static void runDbMode(
            AppConfig config,
            Node localNode,
            Discover.Endpoint localEndpoint,
            ExecutorService threadPool) {

        DbUtil.init(config);

        Connection conn1 = null;
        Connection conn2 = null;
        ScheduledExecutorService scheduler = null;

        try {
            conn1 = DbUtil.getNewConnection();
            conn2 = DbUtil.getNewConnection();
            scheduler = Executors.newScheduledThreadPool(1);

            final Connection finalConn2 = conn2;
            scheduler.scheduleAtFixedRate(
                    () -> batchInsert(finalConn2),
                    0,
                    config.getBatchInsertIntervalSeconds(),
                    TimeUnit.SECONDS
            );

            ArrayList<TcpInfo> ipList = new ArrayList<>();
            while (!Thread.currentThread().isInterrupted()) {
                DbUtil.getNewBatch(ipList, conn1);
                for (TcpInfo ipPort : ipList) {
                    threadPool.execute(() -> oneTask(ipPort, localNode, localEndpoint, null, config));
                }
                ipList.clear();
            }
        } finally {
            if (scheduler != null) {
                scheduler.shutdownNow();
            }
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

        ChannelFuture future = null;
        long start = System.currentTimeMillis();

        try {
            int randomPort = MyUtil.getAvailablePort();

            future = myPeerClient.connectInDiscMode(
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

            boolean completed = future.await(config.getConnectTimeoutMillis());
            long cost = System.currentTimeMillis() - start;

            if (!completed) {
                writeResult(writer, "TIMEOUT " + ipPort.getIpv4() + ":" + ipPort.getPort()
                        + " timeoutMs=" + config.getConnectTimeoutMillis());
                safeCloseChannel(future);
                future.cancel(true);
                return;
            }

            if (future.isSuccess()) {
                writeResult(writer, "SUCCESS " + ipPort.getIpv4() + ":" + ipPort.getPort()
                        + " costMs=" + cost);

                if (config.getSuccessHoldMillis() > 0) {
                    sleepQuietly(config.getSuccessHoldMillis());
                }
            } else {
                Throwable cause = future.cause();
                String msg = cause == null ? "unknown" : cause.getMessage();
                writeResult(writer, "FAIL " + ipPort.getIpv4() + ":" + ipPort.getPort()
                        + " costMs=" + cost
                        + " reason=" + msg);
            }

            safeCloseChannel(future);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            writeResult(writer, "INTERRUPTED " + ipPort.getIpv4() + ":" + ipPort.getPort());
            safeCloseChannel(future);
        } catch (Exception e) {
            writeResult(writer, "ERROR " + ipPort.getIpv4() + ":" + ipPort.getPort()
                    + " error=" + e.getMessage());
            safeCloseChannel(future);
        } finally {
            myPeerClient.close();
        }
    }

    private static void writeResult(ResultWriter writer, String line) {
        if (writer != null) {
            writer.line(line);
        }
    }

    private static void safeCloseChannel(ChannelFuture future) {
        if (future == null) {
            return;
        }
        try {
            if (future.channel() != null) {
                future.channel().close().awaitUninterruptibly();
            }
        } catch (Exception ignored) {
        }
    }

    private static void sleepQuietly(long millis) throws InterruptedException {
        if (millis > 0) {
            Thread.sleep(millis);
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