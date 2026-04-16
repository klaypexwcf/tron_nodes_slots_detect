package MyConnection;

import lombok.Getter;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

@Getter
public class AppConfig {

    private final boolean fileMode;

    private final String localHost;
    private final int localPort;

    private final int batchInsertIntervalSeconds;
    private final int threadPoolCoreSize;
    private final int threadPoolMaxSize;
    private final int threadPoolQueueCapacity;

    private final String dbUrl;
    private final String dbUser;
    private final String dbPassword;
    private final String dbStatusTableName;
    private final String dbOnlineTableName;
    private final int dbBatchSize;
    private final long dbEmptyBatchSleepMillis;

    private final String nodesFile;
    private final String resultFile;
    private final int connectTimeoutMillis;
    private final long successHoldMillis;

    private AppConfig(
            boolean fileMode,
            String localHost,
            int localPort,
            int batchInsertIntervalSeconds,
            int threadPoolCoreSize,
            int threadPoolMaxSize,
            int threadPoolQueueCapacity,
            String dbUrl,
            String dbUser,
            String dbPassword,
            String dbStatusTableName,
            String dbOnlineTableName,
            int dbBatchSize,
            long dbEmptyBatchSleepMillis,
            String nodesFile,
            String resultFile,
            int connectTimeoutMillis,
            long successHoldMillis) {

        this.fileMode = fileMode;

        if (localHost == null || localHost.trim().isEmpty()) {
            throw new IllegalArgumentException("app.local.host 不能为空");
        }
        if (localPort <= 0 || localPort > 65535) {
            throw new IllegalArgumentException("app.local.port 非法");
        }
        if (threadPoolCoreSize <= 0 || threadPoolMaxSize <= 0 || threadPoolMaxSize < threadPoolCoreSize) {
            throw new IllegalArgumentException("线程池参数非法");
        }
        if (threadPoolQueueCapacity <= 0) {
            throw new IllegalArgumentException("app.threadPool.queueCapacity 必须大于 0");
        }
        if (connectTimeoutMillis <= 0) {
            throw new IllegalArgumentException("app.connectTimeoutMillis 必须大于 0");
        }
        if (successHoldMillis < 0) {
            throw new IllegalArgumentException("app.successHoldMillis 不能小于 0");
        }

        if (fileMode) {
            if (nodesFile == null || nodesFile.trim().isEmpty()) {
                throw new IllegalArgumentException("app.input.nodesFile 不能为空");
            }
            if (resultFile == null || resultFile.trim().isEmpty()) {
                throw new IllegalArgumentException("app.output.resultFile 不能为空");
            }
        } else {
            if (batchInsertIntervalSeconds <= 0) {
                throw new IllegalArgumentException("app.batchInsertIntervalSeconds 必须大于 0");
            }
            if (dbUrl == null || dbUrl.trim().isEmpty()) {
                throw new IllegalArgumentException("db.url 不能为空");
            }
            if (dbBatchSize <= 0) {
                throw new IllegalArgumentException("db.batchSize 必须大于 0");
            }
            if (dbEmptyBatchSleepMillis <= 0) {
                throw new IllegalArgumentException("db.emptyBatchSleepMillis 必须大于 0");
            }
        }

        this.localHost = localHost.trim();
        this.localPort = localPort;
        this.batchInsertIntervalSeconds = batchInsertIntervalSeconds;
        this.threadPoolCoreSize = threadPoolCoreSize;
        this.threadPoolMaxSize = threadPoolMaxSize;
        this.threadPoolQueueCapacity = threadPoolQueueCapacity;

        this.dbUrl = trimToEmpty(dbUrl);
        this.dbUser = trimToEmpty(dbUser);
        this.dbPassword = dbPassword == null ? "" : dbPassword;
        this.dbStatusTableName = trimToEmpty(dbStatusTableName);
        this.dbOnlineTableName = trimToEmpty(dbOnlineTableName);
        this.dbBatchSize = dbBatchSize;
        this.dbEmptyBatchSleepMillis = dbEmptyBatchSleepMillis;

        this.nodesFile = trimToEmpty(nodesFile);
        this.resultFile = trimToEmpty(resultFile);
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.successHoldMillis = successHoldMillis;
    }

    public static AppConfig load(String configPath) {
        Path path = Paths.get(configPath);
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("配置文件不存在: " + configPath);
        }

        Properties p = new Properties();
        try (InputStreamReader reader =
                     new InputStreamReader(Files.newInputStream(path), StandardCharsets.UTF_8)) {
            p.load(reader);
        } catch (IOException e) {
            throw new RuntimeException("读取配置文件失败: " + configPath, e);
        }

        return new AppConfig(
                getBoolean(p, "app.fileMode", true),
                required(p, "app.local.host"),
                getInt(p, "app.local.port", 30309),
                getInt(p, "app.batchInsertIntervalSeconds", 15),
                getInt(p, "app.threadPool.coreSize", 20),
                getInt(p, "app.threadPool.maxSize", 50),
                getInt(p, "app.threadPool.queueCapacity", 1000),

                getString(p, "db.url", ""),
                getString(p, "db.user", ""),
                getString(p, "db.password", ""),
                getString(p, "db.statusTableName", "status"),
                getString(p, "db.onlineTableName", "public_online_nodes"),
                getInt(p, "db.batchSize", 50),
                getLong(p, "db.emptyBatchSleepMillis", 10000L),

                getString(p, "app.input.nodesFile", "nodes.txt"),
                getString(p, "app.output.resultFile", "connect_result.txt"),
                getInt(p, "app.connectTimeoutMillis", 5000),
                getLong(p, "app.successHoldMillis", 0L)
        );
    }

    private static String required(Properties p, String key) {
        String value = p.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("缺少配置项: " + key);
        }
        return value.trim();
    }

    private static String getString(Properties p, String key, String defaultValue) {
        String value = p.getProperty(key);
        return value == null ? defaultValue : value.trim();
    }

    private static int getInt(Properties p, String key, int defaultValue) {
        String value = p.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        return Integer.parseInt(value.trim());
    }

    private static long getLong(Properties p, String key, long defaultValue) {
        String value = p.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        return Long.parseLong(value.trim());
    }

    private static boolean getBoolean(Properties p, String key, boolean defaultValue) {
        String value = p.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value.trim());
    }

    private static String trimToEmpty(String s) {
        return s == null ? "" : s.trim();
    }

    public boolean isDbMode() {
        return !fileMode;
    }

}