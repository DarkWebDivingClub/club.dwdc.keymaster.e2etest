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

    private static Network network;

    @SuppressWarnings("resource")
    private static GenericContainer<?> relay;

    @SuppressWarnings("resource")
    private static GenericContainer<?> avatar;

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

        // Build avatar image from Dockerfile + pre-built binary
        ImageFromDockerfile avatarImage = new ImageFromDockerfile()
                .withFileFromPath("Dockerfile", Path.of("docker/avatar/Dockerfile"))
                .withFileFromPath("keymaster-avatar", Path.of(AVATAR_BINARY));

        // Start avatar container
        avatar = new GenericContainer<>(avatarImage)
                .withCommand("--relay", "ws://relay:7777", "--log-level", "debug")
                .withNetwork(network)
                .dependsOn(relay)
                .withLogConsumer(new Slf4jLogConsumer(log).withPrefix("avatar"))
                .waitingFor(Wait.forLogMessage(".*Avatar pubkey:.*", 1));
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

    @AfterAll
    static void tearDown() {
        if (avatar != null) avatar.stop();
        if (relay != null) relay.stop();
        if (network != null) network.close();
    }
}
