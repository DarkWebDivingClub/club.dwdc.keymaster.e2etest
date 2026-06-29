package club.dwdc.keymaster.e2etest;

import club.dwdc.keymaster.KeyMaster;
import club.dwdc.keyvault.core.Bip32KeyVault;
import club.dwdc.keyvault.core.KeyVault;
import club.dwdc.keyvault.desktop.FileSeedStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.MountableFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E test: KeyMaster daemon/client with Avatar over a Nostr relay.
 *
 * <p>Uses Testcontainers to automatically start a strfry relay and keymaster-avatar
 * in Docker. Exercises the full km-daemon + km-cli subprocess chain.
 *
 * <p>Typical run time: ~70 seconds. The 2-minute class-level timeout ensures
 * the suite never hangs indefinitely.
 *
 * <p>Run with: {@code mvn test}
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Timeout(value = 3, unit = TimeUnit.MINUTES)
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

    private static final String GPG_AVATAR_BINARY = System.getProperty(
            "gpg.avatar.binary",
            "/home/rene/git/club.dwdc.keymaster.avatar/target/release/km-gpg-sa");

    private static final String SCD_SHIM_BINARY = System.getProperty(
            "scd.shim.binary",
            "/home/rene/git/club.dwdc.keymaster.avatar/target/release/scd-shim");

    private static final String KV_CLI_JAR = System.getProperty(
            "kv.cli.jar",
            "/home/rene/git/club.dwdc.keyvault/club.dwdc.keyvault.cli/target/kv-cli.jar");

    private static final String KM_DAEMON_JAR = System.getProperty(
            "km.daemon.jar",
            "/home/rene/git/club.dwdc.keymaster/club.dwdc.keymaster.daemon/target/km-daemon.jar");

    private static final String KM_CLI_JAR = System.getProperty(
            "km.cli.jar",
            "/home/rene/git/club.dwdc.keymaster/club.dwdc.keymaster.cli/target/km-cli.jar");

    @TempDir
    static Path kvHome;

    private static Path socketPath;
    private static Path daemonLogFile;
    private static Process daemonProcess;
    private static Network network;

    @SuppressWarnings("resource")
    private static GenericContainer<?> relay;

    @SuppressWarnings("resource")
    private static GenericContainer<?> avatar;

    @SuppressWarnings("resource")
    private static GenericContainer<?> sshd;

    /** Second avatar container with "packaged" layout: binaries off PATH, config-driven. */
    @SuppressWarnings("resource")
    private static GenericContainer<?> avatarPackaged;

    private static String avatarLoginXpub;
    private static String avatarPackagedLoginXpub;

    @BeforeAll
    static void startContainers() throws Exception {
        // Import test mnemonic via kv-cli (verifies full CLI → storage chain)
        ProcessBuilder importPb = new ProcessBuilder(
                "java", "-jar", KV_CLI_JAR, "seed", "import");
        importPb.environment().put("KV_HOME", kvHome.toString());
        importPb.redirectErrorStream(true);
        Process importProc = importPb.start();
        importProc.getOutputStream().write((TEST_MNEMONIC + "\n\n").getBytes(StandardCharsets.UTF_8));
        importProc.getOutputStream().close();
        int importExit = importProc.waitFor();
        assertEquals(0, importExit, "kv-cli seed import should succeed");

        // Verify the seed was stored
        ProcessBuilder existsPb = new ProcessBuilder(
                "java", "-jar", KV_CLI_JAR, "seed", "exists");
        existsPb.environment().put("KV_HOME", kvHome.toString());
        Process existsProc = existsPb.start();
        int existsExit = existsProc.waitFor();
        assertEquals(0, existsExit, "seed should exist after import");

        log.info("Test seed imported via kv-cli into {}", kvHome);

        // Start km-daemon as a background subprocess with log file
        socketPath = kvHome.resolve("keymaster.sock");
        daemonLogFile = kvHome.resolve("km-daemon.log");
        ProcessBuilder daemonPb = new ProcessBuilder(
                "java", "-jar", KM_DAEMON_JAR, "--socket", socketPath.toString());
        daemonPb.environment().put("KV_HOME", kvHome.toString());
        daemonPb.environment().put("KM_HOME", kvHome.toString());
        daemonPb.redirectErrorStream(true);
        daemonPb.redirectOutput(daemonLogFile.toFile());
        daemonProcess = daemonPb.start();

        // Wait for daemon to start listening
        waitForSocket(socketPath, 10);
        log.info("km-daemon started, listening on {}", socketPath);

        // Create identity via km-cli
        int createExit = runKmCli("identity", "create", "alice@atlanta.com");
        assertEquals(0, createExit, "km-cli identity create should succeed");
        log.info("Identity created via km-cli");

        network = Network.newNetwork();

        // Start strfry relay
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

        // Build sshd target container — need in-process KeyMaster to get SSH authorized_keys
        KeyMaster kmForKeys = createKeyMasterInProcess();
        kmForKeys.deriveIdentity("alice@atlanta.com");
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

        // Build avatar image
        ImageFromDockerfile avatarImage = new ImageFromDockerfile()
                .withFileFromPath("Dockerfile", Path.of("docker/avatar/Dockerfile"))
                .withFileFromPath("keymaster-avatar", Path.of(AVATAR_BINARY))
                .withFileFromPath("km-ssh-sa", Path.of(SSH_AVATAR_BINARY))
                .withFileFromPath("km-gpg-sa", Path.of(GPG_AVATAR_BINARY))
                .withFileFromPath("scd-shim", Path.of(SCD_SHIM_BINARY))
                .withFileFromPath("entrypoint.sh", Path.of("docker/avatar/entrypoint.sh"));

        avatar = new GenericContainer<>(avatarImage)
                .withCommand("--relay", "ws://relay:7777", "--log-level", "debug")
                .withNetwork(network)
                .dependsOn(relay)
                .withEnv("SSH_AUTH_SOCK", "/tmp/keymaster-avatar-ssh-agent.sock")
                .withEnv("GNUPGHOME", "/tmp/gnupg-home")
                .withLogConsumer(new Slf4jLogConsumer(log).withPrefix("avatar"))
                .waitingFor(Wait.forLogMessage(".*Local API listening.*", 1));
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

        // Build "packaged layout" avatar: binaries off PATH, config-driven SA lookup
        String avatarToml = "service_avatar_dir = \"/usr/lib/keymaster-avatar\"\n";

        ImageFromDockerfile packagedImage = new ImageFromDockerfile()
                .withFileFromPath("Dockerfile", Path.of("docker/avatar/Dockerfile.packaged"))
                .withFileFromPath("keymaster-avatar", Path.of(AVATAR_BINARY))
                .withFileFromPath("km-ssh-sa", Path.of(SSH_AVATAR_BINARY))
                .withFileFromPath("km-gpg-sa", Path.of(GPG_AVATAR_BINARY))
                .withFileFromPath("scd-shim", Path.of(SCD_SHIM_BINARY))
                .withFileFromPath("entrypoint-packaged.sh", Path.of("docker/avatar/entrypoint-packaged.sh"))
                .withFileFromString("avatar.toml", avatarToml);

        avatarPackaged = new GenericContainer<>(packagedImage)
                .withCommand("--relay", "ws://relay:7777", "--log-level", "debug")
                .withNetwork(network)
                .dependsOn(relay)
                .withEnv("SSH_AUTH_SOCK", "/tmp/keymaster-avatar-ssh-agent.sock")
                .withEnv("GNUPGHOME", "/tmp/gnupg-home")
                .withLogConsumer(new Slf4jLogConsumer(log).withPrefix("avatar-pkg"))
                .waitingFor(Wait.forLogMessage(".*Local API listening.*", 1));
        avatarPackaged.start();
        log.info("Packaged avatar container started");

        String pkgLogs = avatarPackaged.getLogs();
        Matcher mp = Pattern.compile("Login xpub:\\s*(xpub[A-Za-z0-9]+)").matcher(pkgLogs);
        if (!mp.find()) {
            throw new IllegalStateException(
                    "Could not find login xpub in packaged avatar logs:\n" + pkgLogs);
        }
        avatarPackagedLoginXpub = mp.group(1);
        log.info("Packaged avatar login xpub: {}", avatarPackagedLoginXpub);
    }

    @Test
    @Order(1)
    void statusShowsNotAttached() throws Exception {
        int exit = runKmCli("status");
        assertEquals(0, exit, "km-cli status should succeed");
    }

    @Test
    @Order(2)
    void noServiceSpawnInLogs() {
        String logs = avatar.getLogs();
        assertFalse(logs.contains("service.spawn"),
                "Avatar logs should not contain 'service.spawn' — channels are derived from xpubs");
    }

    @Test
    @Order(10)
    void attachViaCliAndSshAddWorks() throws Exception {
        Path descriptorFile = writeDescriptor("descriptor-ssh.json", "ssh");

        int attachExit = runKmCli("attach", descriptorFile.toString(),
                "--identity", "alice@atlanta.com", "--policy", "auto");
        assertEquals(0, attachExit, "km-cli attach should succeed");

        try {
            Thread.sleep(2000);

            // Verify SSH agent socket was created by km-ssh-sa
            var socketResult = avatar.execInContainer("test", "-S",
                    "/tmp/keymaster-avatar-ssh-agent.sock");
            assertEquals(0, socketResult.getExitCode(),
                    "SSH agent socket should exist after attach. stderr: " + socketResult.getStderr());

            // Run ssh-add -l inside the avatar container
            var result = avatar.execInContainer("ssh-add", "-l");
            log.info("ssh-add exit code: {}, stdout: {}, stderr: {}",
                    result.getExitCode(), result.getStdout(), result.getStderr());
            assertNotEquals(2, result.getExitCode(),
                    "ssh-add should reach the agent. stderr: " + result.getStderr());
        } finally {
            runKmCli("detach");
            Thread.sleep(500);
        }
    }

    @Test
    @Order(20)
    void sshLoginToRemoteHost() throws Exception {
        Path descriptorFile = writeDescriptor("descriptor-ssh-login.json", "ssh");

        int attachExit = runKmCli("attach", descriptorFile.toString(),
                "--identity", "alice@atlanta.com", "--policy", "auto");
        assertEquals(0, attachExit, "km-cli attach should succeed");

        try {
            Thread.sleep(2000);

            // Verify key is available
            var listResult = avatar.execInContainer("ssh-add", "-l");
            log.info("ssh-add -l exit={}, stdout={}, stderr={}",
                    listResult.getExitCode(), listResult.getStdout(), listResult.getStderr());
            assertEquals(0, listResult.getExitCode(),
                    "ssh-add -l should list keys. stderr: " + listResult.getStderr());

            // SSH login to the sshd target
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
                    "SSH output should contain 'hello-from-keymaster'. stdout: " + sshResult.getStdout());
        } finally {
            runKmCli("detach");
            Thread.sleep(500);
        }
    }

    @Test
    @Order(30)
    void gpgSignAndVerify() throws Exception {
        Path descriptorFile = writeDescriptor("descriptor-gpg.json", "gpg");

        int attachExit = runKmCli("attach", descriptorFile.toString(),
                "--identity", "alice@atlanta.com", "--policy", "auto");
        assertEquals(0, attachExit, "km-cli attach should succeed");

        try {
            // Wait for km-gpg-sa to complete GPG setup
            Thread.sleep(8000);

            // Sign a test message
            avatar.execInContainer("bash", "-c", "echo 'test message' > /tmp/test.txt");

            var signResult = avatar.execInContainer(
                    "gpg", "--homedir", "/tmp/gnupg-home",
                    "--batch", "--yes", "--clearsign",
                    "--pinentry-mode", "loopback", "--passphrase", "",
                    "/tmp/test.txt");
            log.info("GPG sign: exit={}, stdout={}, stderr={}",
                    signResult.getExitCode(), signResult.getStdout(), signResult.getStderr());

            if (signResult.getExitCode() != 0) {
                // Dump daemon log for debugging
                log.warn("GPG sign failed. Daemon log:\n{}", Files.readString(daemonLogFile));
            }

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
        } finally {
            runKmCli("detach");
            Thread.sleep(500);
        }
    }

    @Test
    @Order(40)
    void serviceProcessRespawnsOnCrash() throws Exception {
        Path descriptorFile = writeDescriptor("descriptor-respawn.json", "ssh");

        int attachExit = runKmCli("attach", descriptorFile.toString(),
                "--identity", "alice@atlanta.com", "--policy", "auto");
        assertEquals(0, attachExit, "km-cli attach should succeed");

        try {
            Thread.sleep(2000);

            // Verify SSH agent is working
            var result1 = avatar.execInContainer("ssh-add", "-l");
            assertEquals(0, result1.getExitCode(),
                    "ssh-add should work before kill. stderr: " + result1.getStderr());

            // Kill km-ssh-sa
            var killResult = avatar.execInContainer("pkill", "km-ssh-sa");
            log.info("pkill exit={}, stdout={}, stderr={}",
                    killResult.getExitCode(), killResult.getStdout(), killResult.getStderr());

            // Wait for respawn
            Thread.sleep(3000);

            // Verify SSH agent works again
            var result2 = avatar.execInContainer("ssh-add", "-l");
            log.info("ssh-add after respawn: exit={}, stdout={}, stderr={}",
                    result2.getExitCode(), result2.getStdout(), result2.getStderr());
            assertEquals(0, result2.getExitCode(),
                    "ssh-add should work after respawn. stderr: " + result2.getStderr());
        } finally {
            runKmCli("detach");
        }
    }

    // ==================== Packaged layout tests ====================
    // These test the "installed as a .deb" scenario: binaries in /usr/lib/keymaster-avatar/
    // (off PATH), avatar in /usr/bin/, config in /etc/keymaster-avatar/.
    // Avatar finds SAs via service_avatar_dir config; km-gpg-sa finds scd-shim via sibling lookup.

    @Test
    @Order(50)
    void packagedLayoutSshWorks() throws Exception {
        // Verify SAs are NOT on PATH — proves we're testing config-driven lookup
        var whichResult = avatarPackaged.execInContainer("which", "km-ssh-sa");
        assertNotEquals(0, whichResult.getExitCode(),
                "km-ssh-sa should NOT be on PATH in packaged layout");

        // Verify config was loaded
        String logs = avatarPackaged.getLogs();
        assertTrue(logs.contains("loaded config from /etc/keymaster-avatar/avatar.toml"),
                "Avatar should have loaded config from /etc/keymaster-avatar/avatar.toml");

        // Attach and test SSH agent
        Path descriptorFile = writeDescriptor("descriptor-pkg-ssh.json", "ssh",
                avatarPackagedLoginXpub);

        int attachExit = runKmCli("attach", descriptorFile.toString(),
                "--identity", "alice@atlanta.com", "--policy", "auto");
        assertEquals(0, attachExit, "km-cli attach should succeed (packaged)");

        try {
            Thread.sleep(2000);

            // Verify SSH agent socket was created
            var socketResult = avatarPackaged.execInContainer("test", "-S",
                    "/tmp/keymaster-avatar-ssh-agent.sock");
            assertEquals(0, socketResult.getExitCode(),
                    "SSH agent socket should exist (packaged). stderr: " + socketResult.getStderr());

            // Run ssh-add -l
            var result = avatarPackaged.execInContainer("ssh-add", "-l");
            log.info("ssh-add (packaged) exit={}, stdout={}, stderr={}",
                    result.getExitCode(), result.getStdout(), result.getStderr());
            assertEquals(0, result.getExitCode(),
                    "ssh-add should list keys (packaged). stderr: " + result.getStderr());
        } finally {
            runKmCli("detach");
            Thread.sleep(500);
        }
    }

    @Test
    @Order(60)
    void packagedLayoutGpgSignWorks() throws Exception {
        // Verify scd-shim is NOT on PATH — proves sibling lookup is needed
        var whichResult = avatarPackaged.execInContainer("which", "scd-shim");
        assertNotEquals(0, whichResult.getExitCode(),
                "scd-shim should NOT be on PATH in packaged layout");

        Path descriptorFile = writeDescriptor("descriptor-pkg-gpg.json", "gpg",
                avatarPackagedLoginXpub);

        int attachExit = runKmCli("attach", descriptorFile.toString(),
                "--identity", "alice@atlanta.com", "--policy", "auto");
        assertEquals(0, attachExit, "km-cli attach should succeed (packaged GPG)");

        try {
            // Wait for km-gpg-sa to complete GPG setup (LEARN, import, ownertrust, stubs)
            Thread.sleep(8000);

            // Sign a test message
            avatarPackaged.execInContainer("bash", "-c", "echo 'packaged test' > /tmp/test-pkg.txt");

            var signResult = avatarPackaged.execInContainer(
                    "gpg", "--homedir", "/tmp/gnupg-home",
                    "--batch", "--yes", "--clearsign",
                    "--pinentry-mode", "loopback", "--passphrase", "",
                    "/tmp/test-pkg.txt");
            log.info("GPG sign (packaged): exit={}, stdout={}, stderr={}",
                    signResult.getExitCode(), signResult.getStdout(), signResult.getStderr());
            assertEquals(0, signResult.getExitCode(),
                    "GPG clearsign should succeed (packaged). stderr: " + signResult.getStderr());

            // Verify the signature
            var verifyResult = avatarPackaged.execInContainer(
                    "gpg", "--homedir", "/tmp/gnupg-home",
                    "--batch", "--verify", "/tmp/test-pkg.txt.asc");
            log.info("GPG verify (packaged): exit={}, stdout={}, stderr={}",
                    verifyResult.getExitCode(), verifyResult.getStdout(), verifyResult.getStderr());
            assertEquals(0, verifyResult.getExitCode(),
                    "GPG verify should succeed (packaged). stderr: " + verifyResult.getStderr());
        } finally {
            runKmCli("detach");
            Thread.sleep(500);
        }
    }

    // ==================== Helpers ====================

    /**
     * Write an avatar descriptor JSON file for the given service (uses default avatar).
     */
    private static Path writeDescriptor(String filename, String service) throws Exception {
        return writeDescriptor(filename, service, avatarLoginXpub);
    }

    /**
     * Write an avatar descriptor JSON file for the given service and login xpub.
     */
    private static Path writeDescriptor(String filename, String service, String loginXpub) throws Exception {
        String relayUrl = "ws://" + relay.getHost() + ":" + relay.getMappedPort(7777);
        String descriptorJson = String.format(
                "{\"relay\":\"%s\",\"login_xpub\":\"%s\",\"services\":[\"%s\"]}",
                relayUrl, loginXpub, service);
        Path file = kvHome.resolve(filename);
        Files.writeString(file, descriptorJson);
        return file;
    }

    /**
     * Run km-cli as a subprocess pointing at our test daemon socket.
     */
    private static int runKmCli(String... args) throws Exception {
        String[] cmd = new String[args.length + 3];
        cmd[0] = "java";
        cmd[1] = "-jar";
        cmd[2] = KM_CLI_JAR;
        System.arraycopy(args, 0, cmd, 3, args.length);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().put("KM_SOCKET", socketPath.toString());
        pb.redirectErrorStream(true);
        Process proc = pb.start();

        // Read output with timeout — process should complete quickly for RPC calls
        boolean finished = proc.waitFor(60, TimeUnit.SECONDS);
        if (!finished) {
            log.warn("km-cli {} did not finish within 60s, killing", args.length > 0 ? args[0] : "");
            proc.destroyForcibly();
            fail("km-cli timed out after 60s");
        }

        // Read remaining output after process has exited
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.info("[km-cli] {}", line);
            }
        }

        return proc.exitValue();
    }

    /**
     * Wait for a Unix domain socket file to appear.
     */
    private static void waitForSocket(Path socket, int maxSeconds) throws Exception {
        for (int i = 0; i < maxSeconds * 10; i++) {
            if (Files.exists(socket)) {
                return;
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("Socket " + socket + " did not appear within " + maxSeconds + "s");
    }

    /**
     * Create a KeyMaster in-process (for SSH authorized_keys derivation only).
     */
    private static KeyMaster createKeyMasterInProcess() {
        FileSeedStore store = new FileSeedStore(kvHome);
        KeyVault vault = new Bip32KeyVault(store.getMnemonic(), store.getPassphrase());
        return new KeyMaster(vault);
    }

    @AfterAll
    static void tearDown() {
        if (avatarPackaged != null) avatarPackaged.stop();
        if (avatar != null) avatar.stop();
        if (sshd != null) sshd.stop();
        if (relay != null) relay.stop();
        if (network != null) network.close();

        if (daemonProcess != null && daemonProcess.isAlive()) {
            daemonProcess.destroy();
            try {
                daemonProcess.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                daemonProcess.destroyForcibly();
            }
        }

        // Dump daemon log for post-mortem debugging
        if (daemonLogFile != null) {
            try {
                String daemonLog = Files.readString(daemonLogFile);
                log.info("=== km-daemon log ===\n{}", daemonLog);
            } catch (Exception e) {
                // ignore
            }
        }
    }
}
