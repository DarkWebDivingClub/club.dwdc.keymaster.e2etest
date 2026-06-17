package club.dwdc.keymaster.e2etest;

import club.dwdc.keymaster.AvatarDescriptor;
import club.dwdc.keymaster.KeyMaster;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E test: KeyMaster attach/detach with Avatar over a Nostr relay.
 *
 * <p>Uses Testcontainers to automatically start a strfry relay and keymaster-avatar
 * in Docker. No manual Docker setup required — just a running Docker daemon.
 *
 * <p>Run with: {@code mvn test}
 */
class KeyMasterAvatarTest {

    private static final Logger log = LoggerFactory.getLogger(KeyMasterAvatarTest.class);

    static final String TEST_MNEMONIC =
            "abandon abandon abandon abandon abandon abandon " +
            "abandon abandon abandon abandon abandon about";

    private static final String AVATAR_BINARY = System.getProperty(
            "avatar.binary",
            "/home/rene/git/club.dwdc.keymaster.avatar/target/release/keymaster-avatar");

    private static final String SSH_AVATAR_BINARY = System.getProperty(
            "ssh.avatar.binary",
            "/home/rene/git/club.dwdc.keymaster.avatar/target/release/ssh-service-avatar");

    private static Network network;

    @SuppressWarnings("resource")
    private static GenericContainer<?> relay;

    @SuppressWarnings("resource")
    private static GenericContainer<?> avatar;

    @SuppressWarnings("resource")
    private static GenericContainer<?> sshd;

    private static String avatarPubKey;

    @BeforeAll
    static void startContainers() throws Exception {
        network = Network.newNetwork();

        // Start strfry relay (tmpfs for LMDB data dir, custom config to disable write policy)
        relay = new GenericContainer<>("dockurr/strfry:1.0.4")
                .withExposedPorts(7777)
                .withNetwork(network)
                .withNetworkAliases("relay")
                .withTmpFs(Map.of("/app/strfry-db", "rw"))
                .withCopyFileToContainer(
                        MountableFile.forClasspathResource("strfry.conf"),
                        "/etc/strfry.conf")
                .waitingFor(Wait.forListeningPort());
        relay.start();
        log.info("Relay started on port {}", relay.getMappedPort(7777));

        // Build sshd target container with authorized_keys from test mnemonic
        KeyMaster kmForKeys = new KeyMaster(TEST_MNEMONIC);
        kmForKeys.createIdentity("alice@atlanta.com");
        String authorizedKeys = kmForKeys.getSshAuthorizedKeysLine();
        log.info("Authorized keys: {}", authorizedKeys);

        ImageFromDockerfile sshdImage = new ImageFromDockerfile()
                .withFileFromPath("Dockerfile", Path.of("docker/sshd/Dockerfile"))
                .withFileFromString("authorized_keys", authorizedKeys + "\n");

        sshd = new GenericContainer<>(sshdImage)
                .withExposedPorts(22)
                .withNetwork(network)
                .withNetworkAliases("sshd-target")
                .withLogConsumer(new Slf4jLogConsumer(log).withPrefix("sshd"))
                .waitingFor(Wait.forListeningPort());
        sshd.start();
        log.info("SSHD container started on port {}", sshd.getMappedPort(22));

        // Build avatar image from Dockerfile + pre-built binaries + entrypoint
        ImageFromDockerfile avatarImage = new ImageFromDockerfile()
                .withFileFromPath("Dockerfile", Path.of("docker/avatar/Dockerfile"))
                .withFileFromPath("keymaster-avatar", Path.of(AVATAR_BINARY))
                .withFileFromPath("ssh-service-avatar", Path.of(SSH_AVATAR_BINARY))
                .withFileFromPath("entrypoint.sh", Path.of("docker/avatar/entrypoint.sh"));

        // Start avatar container (entrypoint starts both avatar + ssh-service-avatar)
        avatar = new GenericContainer<>(avatarImage)
                .withCommand("--relay", "ws://relay:7777", "--log-level", "debug")
                .withNetwork(network)
                .dependsOn(relay)
                .withEnv("SSH_AUTH_SOCK", "/tmp/keymaster-avatar-ssh-agent.sock")
                .withLogConsumer(new Slf4jLogConsumer(log).withPrefix("avatar"))
                .waitingFor(Wait.forLogMessage(".*SSH agent listening.*", 1));
        avatar.start();
        log.info("Avatar container started");

        // Parse avatar pubkey from container logs
        String logs = avatar.getLogs();
        Matcher m = Pattern.compile("Avatar pubkey:\\s*([0-9a-f]{64})").matcher(logs);
        if (!m.find()) {
            throw new IllegalStateException(
                    "Could not find avatar pubkey in logs:\n" + logs);
        }
        avatarPubKey = m.group(1);
        log.info("Avatar pubkey: {}", avatarPubKey);
    }

