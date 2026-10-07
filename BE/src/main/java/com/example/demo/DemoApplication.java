package com.example.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.Executor;

@EnableAsync
@SpringBootApplication
public class DemoApplication {

    private static FileChannel instanceLockChannel;
    private static FileLock instanceLock;

    public static void main(String[] args) {
        if (!acquireSingleInstanceLock()) {
            System.err.println("DemoApplication is already running. Stop the existing VS Code session before starting another one.");
            return;
        }

        Runtime.getRuntime().addShutdownHook(new Thread(DemoApplication::releaseSingleInstanceLock));
        SpringApplication.run(DemoApplication.class, args);
    }

    private static boolean acquireSingleInstanceLock() {
        Path lockFile = Path.of(System.getProperty("java.io.tmpdir"), "sms-demo-application-18083.lock");
        try {
            instanceLockChannel = FileChannel.open(
                    lockFile,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE
            );
            instanceLock = instanceLockChannel.tryLock();
            if (instanceLock == null) {
                closeLockChannel();
                return false;
            }
            return true;
        } catch (OverlappingFileLockException ex) {
            closeLockChannel();
            return false;
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to create the DemoApplication single-instance lock", ex);
        }
    }

    private static synchronized void releaseSingleInstanceLock() {
        if (instanceLock != null) {
            try {
                instanceLock.release();
            } catch (IOException ignored) {
                // The operating system also releases the lock when the JVM exits.
            }
            instanceLock = null;
        }
        closeLockChannel();
    }

    private static void closeLockChannel() {
        if (instanceLockChannel != null) {
            try {
                instanceLockChannel.close();
            } catch (IOException ignored) {
                // Nothing else can be done during shutdown.
            }
            instanceLockChannel = null;
        }
    }

    @Bean(name = "taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("parse-");
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(200);
        executor.initialize();
        return executor;
    }
}
