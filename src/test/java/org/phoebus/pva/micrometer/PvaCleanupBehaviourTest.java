package org.phoebus.pva.micrometer;

import org.epics.pva.PVASettings;
import org.epics.pva.client.PVAChannel;
import org.epics.pva.client.PVAClient;
import org.epics.pva.data.PVADouble;
import org.epics.pva.data.PVAStructure;
import org.epics.pva.server.PVAServer;
import org.epics.pva.server.ServerPV;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

/**
 * Isolated reproducers for three WARNING log entries emitted by {@code core-pva}
 * during normal PVAServer / PVAClient teardown.
 *
 * <p>None of the warnings originate in this project's code; they are defects in
 * the upstream {@code core-pva} library (phoebus project).  These tests exist to:
 * <ol>
 *   <li>Pin the exact sequence of API calls that triggers each warning so that the
 *       upstream issue reports contain a minimal, runnable reproducer.</li>
 *   <li>Detect if a future {@code core-pva} release fixes the issue (the warnings
 *       will disappear from the test output without any code change here).</li>
 * </ol>
 *
 * <p>All three tests <em>pass</em>; they carry no assertions because the observable
 * symptom is a WARNING log line, not a thrown exception or wrong return value.
 *
 * <p>Upstream issue references:
 * <ul>
 *   <li>Issue A – {@code TCPHandler}: log "Socket is closed" at DEBUG, not WARNING</li>
 *   <li>Issue B – {@code PVAClient.close()}: auto-close remaining channels (or warn
 *       selectively based on channel state)</li>
 * </ul>
 *
 * @see <a href="docs/core-pva-cleanup-issues.md">core-pva issue ticket outlines</a>
 */
class PvaCleanupBehaviourTest {

    // -------------------------------------------------------------------------
    // Shared helpers
    // -------------------------------------------------------------------------

    /** Minimal NTScalar structure sufficient to create a {@link ServerPV}. */
    private static PVAStructure minimalDoubleStructure() {
        return new PVAStructure("", "epics:nt/NTScalar:1.0",
                new PVADouble("value", 0.0));
    }

    // -------------------------------------------------------------------------
    // Issue A: TCPHandler logs SocketException "Socket is closed" as WARNING
    // -------------------------------------------------------------------------

    /**
     * Reproduces: {@code WARNING: TCP sender from … exits because of error}
     * {@code java.net.SocketException: Socket is closed}
     *
     * <p>Sequence:
     * <ol>
     *   <li>Start a {@link PVAServer} and publish one PV.</li>
     *   <li>Connect a {@link PVAClient} channel to that PV.</li>
     *   <li>Call {@code server.close()} while the TCP connection is active.</li>
     *   <li>The server-side {@code TCPHandler.sender()} background thread attempts
     *       one more write to the now-closed socket and catches a
     *       {@code SocketException("Socket is closed")}, which it logs as WARNING.</li>
     * </ol>
     *
     * <p><b>Expected core-pva fix (Issue A):</b> {@code TCPHandler.sender()} should
     * recognise "Socket is closed" as expected shutdown behaviour and log at FINE
     * (or not at all) instead of WARNING with a full stack trace.
     */
    @Test
    void tcpSenderLogsWarningWhenServerSocketClosedWhileClientConnected() throws Exception {
        PVAServer server = new PVAServer();
        int port = server.getTCPAddress(false).getPort();

        String savedNameServers = PVASettings.EPICS_PVA_NAME_SERVERS;
        boolean savedAutoAddrList = PVASettings.EPICS_PVA_AUTO_ADDR_LIST;
        PVASettings.EPICS_PVA_NAME_SERVERS = "127.0.0.1:" + port;
        PVASettings.EPICS_PVA_AUTO_ADDR_LIST = false;

        try {
            ServerPV serverPv = server.createPV("cleanup.demo.tcp", minimalDoubleStructure());

            try (PVAClient client = new PVAClient()) {
                PVAChannel channel = client.getChannel("cleanup.demo.tcp");
                channel.connect().get(5, TimeUnit.SECONDS);

                // Close the server while the client TCP connection is live.
                // core-pva logs: "TCP sender … exits because of error: Socket is closed"
                serverPv.close();
                server.close();

                // Brief pause so the sender thread has time to attempt its next write
                // and produce the warning before the test method returns.
                Thread.sleep(200);
            }
        } finally {
            PVASettings.EPICS_PVA_NAME_SERVERS = savedNameServers;
            PVASettings.EPICS_PVA_AUTO_ADDR_LIST = savedAutoAddrList;
            // server.close() is idempotent; safe to call again if the try block threw
            server.close();
        }
    }

    // -------------------------------------------------------------------------
    // Issue B, case 1: PVAClient warns about channels in SEARCHING state
    // -------------------------------------------------------------------------

