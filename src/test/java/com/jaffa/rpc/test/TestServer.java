package com.jaffa.rpc.test;

import lombok.extern.slf4j.Slf4j;
import org.apache.curator.test.TestingServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.support.AnnotationConfigContextLoader;

import java.io.File;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Slf4j
@SuppressWarnings("squid:S2187")
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = {MainConfig.class}, loader = AnnotationConfigContextLoader.class)
public class TestServer {

    private static TestingServer zkServer;

    static {
        System.setProperty("jaffa.rpc.module.id", "test.server");
        System.setProperty("jaffa.rpc.protocol", "http");
        System.setProperty("jaffa.rpc.zookeeper.connection", "localhost:2181");
    }

    @Autowired
    private PersonServiceClient personService;

    @Autowired
    ClientServiceClient clientService;

    private static String getClassPathFromParent() {
        return System.getProperty("java.class.path", "./*");
    }

    private static String getJavaCmdFromParent() {
        return Objects.isNull(System.getProperty("java.home")) ? "java" : String.format("%s%sbin%sjava", System.getProperty("java.home"), File.separator, File.separator);
    }

    @BeforeAll
    static void setUp() throws Exception {
        zkServer = new TestingServer(2181, true);
    }

    @Test
    public void stage1() {
        log.info("Started {}", new Object() {
        }.getClass().getEnclosingMethod().getName());
        Integer id = personService.add("Test name", "test@mail.com", null)
                .withTimeout(15, TimeUnit.SECONDS)
                .onModule("test.server")
                .executeSync();
        log.info("Resulting id is {}", id);
        Person person = personService.get(id)
                .onModule("test.server")
                .executeSync();
        assertEquals(person.getId(), id);
        log.info(person.toString());
        personService.lol().executeSync();
        personService.lol2("kek").executeSync();
        String name = personService.getName().executeSync();
        log.info("Name: {}", name);
        assertNull(name);
        clientService.lol3("test3")
                .onModule("test.server")
                .executeSync();
        clientService.lol4("test4")
                .onModule("test.server")
                .executeSync();
        clientService.lol4("test4")
                .onModule("test.server")
                .withTimeout(10, TimeUnit.SECONDS)
                .executeAsync(UUID.randomUUID().toString(), ServiceCallback.class);
        personService.get(id)
                .onModule("test.server")
                .executeAsync(UUID.randomUUID().toString(), PersonCallback.class);
        personService.lol2("kek").executeSync();
        try {
            personService.testError()
                    .onModule("test.server")
                    .executeSync();
        } catch (Throwable e) {
            log.error("Exception during sync call:", e);
            assertTrue(e.getMessage().contains("very bad in") || (Objects.nonNull(e.getCause()) && e.getCause().getMessage().contains("very bad in")));
        }
        personService.testError().onModule("test.server").executeAsync(UUID.randomUUID().toString(), PersonCallback.class);
    }

    @Test
    public void stage2() {
        log.info("Started {}", new Object() {
        }.getClass().getEnclosingMethod().getName());
        final String javaCmd = getJavaCmdFromParent();
        final String classpath = getClassPathFromParent();
        final ProcessBuilder proc = new ProcessBuilder(javaCmd, "-Djdk.tls.acknowledgeCloseNotify=true", "-cp", classpath, MainServer.class.getName());
        proc.redirectErrorStream(true);
        proc.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        try {
            Process process = proc.start();
            int returnCode = process.waitFor();
            log.info("Main test server returned {}", returnCode);
            assertEquals(0, returnCode);
        } catch (Exception e) {
            log.error("Exception while launching main.server", e);
        }
    }

    @AfterAll
    public static void tearDown() throws Exception {
        zkServer.stop();
    }
}