    @Test
    void attachToAvatar() throws Exception {
        KeyMaster km = new KeyMaster(TEST_MNEMONIC);
        km.createIdentity("alice@atlanta.com");

        String relayUrl = "ws://" + relay.getHost() + ":" + relay.getMappedPort(7777);
        AvatarDescriptor avatar = new AvatarDescriptor(relayUrl, avatarPubKey, List.of());

        String sessionId = km.attach(avatar);
        assertNotNull(sessionId, "Session ID should not be null after attach");

        km.detach();
    }

    @Test
    void sshAgentSocketExists() throws Exception {
        var result = avatar.execInContainer("test", "-S",
                "/tmp/keymaster-avatar-ssh-agent.sock");
        assertEquals(0, result.getExitCode(),
                "SSH agent socket should exist. stderr: " + result.getStderr());
    }

    @Test
    void sshAddReturnsWhenAttached() throws Exception {
        KeyMaster km = new KeyMaster(TEST_MNEMONIC);
        km.createIdentity("alice@atlanta.com");

        String relayUrl = "ws://" + relay.getHost() + ":" + relay.getMappedPort(7777);
        AvatarDescriptor descriptor = new AvatarDescriptor(relayUrl, avatarPubKey, List.of("ssh"));

        String sessionId = km.attach(descriptor);
        assertNotNull(sessionId, "Session ID should not be null after attach");

        // Wait for service.spawn to be processed and service channel to be established
        Thread.sleep(3000);

        // Run ssh-add -l inside the avatar container
        var result = avatar.execInContainer(
                "ssh-add", "-l");

        log.info("ssh-add exit code: {}, stdout: {}, stderr: {}",
                result.getExitCode(), result.getStdout(), result.getStderr());

        // Exit code 2 = agent unreachable, 1 = agent ok but no keys, 0 = keys listed
        assertNotEquals(2, result.getExitCode(),
                "ssh-add should reach the agent (exit code != 2). stderr: " + result.getStderr());

        km.detach();
    }

    @Test
    void sshLoginToRemoteHost() throws Exception {
        KeyMaster km = new KeyMaster(TEST_MNEMONIC);
        km.createIdentity("alice@atlanta.com");

        String relayUrl = "ws://" + relay.getHost() + ":" + relay.getMappedPort(7777);
        AvatarDescriptor descriptor = new AvatarDescriptor(relayUrl, avatarPubKey, List.of("ssh"));

        String sessionId = km.attach(descriptor);
        assertNotNull(sessionId, "Session ID should not be null after attach");

        // Wait for service.spawn + service channel establishment
        Thread.sleep(3000);

        // Verify key is available via ssh-add
        var listResult = avatar.execInContainer("ssh-add", "-l");
        log.info("ssh-add -l exit={}, stdout={}, stderr={}",
                listResult.getExitCode(), listResult.getStdout(), listResult.getStderr());
        assertEquals(0, listResult.getExitCode(),
                "ssh-add -l should list keys. stderr: " + listResult.getStderr());

        // SSH login to the sshd target container
        var sshResult = avatar.execInContainer(
                "ssh",
                "-o", "StrictHostKeyChecking=no",
                "-o", "UserKnownHostsFile=/dev/null",
                "-o", "BatchMode=yes",
                "testuser@sshd-target",
                "echo", "hello-from-keymaster");

        log.info("ssh exit={}, stdout={}, stderr={}",
                sshResult.getExitCode(), sshResult.getStdout(), sshResult.getStderr());

        assertEquals(0, sshResult.getExitCode(),
                "SSH login should succeed. stderr: " + sshResult.getStderr());
        assertTrue(sshResult.getStdout().contains("hello-from-keymaster"),
                "SSH command output should contain 'hello-from-keymaster'. stdout: " + sshResult.getStdout());

        km.detach();
    }

    @AfterAll
    static void tearDown() {
        if (avatar != null) avatar.stop();
        if (sshd != null) sshd.stop();
        if (relay != null) relay.stop();
        if (network != null) network.close();
    }
}
