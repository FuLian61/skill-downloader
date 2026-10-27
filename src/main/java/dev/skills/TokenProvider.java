package dev.skills;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@FunctionalInterface
public interface TokenProvider {
    String token() throws IOException;

    default String refresh() throws IOException { return null; }

    static TokenProvider rotatingFile(Path path) {
        return new TokenProvider() {
            public String token() throws IOException {
                if (Files.size(path) > 32768) throw new IOException("Token 文件过大");
                return Files.readString(path).strip();
            }
            public String refresh() throws IOException { return token(); }
        };
    }
}
