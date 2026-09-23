package com.abdelrhman.webguard.vpn;

import android.net.VpnService;
import com.abdelrhman.webguard.core.DomainBlocker;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Local SOCKS5 gateway used by the tun2socks engine.
 * Outbound sockets are explicitly protected so they do not recurse into the VPN.
 */
public final class Socks5ProxyServer {
    private static final int PORT = 1080;
    private final VpnService vpn;
    private final DomainBlocker.ContextFacade blocker;
    private final DnsClient dns;

    private volatile boolean running;
    private ServerSocket server;
    private Thread acceptThread;

    public Socks5ProxyServer(VpnService vpn, android.content.Context context) {
        this.vpn = vpn;
        this.blocker = new DomainBlocker.ContextFacade(context);
        this.dns = new DnsClient(vpn);
    }

    public synchronized void start() throws IOException {
        if (running) return;
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress("127.0.0.1", PORT));
        running = true;

        acceptThread = new Thread(() -> {
            while (running) {
                try {
                    final Socket client = server.accept();
                    Thread t = new Thread(() -> handleClient(client), "WebGuard-SOCKS-Client");
                    t.setDaemon(true);
                    t.start();
                } catch (IOException e) {
                    if (running) e.printStackTrace();
                }
            }
        }, "WebGuard-SOCKS-Accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public synchronized void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (Exception ignored) {}
        server = null;
    }

    private void handleClient(Socket client) {
        try (Socket c = client) {
            c.setTcpNoDelay(true);
            InputStream in = c.getInputStream();
            OutputStream out = c.getOutputStream();

            if (!handshake(in, out)) return;

            int ver = in.read();
            int cmd = in.read();
            int rsv = in.read();
            int atyp = in.read();
            if (ver != 5 || rsv != 0 || cmd < 0 || atyp < 0) return;

            Destination dst = readDestination(in, atyp);
            if (dst == null) {
                sendReply(out, 0x08, 0, 0);
                return;
            }

            if (cmd == 1) {
                handleConnect(c, out, dst);
            } else if (cmd == 3) {
                handleUdpAssociate(c, out);
            } else {
                sendReply(out, 0x07, 0, 0);
            }
        } catch (Exception ignored) {
        }
    }

    private boolean handshake(InputStream in, OutputStream out) throws IOException {
        int ver = in.read();
        int nMethods = in.read();
        if (ver != 5 || nMethods < 0 || nMethods > 255) return false;

        byte[] methods = readFully(in, nMethods);
        boolean noAuth = false;
        for (byte method : methods) if ((method & 255) == 0x00) noAuth = true;

        out.write(new byte[]{0x05, (byte) (noAuth ? 0x00 : 0xFF)});
        out.flush();
        return noAuth;
    }

    private Destination readDestination(InputStream in, int atyp) throws IOException {
        String host;
        if (atyp == 1) {
            byte[] a = readFully(in, 4);
            host = (a[0] & 255) + "." + (a[1] & 255) + "." + (a[2] & 255) + "." + (a[3] & 255);
        } else if (atyp == 3) {
            int len = in.read();
            if (len < 1 || len > 255) return null;
            host = new String(readFully(in, len), StandardCharsets.US_ASCII);
        } else if (atyp == 4) {
            byte[] a = readFully(in, 16);
            host = InetAddress.getByAddress(a).getHostAddress();
        } else {
            return null;
        }

        int hi = in.read();
        int lo = in.read();
        if (hi < 0 || lo < 0) return null;
        return new Destination(host, (hi << 8) | lo, atyp == 3);
    }

    private void handleConnect(Socket client, OutputStream clientOut, Destination dst) {
        if (dst.isDomain && DomainBlocker.isBlocked(blocker.context, dst.host)) {
            BlockLog.record(blocker.context, dst.host);
            safeReply(clientOut, 0x02, 0, 0);
            return;
        }

        try {
            InetAddress[] addresses = dst.isDomain ? dns.resolve(dst.host)
                    : new InetAddress[]{InetAddress.getByName(dst.host)};

            Socket remote = null;
            IOException last = null;
            for (InetAddress address : addresses) {
                try {
                    Socket candidate = new Socket();
                    candidate.setTcpNoDelay(true);
                    if (!vpn.protect(candidate)) {
                        candidate.close();
                        throw new IOException("Cannot protect outbound socket");
                    }
                    candidate.connect(new InetSocketAddress(address, dst.port), 7000);
                    remote = candidate;
                    break;
                } catch (IOException e) {
                    last = e;
                }
            }

            if (remote == null) {
                safeReply(clientOut, 0x04, 0, 0);
                return;
            }

            try (Socket r = remote) {
                sendReply(clientOut, 0x00,
                        ((InetSocketAddress) r.getLocalSocketAddress()).getAddress().getAddress(),
                        ((InetSocketAddress) r.getLocalSocketAddress()).getPort());

                relay(client, r);
            }
        } catch (Exception e) {
            safeReply(clientOut, 0x05, 0, 0);
        }
    }

