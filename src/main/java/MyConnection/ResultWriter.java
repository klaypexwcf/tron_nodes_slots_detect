package MyConnection;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public class ResultWriter implements Closeable {
    private final BufferedWriter writer;

    public ResultWriter(Path outputFile) throws IOException {
        this.writer = Files.newBufferedWriter(
                outputFile,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        );
    }

    public synchronized void line(String s) {
        try {
            writer.write(s);
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            throw new RuntimeException("write output failed", e);
        }
    }

    @Override
    public synchronized void close() throws IOException {
        writer.flush();
        writer.close();
    }
}