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
import org.testcontainers.images.builder.Transferable;
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
            "/home/rene/git/club.dwdc.keymaster.avatar/target/release/km-ssh-sa");

    private static final String PKCS11_LIBRARY = System.getProperty(
            "pkcs11.library",
            "/home/rene/git/club.dwdc.keymaster.avatar/target/release/libiz_pkcs11_gpg.so");

    private static final String SCDAEMON_BINARY = System.getProperty(
            "scdaemon.binary",
            "/home/rene/git/club.dwdc.keymaster.avatar/target/release/iz-scdaemon");

    private static Network network;

    @SuppressWarnings("resource")
    private static GenericContainer<?> relay;

    @SuppressWarnings("resource")
    private static GenericContainer<?> avatar;

    @SuppressWarnings("resource")
    private static GenericContainer<?> sshd;

    private static String avatarLoginXpub;

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

        // Build avatar image from Dockerfile + pre-built binaries + scdaemon + entrypoint
        ImageFromDockerfile avatarImage = new ImageFromDockerfile()
                .withFileFromPath("Dockerfile", Path.of("docker/avatar/Dockerfile"))
                .withFileFromPath("keymaster-avatar", Path.of(AVATAR_BINARY))
                .withFileFromPath("km-ssh-sa", Path.of(SSH_AVATAR_BINARY))
                .withFileFromPath("iz-scdaemon", Path.of(SCDAEMON_BINARY))
                .withFileFromPath("libiz_pkcs11_gpg.so", Path.of(PKCS11_LIBRARY))
                .withFileFromPath("entrypoint.sh", Path.of("docker/avatar/entrypoint.sh"));

        // Start avatar container (entrypoint starts both avatar + km-ssh-sa)
        avatar = new GenericContainer<>(avatarImage)
                .withCommand("--relay", "ws://relay:7777", "--log-level", "debug")
                .withNetwork(network)
                .dependsOn(relay)
                .withEnv("SSH_AUTH_SOCK", "/tmp/keymaster-avatar-ssh-agent.sock")
                .withEnv("IZ_PKCS11_SOCKET", "/tmp/keymaster-avatar.sock")
                .withEnv("GNUPGHOME", "/tmp/gnupg-home")
                .withLogConsumer(new Slf4jLogConsumer(log).withPrefix("avatar"))
                .waitingFor(Wait.forLogMessage(".*SSH agent listening.*", 1));
        avatar.start();
        log.info("Avatar container started");

        // Parse avatar login_xpub from container logs
        String logs = avatar.getLogs();
        Matcher m = Pattern.compile("Login xpub:\\s*(xpub[A-Za-z0-9]+)").matcher(logs);
        if (!m.find()) {
            throw new IllegalStateException(
                    "Could not find login xpub in logs:\n" + logs);
        }
        avatarLoginXpub = m.group(1);
        log.info("Avatar login xpub: {}", avatarLoginXpub);
    }

    @Test
    void attachToAvatar() throws Exception {
        KeyMaster km = new KeyMaster(TEST_MNEMONIC);
        km.createIdentity("alice@atlanta.com");

        String relayUrl = "ws://" + relay.getHost() + ":" + relay.getMappedPort(7777);
        AvatarDescriptor avatar = new AvatarDescriptor(relayUrl, avatarLoginXpub, List.of());

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
        AvatarDescriptor descriptor = new AvatarDescriptor(relayUrl, avatarLoginXpub, List.of("ssh"));

        String sessionId = km.attach(descriptor);
        assertNotNull(sessionId, "Session ID should not be null after attach");

        // Channels are derived immediately from xpubs — no service.spawn delay needed
        Thread.sleep(500);

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
        AvatarDescriptor descriptor = new AvatarDescriptor(relayUrl, avatarLoginXpub, List.of("ssh"));

        String sessionId = km.attach(descriptor);
        assertNotNull(sessionId, "Session ID should not be null after attach");

        // Channels are derived immediately from xpubs — no service.spawn delay needed
        Thread.sleep(500);

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

    @Test
    void gpgSignAndVerify() throws Exception {
        // Configure gpg-agent to use iz-scdaemon (our custom scdaemon that speaks
        // Assuan protocol and communicates with the avatar's local API directly,
        // bypassing gnupg-pkcs11-scd which doesn't support Ed25519)
        String gpgAgentConf =
                "scdaemon-program /usr/local/bin/iz-scdaemon\n" +
                "allow-loopback-pinentry\n" +
                "pinentry-program /tmp/gnupg-home/pinentry-iz.sh\n" +
                "log-file /tmp/gnupg-home/gpg-agent.log\n" +
                "verbose\n";
        avatar.copyFileToContainer(
                Transferable.of(gpgAgentConf), "/tmp/gnupg-home/gpg-agent.conf");

        // Write dummy pinentry that returns empty PIN
        String pinentry =
                "#!/bin/bash\n" +
                "echo \"OK Pleased to meet you\"\n" +
                "while IFS= read -r cmd; do\n" +
                "  case \"${cmd%% *}\" in\n" +
                "    GETPIN) echo \"D\"; echo \"OK\";;\n" +
                "    BYE) echo \"OK\"; exit 0;;\n" +
                "    *) echo \"OK\";;\n" +
                "  esac\n" +
                "done\n";
        avatar.copyFileToContainer(
                Transferable.of(pinentry), "/tmp/gnupg-home/pinentry-iz.sh");
        avatar.execInContainer("chmod", "+x", "/tmp/gnupg-home/pinentry-iz.sh");

        // Attach KeyMaster with GPG service
        KeyMaster km = new KeyMaster(TEST_MNEMONIC);
        km.createIdentity("alice@atlanta.com");

        String relayUrl = "ws://" + relay.getHost() + ":" + relay.getMappedPort(7777);
        AvatarDescriptor descriptor = new AvatarDescriptor(relayUrl, avatarLoginXpub, List.of("gpg"));

        String sessionId = km.attach(descriptor);
        assertNotNull(sessionId, "Session ID should not be null after attach");

        // Channels are derived immediately from xpubs — no service.spawn delay needed
        Thread.sleep(500);

        avatar.execInContainer("chmod", "700", "/tmp/gnupg-home");

        // Kill any existing gpg-agent and run SCD LEARN to discover keys.
        // iz-scdaemon imports the cert synchronously before its Assuan greeting,
        // so a single LEARN is sufficient — keygrips match from the first pass.
        avatar.execInContainer(
                "gpgconf", "--homedir", "/tmp/gnupg-home", "--kill", "gpg-agent");
        Thread.sleep(1000);

        var learnResult = avatar.execInContainer("bash", "-c",
                "gpg-connect-agent --homedir /tmp/gnupg-home " +
                "'LEARN --sendinfo --force' /bye 2>&1");
        log.info("SCD LEARN: exit={}, stdout={}",
                learnResult.getExitCode(), learnResult.getStdout());

        // Sign a test message
        avatar.execInContainer("bash", "-c", "echo 'test message' > /tmp/test.txt");

        var signResult = avatar.execInContainer(
                "gpg", "--homedir", "/tmp/gnupg-home",
                "--batch", "--yes", "--clearsign",
                "--pinentry-mode", "loopback", "--passphrase", "",
                "/tmp/test.txt");
        log.info("GPG sign: exit={}, stdout={}, stderr={}",
                signResult.getExitCode(), signResult.getStdout(), signResult.getStderr());
        assertEquals(0, signResult.getExitCode(),
                "GPG clearsign should succeed. stderr: " + signResult.getStderr());

        // Verify the signature
        var verifyResult = avatar.execInContainer(
                "gpg", "--homedir", "/tmp/gnupg-home",
                "--batch", "--verify", "/tmp/test.txt.asc");
        log.info("GPG verify: exit={}, stdout={}, stderr={}",
                verifyResult.getExitCode(), verifyResult.getStdout(), verifyResult.getStderr());
        assertEquals(0, verifyResult.getExitCode(),
                "GPG verify should succeed. stderr: " + verifyResult.getStderr());

        km.detach();
    }

    @Test
    void noServiceSpawnInLogs() {
        // T12: verify no service.spawn round-trip occurred
        String logs = avatar.getLogs();
        assertFalse(logs.contains("service.spawn"),
                "Avatar logs should not contain 'service.spawn' — channels are derived from xpubs");
    }

    @AfterAll
    static void tearDown() {
        if (avatar != null) avatar.stop();
        if (sshd != null) sshd.stop();
        if (relay != null) relay.stop();
        if (network != null) network.close();
    }
}