    private void handleUdpAssociate(Socket control, OutputStream out) {
        DatagramSocket udp = null;
        AtomicBoolean closed = new AtomicBoolean(false);
        try {
            udp = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
            if (!vpn.protect(udp)) throw new IOException("Cannot protect UDP socket");
            final DatagramSocket finalUdp = udp;
            byte[] bound = finalUdp.getLocalAddress().getAddress();
            sendReply(out, 0x00, bound, finalUdp.getLocalPort());

            InetSocketAddress clientAddress = null;
            byte[] buffer = new byte[65535];

            while (running && !control.isClosed() && !closed.get()) {
                DatagramPacket p = new DatagramPacket(buffer, buffer.length);
                finalUdp.receive(p);
                if (p.getAddress().isLoopback() || p.getAddress().isAnyLocalAddress()) {
                    UdpRequest req = parseUdpRequest(p.getData(), p.getLength());
                    if (req == null) continue;

                    if (req.domain != null && DomainBlocker.isBlocked(blocker.context, req.domain)) {
                        BlockLog.record(blocker.context, req.domain);
                        continue;
                    }

                    InetAddress target;
                    if (req.domain != null) {
                        InetAddress[] resolved = dns.resolve(req.domain);
                        if (resolved.length == 0) continue;
                        target = resolved[0];
                    } else {
                        target = req.address;
                    }

                    byte[] payload = req.payload;
                    DatagramPacket targetPacket = new DatagramPacket(
                            payload, payload.length, target, req.port);
                    finalUdp.send(targetPacket);
                    clientAddress = new InetSocketAddress(p.getAddress(), p.getPort());

                    // Collect a response for this datagram.
                    finalUdp.setSoTimeout(1200);
                    try {
                        DatagramPacket response = new DatagramPacket(buffer, buffer.length);
                        finalUdp.receive(response);
                        byte[] wrapped = wrapUdpResponse(response);
                        DatagramPacket back = new DatagramPacket(
                                wrapped, wrapped.length, clientAddress.getAddress(), clientAddress.getPort());
                        finalUdp.send(back);
                    } catch (SocketTimeoutException ignored) {
                    } finally {
                        finalUdp.setSoTimeout(0);
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            closed.set(true);
            if (udp != null) udp.close();
        }
    }

    private static UdpRequest parseUdpRequest(byte[] data, int len) throws IOException {
        if (len < 10 || data[0] != 0 || data[1] != 0 || data[2] != 0) return null;
        int o = 3;
        int atyp = data[o++] & 255;
        InetAddress address = null;
        String domain = null;

        if (atyp == 1) {
            if (o + 4 > len) return null;
            address = InetAddress.getByAddress(Arrays.copyOfRange(data, o, o + 4));
            o += 4;
        } else if (atyp == 3) {
            if (o >= len) return null;
            int n = data[o++] & 255;
            if (n < 1 || o + n > len) return null;
            domain = new String(data, o, n, StandardCharsets.US_ASCII).toLowerCase(Locale.US);
            o += n;
        } else if (atyp == 4) {
            if (o + 16 > len) return null;
            address = InetAddress.getByAddress(Arrays.copyOfRange(data, o, o + 16));
            o += 16;
        } else {
            return null;
        }

        if (o + 2 > len) return null;
        int port = ((data[o] & 255) << 8) | (data[o + 1] & 255);
        o += 2;
        return new UdpRequest(address, domain, port, Arrays.copyOfRange(data, o, len));
    }

    private static byte[] wrapUdpResponse(DatagramPacket response) throws IOException {
        byte[] a = response.getAddress().getAddress();
        int atyp = a.length == 4 ? 1 : 4;
        ByteArrayOutputStream out = new ByteArrayOutputStream(response.getLength() + 32);
        out.write(0); out.write(0); out.write(0); // RSV + FRAG
        out.write(atyp);
        out.write(a, 0, a.length);
        out.write((response.getPort() >>> 8) & 255);
        out.write(response.getPort() & 255);
        out.write(response.getData(), response.getOffset(), response.getLength());
        return out.toByteArray();
    }

    private static void relay(Socket a, Socket b) throws IOException {
        Thread one = new Thread(() -> copy(a, b), "WebGuard-relay-A");
        Thread two = new Thread(() -> copy(b, a), "WebGuard-relay-B");
        one.setDaemon(true); two.setDaemon(true);
        one.start(); two.start();
        try { one.join(); } catch (InterruptedException ignored) {}
        try { two.join(250); } catch (InterruptedException ignored) {}
    }

    private static void copy(Socket from, Socket to) {
        try {
            byte[] buf = new byte[16 * 1024];
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (IOException ignored) {
        } finally {
            try { from.shutdownInput(); } catch (Exception ignored) {}
            try { to.shutdownOutput(); } catch (Exception ignored) {}
            try { from.close(); } catch (Exception ignored) {}
            try { to.close(); } catch (Exception ignored) {}
        }
    }

    private static byte[] readFully(InputStream in, int n) throws IOException {
        byte[] b = new byte[n];
        int o = 0;
        while (o < n) {
            int r = in.read(b, o, n - o);
            if (r < 0) throw new EOFException();
            o += r;
        }
        return b;
    }

    private static void sendReply(OutputStream out, int rep, byte[] address, int port) throws IOException {
        byte[] a = (address == null || address.length == 0) ? new byte[]{0, 0, 0, 0} : address;
        int atyp = a.length == 16 ? 4 : 1;
        out.write(new byte[]{0x05, (byte) rep, 0, (byte) atyp});
        out.write(a);
        out.write((port >>> 8) & 255);
        out.write(port & 255);
        out.flush();
    }

    private static void safeReply(OutputStream out, int rep, int address, int port) {
        try { sendReply(out, rep, new byte[]{0,0,0,0}, port); } catch (Exception ignored) {}
    }

    private static final class Destination {
        final String host; final int port; final boolean isDomain;
        Destination(String host, int port, boolean isDomain) {
            this.host = host; this.port = port; this.isDomain = isDomain;
        }
    }

    private static final class UdpRequest {
        final InetAddress address; final String domain; final int port; final byte[] payload;
        UdpRequest(InetAddress address, String domain, int port, byte[] payload) {
            this.address = address; this.domain = domain; this.port = port; this.payload = payload;
        }
    }
}