    /**
     * Reproduces: {@code WARNING: PVA Client closed with remaining channels:
     * ['…' … SEARCHING]}
     *
     * <p>Sequence:
     * <ol>
     *   <li>Start a {@link PVAServer}, publish one PV.</li>
     *   <li>Connect a {@link PVAClient} channel → state becomes CONNECTED.</li>
     *   <li>Call {@code serverPv.close()} — the server sends
     *       {@code CMD_DESTROY_CHANNEL}; the client transitions the channel to
     *       INIT / SEARCHING (seeking to reconnect) rather than CLOSED.</li>
     *   <li>Call {@code client.close()} while the channel is still SEARCHING.</li>
     * </ol>
     *
     * <p><b>Expected core-pva fix (Issue B, option A):</b> {@code PVAClient.close()}
     * should silently auto-close all remaining channels before shutting down.
     * <br>
     * <b>Option B:</b> emit WARNING only for CONNECTED channels; log FINE (or
     * nothing) for SEARCHING / INIT channels, which are already in a "looking to
     * reconnect" state and represent no leak from the user's perspective.
     */
    @Test
    void clientCloseLogsWarningForChannelsInSearchingState() throws Exception {
        PVAServer server = new PVAServer();
        int port = server.getTCPAddress(false).getPort();

        String savedNameServers = PVASettings.EPICS_PVA_NAME_SERVERS;
        boolean savedAutoAddrList = PVASettings.EPICS_PVA_AUTO_ADDR_LIST;
        PVASettings.EPICS_PVA_NAME_SERVERS = "127.0.0.1:" + port;
        PVASettings.EPICS_PVA_AUTO_ADDR_LIST = false;

        try {
            ServerPV serverPv = server.createPV("cleanup.demo.searching", minimalDoubleStructure());

            PVAClient client = new PVAClient();
            PVAChannel channel = client.getChannel("cleanup.demo.searching");
            channel.connect().get(5, TimeUnit.SECONDS);

            // Destroy the server-side PV: the client channel transitions to SEARCHING.
            serverPv.close();
            // Allow the state transition to propagate before closing the client.
            Thread.sleep(200);

            // Close the client while the channel is still SEARCHING (not CLOSED).
            // core-pva logs: "PVA Client closed with remaining channels: ['…' SEARCHING]"
            client.close();
        } finally {
            PVASettings.EPICS_PVA_NAME_SERVERS = savedNameServers;
            PVASettings.EPICS_PVA_AUTO_ADDR_LIST = savedAutoAddrList;
            server.close();
        }
    }

    // -------------------------------------------------------------------------
    // Issue B, case 2: PVAClient warns about channels still CONNECTED
    // -------------------------------------------------------------------------

    /**
     * Reproduces: {@code WARNING: PVA Client closed with remaining channels:
     * ['…' … CONNECTED]}
     *
     * <p>Sequence:
     * <ol>
     *   <li>Start a {@link PVAServer}, publish one PV.</li>
     *   <li>Connect a {@link PVAClient} channel → state becomes CONNECTED.</li>
     *   <li>Call {@code client.close()} <em>without</em> first calling
     *       {@code channel.close()}.</li>
     * </ol>
     *
     * <p>This scenario occurs in our own integration tests when {@code PVAClient} is
     * closed inside a try-with-resources block while the registry's {@code close()}
     * (which would have triggered a server-side channel destroy) is deferred to the
     * surrounding {@code finally} block.
     *
     * <p><b>Expected core-pva fix (Issue B, option A):</b> {@code PVAClient.close()}
     * should silently auto-close all remaining channels before shutting down, just as
     * most {@link java.io.Closeable} containers close their children.
     */
    @Test
    void clientCloseLogsWarningForChannelsStillConnected() throws Exception {
        PVAServer server = new PVAServer();
        int port = server.getTCPAddress(false).getPort();

        String savedNameServers = PVASettings.EPICS_PVA_NAME_SERVERS;
        boolean savedAutoAddrList = PVASettings.EPICS_PVA_AUTO_ADDR_LIST;
        PVASettings.EPICS_PVA_NAME_SERVERS = "127.0.0.1:" + port;
        PVASettings.EPICS_PVA_AUTO_ADDR_LIST = false;

        try {
            ServerPV serverPv = server.createPV("cleanup.demo.connected", minimalDoubleStructure());

            PVAClient client = new PVAClient();
            PVAChannel channel = client.getChannel("cleanup.demo.connected");
            channel.connect().get(5, TimeUnit.SECONDS);

            // Close the client without closing the channel first.
            // core-pva logs: "PVA Client closed with remaining channels: ['…' CONNECTED]"
            client.close();

            serverPv.close();
        } finally {
            PVASettings.EPICS_PVA_NAME_SERVERS = savedNameServers;
            PVASettings.EPICS_PVA_AUTO_ADDR_LIST = savedAutoAddrList;
            server.close();
        }
    }
}
